package com.lightningkite.lightningdb

import com.lightningkite.lightningserver.metrics.Metricable
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serverhealth.HealthCheckable
import com.lightningkite.lightningserver.serverhealth.HealthStatus
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlin.reflect.KType

/**
 * An abstracted model for communicating with a Database.
 * Every implementation will handle how to return a FieldCollection to perform actions on a collection/table in the underlying database system.
 */
interface Database : HealthCheckable, Metricable<Database> {

    /**
     * Returns a FieldCollection of type T that will access and manipulate data from a collection/table in the underlying database system.
     */
    fun <T : Any> collection(serializer: KSerializer<T>, name: String): FieldCollection<T>
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> collection(type: KType, name: String): FieldCollection<T> = collection(Serialization.Internal.module.serializer(type) as KSerializer<T>, name)

    /**
     * Will attempt inserting data into the database to confirm that the connection is alive and available.
     */
    override suspend fun healthCheck(): HealthStatus {
        prepareModels()
        try {
            val c = collection<HealthCheckTestModel>()
            val id = "HealthCheck"
            c.upsertOneById(id, HealthCheckTestModel(id))
            assert(c.get(id) != null)
            return HealthStatus(HealthStatus.Level.OK)
        } catch (e: Exception) {
            return HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message ?: e::class.qualifiedName)
        }
    }

    override fun withMetrics(metricsKeyName: String): Database = MetricsWrappedDatabase(this, metricsKeyName)
}

@GenerateDataClassPaths
@Serializable
data class HealthCheckTestModel(override val _id: String) : HasId<String>

/**
 * A Helper function for getting a collection from a database using generics.
 * This can make collection calls much cleaner and less wordy when the types can be inferred.
 */
inline fun <reified T : Any> Database.collection(name: String = T::class.simpleName!!): FieldCollection<T> {
    return collection(Serialization.Internal.module.serializer<T>(), name)
}
