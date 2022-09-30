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

fun prepareEmbeddedMapFields() {
    EmbeddedMap::_id.setCopyImplementation { original, value -> original.copy(_id = value) }
    EmbeddedMap::map.setCopyImplementation { original, value -> original.copy(map = value) }
}
val <K> PropChain<K, EmbeddedMap>._id: PropChain<K, UUID> get() = this[EmbeddedMap::_id]
val <K> PropChain<K, EmbeddedMap>.map: PropChain<K, Map<String, RecursiveEmbed>> get() = this[EmbeddedMap::map]
