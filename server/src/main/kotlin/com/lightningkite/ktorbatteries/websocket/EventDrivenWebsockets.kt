package com.lightningkite.ktorbatteries.websocket

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.websocket.*

//fun Route.websocket(
//    startup: suspend (String)->Unit,
//    onMessage: suspend (String, Frame)->Unit,
//    shutdown: suspend (String)->Unit
//): Unit = TODO()
//
//suspend fun Application.websocketSend(id: String, frame: Frame): Unit = TODO()