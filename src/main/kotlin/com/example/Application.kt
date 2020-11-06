package com.example

import com.google.gson.Gson
import io.ktor.application.ApplicationCall
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.content.TextContent
import io.ktor.features.CORS
import io.ktor.features.CallLogging
import io.ktor.features.ContentConverter
import io.ktor.features.ContentNegotiation
import io.ktor.features.suitableCharset
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.cio.websocket.DefaultWebSocketSession
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.pingPeriod
import io.ktor.http.withCharset
import io.ktor.request.ApplicationReceiveRequest
import io.ktor.request.contentCharset
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.Routing
import io.ktor.routing.delete
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.put
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.util.collections.ConcurrentList
import io.ktor.util.pipeline.PipelineContext
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.charsets.decode
import io.ktor.utils.io.readRemaining
import io.ktor.websocket.WebSockets
import io.ktor.websocket.webSocket
import org.litote.kmongo.coroutine.coroutine
import org.litote.kmongo.eq
import org.litote.kmongo.inc
import org.litote.kmongo.reactivestreams.KMongo
import org.slf4j.event.Level
import java.time.Duration
import java.util.*
import java.util.logging.Logger

class MemesRepository {
    val mongo = KMongo
            .createClient("mongodb+srv://Test1234:Test1234@cluster0.ashb0.mongodb.net/<dbname>?retryWrites=true&w=majority").coroutine
            .getDatabase("memes")
            .getCollection<Meme>("memes")

    suspend fun currentMemes(): Memes = Memes(mongo.find().toList())

    suspend fun addMeme(meme: Meme) {
        val meme = meme.copy(id = UUID.randomUUID().toString())
        logger.info("Adding meme $meme")
        mongo.insertOne(meme)
    }

    suspend fun removeMeme(memeId: String) {
        mongo.deleteOne(Meme::id eq memeId)
    }

    suspend fun updateMeme(memeId: String) {
        mongo.updateOne(Meme::id eq memeId, inc(Meme::likes, 1))
    }

    companion object {
        val logger = Logger.getLogger("MemesRepository")
    }
}

class ConnectionsService(private val memesRepository: MemesRepository) {
    private val connections = ConcurrentList<DefaultWebSocketSession>()

    suspend fun addConnection(connection: DefaultWebSocketSession) {
        connections += connection
    }

    suspend fun removeConnection(connection: DefaultWebSocketSession) {
        connections -= connection
    }

    suspend fun sendToAll() {
        connections.forEach { send(it, memesRepository.currentMemes()) }
    }

    suspend fun send(connection: DefaultWebSocketSession, memes: Memes? = null) {
        val memes = memes ?: memesRepository.currentMemes()
        connection.outgoing.send(memes.toJson().let(Frame::Text))
    }

    companion object {
        val logger = Logger.getLogger("ConnectionsService")
    }
}

fun main(args: Array<String>) {
    val port = Integer.valueOf(System.getenv("PORT") ?: "8080")
    embeddedServer(Netty, port) {
        install(CallLogging) {
            level = Level.INFO
        }

        install(WebSockets) {
            pingPeriod = Duration.ofMinutes(1)
        }

        install(CORS) {
            anyHost()
            header(HttpHeaders.XForwardedProto)
            method(HttpMethod.Options)
            method(HttpMethod.Delete)
            method(HttpMethod.Post)
            method(HttpMethod.Patch)
            method(HttpMethod.Put)
            header("userUuid")
            allowCredentials = true
            allowNonSimpleContentTypes = true
        }

        install(ContentNegotiation) {
            register(ContentType.Application.Json, GsonConverter)
        }

        install(Routing) {
            val memesRepository = MemesRepository()
            val connectionsRepository = ConnectionsService(memesRepository)

            get("/") {
                call.respondText("Hello World!", ContentType.Text.Plain)
            }

            post("/meme/imgSrc") {
                val meme = call.receive<Meme>()
                memesRepository.addMeme(meme)
                connectionsRepository.sendToAll()
                call.respond(HttpStatusCode.OK)
            }

            put("/meme/{memeId}") {
                val memeId = call.parameters["memeId"]!!
                memesRepository.updateMeme(memeId)
                connectionsRepository.sendToAll()
                call.respond(HttpStatusCode.OK)
            }

            delete("/meme/{memeId}") {
                val memeId = call.parameters["memeId"]!!
                memesRepository.removeMeme(memeId)
                connectionsRepository.sendToAll()
                call.respond(HttpStatusCode.OK)
            }

            webSocket("/ws") {
                connectionsRepository.addConnection(this)
                connectionsRepository.send(this)
                try {
                    for (msg in incoming) {
                    }
                } finally {
                    connectionsRepository.removeConnection(this)
                }
            }
        }
    }.start(wait = true)
}

data class Memes(
        val memes: List<Meme>
)

data class Meme(
    val id: String?,
    val author: String?,
    val text: String?,
    val imgSrc: String?,
    val imgBase64: String?,
    val likes: Int = 0,
)

// Converters

fun Any.toJson(): String = globalGson.toJson(this)
inline fun <reified T> String.fromJson(): T = globalGson.fromJson(this, T::class.java)

object GsonConverter : ContentConverter {
    override suspend fun convertForSend(context: PipelineContext<Any, ApplicationCall>, contentType: ContentType, value: Any): Any? {
        return TextContent(globalGson.toJson(value), contentType.withCharset(context.call.suitableCharset()))
    }

    override suspend fun convertForReceive(context: PipelineContext<ApplicationReceiveRequest, ApplicationCall>): Any? {
        val request = context.subject
        val channel = request.value as? ByteReadChannel ?: return null
        val reader = (context.call.request.contentCharset() ?: Charsets.UTF_8).newDecoder()
                .decode(channel.readRemaining()).reader()
        return globalGson.fromJson(reader, request.type.javaObjectType)
    }
}

val globalGson by lazy {
    Gson().newBuilder()
            .serializeNulls().create()
}