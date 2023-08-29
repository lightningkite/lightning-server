package com.lightningkite.lightningdb

import com.lightningkite.lightningserver.serverhealth.HealthStatus
import kotlin.reflect.KType

class MetricsWrappedDatabase(val wraps: Database, val metricsKeyName: String): Database by wraps {
    override fun <T : Any> collection(type: KType, name: String): FieldCollection<T> = wraps.collection<T>(type, name).metrics(metricsKeyName)
}