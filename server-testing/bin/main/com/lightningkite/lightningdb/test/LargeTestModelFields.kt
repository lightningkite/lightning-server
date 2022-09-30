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

fun prepareLargeTestModelFields() {
    LargeTestModel::_id.setCopyImplementation { original, value -> original.copy(_id = value) }
    LargeTestModel::boolean.setCopyImplementation { original, value -> original.copy(boolean = value) }
    LargeTestModel::byte.setCopyImplementation { original, value -> original.copy(byte = value) }
    LargeTestModel::short.setCopyImplementation { original, value -> original.copy(short = value) }
    LargeTestModel::int.setCopyImplementation { original, value -> original.copy(int = value) }
    LargeTestModel::long.setCopyImplementation { original, value -> original.copy(long = value) }
    LargeTestModel::float.setCopyImplementation { original, value -> original.copy(float = value) }
    LargeTestModel::double.setCopyImplementation { original, value -> original.copy(double = value) }
    LargeTestModel::char.setCopyImplementation { original, value -> original.copy(char = value) }
    LargeTestModel::string.setCopyImplementation { original, value -> original.copy(string = value) }
    LargeTestModel::instant.setCopyImplementation { original, value -> original.copy(instant = value) }
    LargeTestModel::list.setCopyImplementation { original, value -> original.copy(list = value) }
    LargeTestModel::listEmbedded.setCopyImplementation { original, value -> original.copy(listEmbedded = value) }
    LargeTestModel::set.setCopyImplementation { original, value -> original.copy(set = value) }
    LargeTestModel::setEmbedded.setCopyImplementation { original, value -> original.copy(setEmbedded = value) }
    LargeTestModel::map.setCopyImplementation { original, value -> original.copy(map = value) }
    LargeTestModel::embedded.setCopyImplementation { original, value -> original.copy(embedded = value) }
    LargeTestModel::booleanNullable.setCopyImplementation { original, value -> original.copy(booleanNullable = value) }
    LargeTestModel::byteNullable.setCopyImplementation { original, value -> original.copy(byteNullable = value) }
    LargeTestModel::shortNullable.setCopyImplementation { original, value -> original.copy(shortNullable = value) }
    LargeTestModel::intNullable.setCopyImplementation { original, value -> original.copy(intNullable = value) }
    LargeTestModel::longNullable.setCopyImplementation { original, value -> original.copy(longNullable = value) }
    LargeTestModel::floatNullable.setCopyImplementation { original, value -> original.copy(floatNullable = value) }
    LargeTestModel::doubleNullable.setCopyImplementation { original, value -> original.copy(doubleNullable = value) }
    LargeTestModel::charNullable.setCopyImplementation { original, value -> original.copy(charNullable = value) }
    LargeTestModel::stringNullable.setCopyImplementation { original, value -> original.copy(stringNullable = value) }
    LargeTestModel::instantNullable.setCopyImplementation { original, value -> original.copy(instantNullable = value) }
    LargeTestModel::listNullable.setCopyImplementation { original, value -> original.copy(listNullable = value) }
    LargeTestModel::mapNullable.setCopyImplementation { original, value -> original.copy(mapNullable = value) }
    LargeTestModel::embeddedNullable.setCopyImplementation { original, value -> original.copy(embeddedNullable = value) }
}
val <K> PropChain<K, LargeTestModel>._id: PropChain<K, UUID> get() = this[LargeTestModel::_id]
val <K> PropChain<K, LargeTestModel>.boolean: PropChain<K, Boolean> get() = this[LargeTestModel::boolean]
val <K> PropChain<K, LargeTestModel>.byte: PropChain<K, Byte> get() = this[LargeTestModel::byte]
val <K> PropChain<K, LargeTestModel>.short: PropChain<K, Short> get() = this[LargeTestModel::short]
val <K> PropChain<K, LargeTestModel>.int: PropChain<K, Int> get() = this[LargeTestModel::int]
val <K> PropChain<K, LargeTestModel>.long: PropChain<K, Long> get() = this[LargeTestModel::long]
val <K> PropChain<K, LargeTestModel>.float: PropChain<K, Float> get() = this[LargeTestModel::float]
val <K> PropChain<K, LargeTestModel>.double: PropChain<K, Double> get() = this[LargeTestModel::double]
val <K> PropChain<K, LargeTestModel>.char: PropChain<K, Char> get() = this[LargeTestModel::char]
val <K> PropChain<K, LargeTestModel>.string: PropChain<K, String> get() = this[LargeTestModel::string]
val <K> PropChain<K, LargeTestModel>.instant: PropChain<K, Instant> get() = this[LargeTestModel::instant]
val <K> PropChain<K, LargeTestModel>.list: PropChain<K, List<Int>> get() = this[LargeTestModel::list]
val <K> PropChain<K, LargeTestModel>.listEmbedded: PropChain<K, List<ClassUsedForEmbedding>> get() = this[LargeTestModel::listEmbedded]
val <K> PropChain<K, LargeTestModel>.set: PropChain<K, Set<Int>> get() = this[LargeTestModel::set]
val <K> PropChain<K, LargeTestModel>.setEmbedded: PropChain<K, Set<ClassUsedForEmbedding>> get() = this[LargeTestModel::setEmbedded]
val <K> PropChain<K, LargeTestModel>.map: PropChain<K, Map<String, Int>> get() = this[LargeTestModel::map]
val <K> PropChain<K, LargeTestModel>.embedded: PropChain<K, ClassUsedForEmbedding> get() = this[LargeTestModel::embedded]
val <K> PropChain<K, LargeTestModel>.booleanNullable: PropChain<K, Boolean?> get() = this[LargeTestModel::booleanNullable]
val <K> PropChain<K, LargeTestModel>.byteNullable: PropChain<K, Byte?> get() = this[LargeTestModel::byteNullable]
val <K> PropChain<K, LargeTestModel>.shortNullable: PropChain<K, Short?> get() = this[LargeTestModel::shortNullable]
val <K> PropChain<K, LargeTestModel>.intNullable: PropChain<K, Int?> get() = this[LargeTestModel::intNullable]
val <K> PropChain<K, LargeTestModel>.longNullable: PropChain<K, Long?> get() = this[LargeTestModel::longNullable]
val <K> PropChain<K, LargeTestModel>.floatNullable: PropChain<K, Float?> get() = this[LargeTestModel::floatNullable]
val <K> PropChain<K, LargeTestModel>.doubleNullable: PropChain<K, Double?> get() = this[LargeTestModel::doubleNullable]
val <K> PropChain<K, LargeTestModel>.charNullable: PropChain<K, Char?> get() = this[LargeTestModel::charNullable]
val <K> PropChain<K, LargeTestModel>.stringNullable: PropChain<K, String?> get() = this[LargeTestModel::stringNullable]
val <K> PropChain<K, LargeTestModel>.instantNullable: PropChain<K, Instant?> get() = this[LargeTestModel::instantNullable]
val <K> PropChain<K, LargeTestModel>.listNullable: PropChain<K, List<Int>?> get() = this[LargeTestModel::listNullable]
val <K> PropChain<K, LargeTestModel>.mapNullable: PropChain<K, Map<String, Int>?> get() = this[LargeTestModel::mapNullable]
val <K> PropChain<K, LargeTestModel>.embeddedNullable: PropChain<K, ClassUsedForEmbedding?> get() = this[LargeTestModel::embeddedNullable]
