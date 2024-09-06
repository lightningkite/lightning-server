@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package com.lightningkite

import com.lightningkite.serialization.UUIDSerializer
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@OptIn(ExperimentalSerializationApi::class)
object DeferToContextualUuidSerializer: KSerializer<UUID> by ContextualSerializer<UUID>(UUID::class, UUIDSerializer, arrayOf())

@Serializable(DeferToContextualUuidSerializer::class)
expect class UUID: Comparable<UUID> {
    override fun compareTo(other: UUID): Int
    companion object {
        fun random(): UUID
        fun parse(uuidString: String): UUID
    }
}
@Deprecated("Use UUID.v4() instead", ReplaceWith("UUID.v4()", "com.lightningkite.UUID")) fun uuid(): UUID = UUID.random()
@Deprecated("Use UUID.parse(string) instead", ReplaceWith("UUID.parse(string)", "com.lightningkite.UUID")) fun uuid(string: String): UUID = UUID.parse(string)


