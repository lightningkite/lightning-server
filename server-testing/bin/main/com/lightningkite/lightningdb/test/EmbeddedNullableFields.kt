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

fun prepareEmbeddedNullableFields() {
    EmbeddedNullable::_id.setCopyImplementation { original, value -> original.copy(_id = value) }
    EmbeddedNullable::name.setCopyImplementation { original, value -> original.copy(name = value) }
    EmbeddedNullable::embed1.setCopyImplementation { original, value -> original.copy(embed1 = value) }
}
val <K> PropChain<K, EmbeddedNullable>._id: PropChain<K, UUID> get() = this[EmbeddedNullable::_id]
val <K> PropChain<K, EmbeddedNullable>.name: PropChain<K, String> get() = this[EmbeddedNullable::name]
val <K> PropChain<K, EmbeddedNullable>.embed1: PropChain<K, ClassUsedForEmbedding?> get() = this[EmbeddedNullable::embed1]
