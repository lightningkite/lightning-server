package com.lightningkite.lightningserver.reporting

import com.lightningkite.lightningserver.metrics.MetricType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

class ServerEntryPointElement(val entryPoint: Any, val metricSums: ConcurrentHashMap<MetricType, Double> = ConcurrentHashMap()) : AbstractCoroutineContextElement(ServerEntryPointElement) {
    companion object Key : CoroutineContext.Key<ServerEntryPointElement>
}

suspend fun <T> serverEntryPoint(entryPoint: Any, action: suspend CoroutineScope.() -> T): T {
    return withContext(ServerEntryPointElement(entryPoint), action)
}

suspend fun serverEntryPoint(): Any? = coroutineContext[ServerEntryPointElement.Key]?.entryPoint
