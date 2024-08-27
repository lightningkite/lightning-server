package com.lightningkite.lightningdb

import kotlinx.serialization.KSerializer
import com.lightningkite.serialization.default as defaultOtherPackage

//@Deprecated("Moved packages", ReplaceWith("ClientModule", "com.lightningkite.serialization.ClientModule")) val ClientModule get() = com.lightningkite.serialization.ClientModule
//@Deprecated("Moved packages", ReplaceWith("ServerFile", "com.lightningkite.lightningserver.files.ServerFile")) typealias ServerFile = com.lightningkite.lightningserver.files.ServerFile
//@Deprecated("Moved packages", ReplaceWith("ServerFileSerialization", "com.lightningkite.lightningserver.files.ServerFileSerializer")) typealias ServerFileSerialization = com.lightningkite.lightningserver.files.ServerFileSerializer
//@Deprecated("Moved packages", ReplaceWith("MultiplexMessage", "com.lightningkite.lightningserver.websocket.MultiplexMessage")) typealias MultiplexMessage = com.lightningkite.lightningserver.websocket.MultiplexMessage
//@Deprecated("Moved packages", ReplaceWith("UUIDSerializer", "com.lightningkite.serialization.UUIDSerializer")) typealias UUIDSerializer = com.lightningkite.serialization.UUIDSerializer
//@Deprecated("Moved packages", ReplaceWith("DurationMsSerializer", "com.lightningkite.serialization.DurationMsSerializer")) typealias DurationMsSerializer = com.lightningkite.serialization.DurationMsSerializer
//@Deprecated("Moved packages", ReplaceWith("DurationSerializer", "com.lightningkite.serialization.DurationSerializer")) typealias DurationSerializer = com.lightningkite.serialization.DurationSerializer
//@Deprecated("Moved packages", ReplaceWith("InstantIso8601Serializer", "com.lightningkite.serialization.InstantIso8601Serializer")) typealias InstantIso8601Serializer = com.lightningkite.serialization.InstantIso8601Serializer
//@Deprecated("Moved packages", ReplaceWith("LocalDateIso8601Serializer", "com.lightningkite.serialization.LocalDateIso8601Serializer")) typealias LocalDateIso8601Serializer = com.lightningkite.serialization.LocalDateIso8601Serializer
//@Deprecated("Moved packages", ReplaceWith("LocalTimeIso8601Serializer", "com.lightningkite.serialization.LocalTimeIso8601Serializer")) typealias LocalTimeIso8601Serializer = com.lightningkite.serialization.LocalTimeIso8601Serializer
//@Deprecated("Moved packages", ReplaceWith("LocalDateTimeIso8601Serializer", "com.lightningkite.serialization.LocalDateTimeIso8601Serializer")) typealias LocalDateTimeIso8601Serializer = com.lightningkite.serialization.LocalDateTimeIso8601Serializer
//@Deprecated("Moved packages", ReplaceWith("WrappingSerializer<T, V>", "com.lightningkite.serialization.WrappingSerializer")) typealias WrappingSerializer<T, V> = com.lightningkite.serialization.WrappingSerializer<T, V>
//@Deprecated("Moved packages", ReplaceWith("SerializableProperty<T, V>", "com.lightningkite.serialization.SerializableProperty")) typealias SerializableProperty<T, V> = com.lightningkite.serialization.SerializableProperty<T, V>
//@Deprecated("Moved packages", ReplaceWith("Partial<T>", "com.lightningkite.serialization.Partial")) typealias Partial<T> = com.lightningkite.serialization.Partial<T>
//@Deprecated("Moved packages", ReplaceWith("PartialBuilder<T>", "com.lightningkite.serialization.PartialBuilder")) typealias PartialBuilder<T> = com.lightningkite.serialization.PartialBuilder<T>
//@Deprecated("Moved packages", ReplaceWith("PartialSerializer<T>", "com.lightningkite.serialization.PartialSerializer")) typealias PartialSerializer<T> = com.lightningkite.serialization.PartialSerializer<T>
//@Deprecated("Moved packages", ReplaceWith("DataClassPathSerializer<T>", "com.lightningkite.serialization.DataClassPathSerializer")) typealias DataClassPathSerializer<T> = com.lightningkite.serialization.DataClassPathSerializer<T>
//@Deprecated("Moved packages", ReplaceWith("DataClassPathPartial<T>", "com.lightningkite.serialization.DataClassPathPartial")) typealias DataClassPathPartial<T> = com.lightningkite.serialization.DataClassPathPartial<T>
//@Deprecated("Moved packages", ReplaceWith("DataClassPath<T, V>", "com.lightningkite.serialization.DataClassPath")) typealias DataClassPath<T, V> = com.lightningkite.serialization.DataClassPath<T, V>
//@Deprecated("Moved packages", ReplaceWith("DataClassPathSelf<T>", "com.lightningkite.serialization.DataClassPathSelf")) typealias DataClassPathSelf<T> = com.lightningkite.serialization.DataClassPathSelf<T>
//@Deprecated("Moved packages", ReplaceWith("DataClassPathAccessT, M, V>", "com.lightningkite.serialization.DataClassPathAccess")) typealias DataClassPathAccess<T, M, V> = com.lightningkite.serialization.DataClassPathAccess<T, M, V>
//@Deprecated("Moved packages", ReplaceWith("DataClassPathNotNull<T, V>", "com.lightningkite.serialization.DataClassPathNotNull")) typealias DataClassPathNotNull<T, V> = com.lightningkite.serialization.DataClassPathNotNull<T, V>
//@Deprecated("Moved packages", ReplaceWith("DataClassPathList<T, V>", "com.lightningkite.serialization.DataClassPathList")) typealias DataClassPathList<T, V> = com.lightningkite.serialization.DataClassPathList<T, V>
//@Deprecated("Moved packages", ReplaceWith("DataClassPathSet<T, V>", "com.lightningkite.serialization.DataClassPathSet")) typealias DataClassPathSet<T, V> = com.lightningkite.serialization.DataClassPathSet<T, V>
//@Deprecated("Moved packages", ReplaceWith("default()", "com.lightningkite.serialization.default")) fun <T> KSerializer<T>.default(): T = defaultOtherPackage()