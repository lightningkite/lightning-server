@file:OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)

package com.lightningkite.lightningdb

import com.lightningkite.lightningserver.serverhealth.HealthCheckable
import com.lightningkite.lightningserver.serverhealth.HealthStatus
import kotlinx.serialization.Serializable
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import com.lightningkite.lightningdb.*
import kotlin.reflect.*
import kotlinx.serialization.*
import kotlinx.serialization.builtins.*
import kotlinx.serialization.internal.GeneratedSerializer
import java.time.*
import java.util.*

fun prepareHealthCheckTestModelFields() {
    HealthCheckTestModel::_id.setCopyImplementation { original, value -> original.copy(_id = value) }
}
val <K> PropChain<K, HealthCheckTestModel>._id: PropChain<K, String> get() = this[HealthCheckTestModel::_id]
