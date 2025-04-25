package com.lightningkite.lightningserver.core

import com.lightningkite.lightningserver.http.Request
import com.lightningkite.lightningserver.metrics.MetricType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
//
//fun allServerEntryPoints(): List<ServerEntryPoint> {
//    return listOf<Collection<ServerEntryPoint>>(
//        Http.endpoints.keys,
//        WebSockets.handlers.keys.flatMap {
//            listOf(
//                WebSockets.HandlerSection(it, WebSockets.WsHandlerType.CONNECTING),
//                WebSockets.HandlerSection(it, WebSockets.WsHandlerType.CONNECTED),
//                WebSockets.HandlerSection(it, WebSockets.WsHandlerType.MESSAGE),
//                WebSockets.HandlerSection(it, WebSockets.WsHandlerType.WSSUB),
//                WebSockets.HandlerSection(it, WebSockets.WsHandlerType.DISCONNECT)
//            )
//        },
//        Scheduler.schedules.values,
//        Tasks.tasks.values
//    ).flatten()
//}
//
interface ServerEntryPoint {

}

val serverLogger = LoggerFactory.getLogger("LightningServer")

interface ServerContext {
    val entryPoint: ServerEntryPoint
    val request: Request?
    suspend fun logString(): String
}


class ServerContextElement(val context: ServerContext, val metricSums: ConcurrentHashMap<MetricType, Double> = ConcurrentHashMap()) : AbstractCoroutineContextElement(ServerContextElement) {
    companion object Key : CoroutineContext.Key<ServerContextElement>
}

suspend fun <T> serverContext(context: ServerContext, action: suspend CoroutineScope.() -> T): T {
    serverLogger.info("Handling ${context.logString()}")
    return withContext(ServerContextElement(context), action)
}

suspend fun serverContext(): ServerContext? = coroutineContext[ServerContextElement.Key]?.context
