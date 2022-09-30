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

fun prepareClassUsedForEmbeddingFields() {
    ClassUsedForEmbedding::value1.setCopyImplementation { original, value -> original.copy(value1 = value) }
    ClassUsedForEmbedding::value2.setCopyImplementation { original, value -> original.copy(value2 = value) }
}
val <K> PropChain<K, ClassUsedForEmbedding>.value1: PropChain<K, String> get() = this[ClassUsedForEmbedding::value1]
val <K> PropChain<K, ClassUsedForEmbedding>.value2: PropChain<K, Int> get() = this[ClassUsedForEmbedding::value2]
