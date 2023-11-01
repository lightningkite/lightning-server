package com.lightningkite.lightningserver.pubsub

import com.lightningkite.lightningserver.services.Disconnectable
import com.lightningkite.lightningserver.serverhealth.HealthCheckable
import com.lightningkite.lightningserver.serverhealth.HealthStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer

@Deprecated("Renamed to just 'PubSub'", ReplaceWith("PubSub", "com.lightningkite.lightningserver.pubsub.PubSub"))
typealias PubSubInterface = PubSub

interface PubSub : HealthCheckable, Disconnectable {
    fun <T> get(key: String, serializer: KSerializer<T>): PubSubChannel<T>
    fun string(key: String): PubSubChannel<String>
    override suspend fun healthCheck(): HealthStatus {
        return try {
            get("health-check-test-key", Boolean.serializer()).emit(true)
            HealthStatus(HealthStatus.Level.OK)
        } catch (e: Exception) {
            HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
        }
    }
}

interface PubSubChannel<T> : Flow<T>, FlowCollector<T> {}

