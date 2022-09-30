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

fun prepareEmbeddedObjectTestFields() {
    EmbeddedObjectTest::_id.setCopyImplementation { original, value -> original.copy(_id = value) }
    EmbeddedObjectTest::name.setCopyImplementation { original, value -> original.copy(name = value) }
    EmbeddedObjectTest::embed1.setCopyImplementation { original, value -> original.copy(embed1 = value) }
    EmbeddedObjectTest::embed2.setCopyImplementation { original, value -> original.copy(embed2 = value) }
}
val <K> PropChain<K, EmbeddedObjectTest>._id: PropChain<K, UUID> get() = this[EmbeddedObjectTest::_id]
val <K> PropChain<K, EmbeddedObjectTest>.name: PropChain<K, String> get() = this[EmbeddedObjectTest::name]
val <K> PropChain<K, EmbeddedObjectTest>.embed1: PropChain<K, ClassUsedForEmbedding> get() = this[EmbeddedObjectTest::embed1]
val <K> PropChain<K, EmbeddedObjectTest>.embed2: PropChain<K, ClassUsedForEmbedding> get() = this[EmbeddedObjectTest::embed2]
