package com.lightningkite.lightningserver.core

import com.lightningkite.lightningserver.http.Http
import com.lightningkite.lightningserver.schedule.Scheduler
import com.lightningkite.lightningserver.tasks.Tasks
import com.lightningkite.lightningserver.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

fun allServerEntryPoints(): List<String> {
    return listOf(
        Http.endpoints.keys.map { it.toString() },
        WebSockets.handlers.keys.flatMap {
            listOf(
                WebSockets.HandlerSection(it, WebSockets.WsHandlerType.CONNECT).toString(),
                WebSockets.HandlerSection(it, WebSockets.WsHandlerType.MESSAGE).toString(),
                WebSockets.HandlerSection(it, WebSockets.WsHandlerType.DISCONNECT).toString()
            )
        },
        Scheduler.schedules.map { it.toString() },
        Tasks.tasks.map { it.toString() }
    ).flatten()
}

class ServerEntryPointElement(val entryPoint: Any) : AbstractCoroutineContextElement(ServerEntryPointElement) {
    companion object Key : CoroutineContext.Key<ServerEntryPointElement>
}

val serverLogger = LoggerFactory.getLogger("LightningServer")
suspend fun <T> serverEntryPoint(entryPoint: Any, action: suspend CoroutineScope.() -> T): T {
    serverLogger.info("Handling $entryPoint")
    return withContext(ServerEntryPointElement(entryPoint), action)
}

suspend fun serverEntryPoint(): Any? = coroutineContext[ServerEntryPointElement.Key]?.entryPoint
