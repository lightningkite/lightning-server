package com.lightningkite.lightningdb

import com.lightningkite.lightningserver.metrics.Metricable
import com.lightningkite.lightningserver.metrics.Metrics
import com.lightningkite.lightningserver.serverhealth.HealthCheckable
import com.lightningkite.lightningserver.serverhealth.HealthStatus
import com.lightningkite.lightningserver.services.Disconnectable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule

/**
 * An abstracted model for communicating with a Database.
 * Every implementation will handle how to return a FieldCollection to perform actions on a collection/table in the underlying database system.
 */
interface Database : HealthCheckable, Disconnectable {

    /**
     * Returns a FieldCollection of type T that will access and manipulate data from a collection/table in the underlying database system.
     */
    fun <T : Any> collection(module: SerializersModule, serializer: KSerializer<T>, name: String): FieldCollection<T>

    /**
     * Will attempt inserting data into the database to confirm that the connection is alive and available.
     */
    override suspend fun healthCheck(): HealthStatus {
        prepareModels()
        try {
            val c = collection(SerializersModule {}, HealthCheckTestModel.serializer(), "HealthCheckTestModel")
            val id = "HealthCheck"
            c.upsertOneById(id, HealthCheckTestModel(id))
            assert(c.get(id) != null)
            return HealthStatus(HealthStatus.Level.OK)
        } catch (e: Exception) {
            return HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message ?: e::class.qualifiedName)
        }
    }
}

@GenerateDataClassPaths
@Serializable
data class HealthCheckTestModel(override val _id: String) : HasId<String>


