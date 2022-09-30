package com.lightningkite.lightningdb

import com.lightningkite.lightningserver.serverhealth.HealthCheckable
import com.lightningkite.lightningserver.serverhealth.HealthStatus
import kotlinx.serialization.Serializable
import kotlin.reflect.KType
import kotlin.reflect.typeOf

interface Database: HealthCheckable {
    fun <T: Any> collection(type: KType, name: String): FieldCollection<T>
    override suspend fun healthCheck(): HealthStatus {
        try {
            val c = collection<HealthCheckTestModel>()
            val id = "HealthCheck"
            c.upsertOneById(id, HealthCheckTestModel(id))
            assert(c.get(id) != null)
            return HealthStatus(HealthStatus.Level.OK)
        } catch(e: Exception) {
            return HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
        }
    }
}

@DatabaseModel
@Serializable
data class HealthCheckTestModel(override val _id: String): HasId<String>

inline fun <reified T: Any> Database.collection(name: String = T::class.simpleName!!): FieldCollection<T> {
    return collection(typeOf<T>(), name)
}