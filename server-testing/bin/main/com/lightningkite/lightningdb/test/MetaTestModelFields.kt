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

fun prepareMetaTestModelFields() {
    MetaTestModel::_id.setCopyImplementation { original, value -> original.copy(_id = value) }
    MetaTestModel::condition.setCopyImplementation { original, value -> original.copy(condition = value) }
    MetaTestModel::modification.setCopyImplementation { original, value -> original.copy(modification = value) }
}
val <K> PropChain<K, MetaTestModel>._id: PropChain<K, UUID> get() = this[MetaTestModel::_id]
val <K> PropChain<K, MetaTestModel>.condition: PropChain<K, Condition<LargeTestModel>> get() = this[MetaTestModel::condition]
val <K> PropChain<K, MetaTestModel>.modification: PropChain<K, Modification<LargeTestModel>> get() = this[MetaTestModel::modification]
