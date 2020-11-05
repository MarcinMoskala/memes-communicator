package com.example

import com.google.gson.Gson
import io.ktor.application.call
import io.ktor.application.install
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
import java.time.Duration

fun main(args: Array<String>) {
    val jsonResponse = """{
        "id": 1,
        "task": "Pay waterbill",
        "description": "Pay water bill today",
    }"""
    embeddedServer(Netty, 8080) {
        install(WebSockets) {
            pingPeriod = Duration.ofMinutes(1)
        }

        install(Routing) {
            val memes = mutableListOf<Meme>()
            val connections = mutableListOf<DefaultWebSocketSession>()

            get("/") {
                call.respondText("Hello World!", ContentType.Text.Plain)
            }
            get("/todo") {
                call.respondText(jsonResponse, ContentType.Application.Json)
            }
            webSocket("/ws") {
                connections += this
                outgoing.send(Memes(memes).toJson().let(Frame::Text))
                try {
                    for (msg in incoming) {
                        when (msg) {
                            is Frame.Text -> {
                                val meme = msg.readText().fromJson<Meme>()
                                memes += meme
                                connections.forEach {
                                    it.send(Memes(memes).toJson().let(Frame::Text))
                                }
                            }
                        }
                    }
                } finally {
                    connections -= this
                }
            }
        }
    }.start(wait = true)
}

fun Any.toJson(): String = Gson().toJson(this)
inline fun <reified T> String.fromJson(): T = Gson().fromJson(this, T::class.java)

class Memes(
    val memes: List<Meme>
)

class Meme(
    val author: String,
    val text: String?,
    val imgSrc: String?,
)