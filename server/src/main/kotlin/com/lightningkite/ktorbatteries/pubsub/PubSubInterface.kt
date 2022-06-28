package com.lightningkite.ktorbatteries.pubsub

import com.lightningkite.ktorbatteries.cache.set
import com.lightningkite.ktorbatteries.serialization.Serialization
import com.lightningkite.ktorbatteries.serverhealth.HealthCheckable
import com.lightningkite.ktorbatteries.serverhealth.HealthStatus
import kotlinx.coroutines.flow.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

interface PubSubInterface: HealthCheckable {
    fun <T> get(key: String, serializer: KSerializer<T>): PubSubChannel<T>
    override suspend fun healthCheck(): HealthStatus {
        return try {
            get<Boolean>("health-check-test-key").emit(true)
            HealthStatus(HealthStatus.Level.OK)
        } catch(e: Exception) {
            HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
        }
    }

    override val healthCheckName: String
        get() = "PubSub"
}
inline operator fun <reified T: Any> PubSubInterface.get(key: String): PubSubChannel<T> {
    return get(key, Serialization.json.serializersModule.serializer<T>())
}

interface PubSubChannel<T>: Flow<T>, FlowCollector<T> {}

