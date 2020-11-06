package com.example

import com.google.gson.Gson
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.CallLogging
import io.ktor.http.ContentType
import io.ktor.http.cio.websocket.DefaultWebSocketSession
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.pingPeriod
import io.ktor.http.cio.websocket.readText
import io.ktor.response.respondText
import io.ktor.routing.Routing
import io.ktor.routing.get
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.websocket.WebSockets
import io.ktor.websocket.webSocket
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.litote.kmongo.coroutine.coroutine
import org.litote.kmongo.reactivestreams.KMongo
import org.slf4j.event.Level
import java.time.Duration
import java.util.logging.Logger

class MemesRepository {
    val mongo = KMongo.createClient("mongodb+srv://Test1234:Test1234@cluster0.ashb0.mongodb.net/<dbname>?retryWrites=true&w=majority").coroutine
        .getDatabase("memes")
        .getCollection<Meme>("memes")

    suspend fun currentMemes(): Memes = Memes(mongo.find().toList())

    suspend fun addMeme(meme: Meme) {
        logger.info("Adding meme $meme")
        mongo.insertOne(Meme("Michał", "Some text", "https://i1.kwejk.pl/k/obrazki/2020/11/gTMqPwhPEX4yF14S.jpg"))
    }

    companion object {
        val logger = Logger.getLogger("MemesRepository")
    }
}

class ConnectionsService(private val memesRepository: MemesRepository) {
    private val connections = mutableListOf<DefaultWebSocketSession>()
    private val mutex = Mutex()

    suspend fun addConnection(connection: DefaultWebSocketSession): Unit = mutex.withLock {
        connections += connection
    }

    suspend fun removeConnection(connection: DefaultWebSocketSession): Unit = mutex.withLock {
        connections -= connection
    }

    suspend fun sendToAll() {
        connections.forEach { send(it, memesRepository.currentMemes()) }
    }

    suspend fun send(connection: DefaultWebSocketSession, memes: Memes? = null) = mutex.withLock {
        val memes = memes ?: memesRepository.currentMemes()
        logger.info("Sending memes ${memes}")
        connection.outgoing.send(memesRepository.currentMemes().toJson().let(Frame::Text))
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

        install(Routing) {
            val memesRepository = MemesRepository()
            val connectionsRepository = ConnectionsService(memesRepository)

            get("/") {
                call.respondText("Hello World!", ContentType.Text.Plain)
            }
            webSocket("/ws") {
                connectionsRepository.addConnection(this)
                connectionsRepository.send(this)
                try {
                    for (msg in incoming) {
                        when (msg) {
                            is Frame.Text -> {
                                val meme = msg.readText().fromJson<Meme>()
                                memesRepository.addMeme(meme)
                                connectionsRepository.sendToAll()
                            }
                        }
                    }
                } finally {
                    connectionsRepository.removeConnection(this)
                }
            }
        }
    }.start(wait = true)
}

fun Any.toJson(): String = Gson().toJson(this)
inline fun <reified T> String.fromJson(): T = Gson().fromJson(this, T::class.java)

data class Memes(
    val memes: List<Meme>
)

data class Meme(
    val author: String,
    val text: String?,
    val imgSrc: String?,
)