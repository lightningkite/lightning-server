package com.lightningkite.lightningdb

import com.lightningkite.lightningserver.serverhealth.HealthStatus
import kotlinx.serialization.KSerializer
import kotlin.reflect.KType

class MetricsWrappedDatabase(val wraps: Database, val metricsKeyName: String): Database by wraps {
    override fun <T : Any> collection(serializer: KSerializer<T>, name: String): FieldCollection<T> = wraps.collection<T>(serializer, name).metrics(metricsKeyName)
}