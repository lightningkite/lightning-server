@file:UseContextualSerialization(UUID::class, Instant::class)
@file:OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)

package com.lightningkite.lightningdb.test

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.DatabaseModel
import com.lightningkite.lightningdb.UUIDFor
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import java.time.Instant
import java.util.*
import kotlin.reflect.*
import kotlinx.serialization.*
import kotlinx.serialization.builtins.*
import kotlinx.serialization.internal.GeneratedSerializer
import java.time.*

fun prepareEmployeeFields() {
    Employee::_id.setCopyImplementation { original, value -> original.copy(_id = value) }
    Employee::dictionary.setCopyImplementation { original, value -> original.copy(dictionary = value) }
}
val <K> PropChain<K, Employee>._id: PropChain<K, @Contextual UUID> get() = this[Employee::_id]
val <K> PropChain<K, Employee>.dictionary: PropChain<K, Map<String, Int>> get() = this[Employee::dictionary]
