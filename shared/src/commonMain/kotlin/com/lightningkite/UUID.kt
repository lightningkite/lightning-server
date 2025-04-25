@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
@file:OptIn(ExperimentalUuidApi::class)

package com.lightningkite

import com.lightningkite.serialization.UUIDSerializer
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalSerializationApi::class)
object DeferToContextualUuidSerializer: KSerializer<UUID> by ContextualSerializer<UUID>(UUID::class, UUIDSerializer, arrayOf())

typealias UUID = kotlin.uuid.Uuid
@Deprecated("Use UUID.v4() instead", ReplaceWith("UUID.v4()", "com.lightningkite.UUID")) fun uuid(): UUID = UUID.random()
@Deprecated("Use UUID.parse(string) instead", ReplaceWith("UUID.parse(string)", "com.lightningkite.UUID")) fun uuid(string: String): UUID = UUID.parse(string)
@Deprecated("Use UUID.parse(string) instead", ReplaceWith("UUID.parse(string)", "com.lightningkite.UUID")) fun Uuid.Companion.fromString(string: String): UUID = UUID.parse(string)


