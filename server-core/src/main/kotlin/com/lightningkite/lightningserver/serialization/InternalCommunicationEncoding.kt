package com.lightningkite.lightningserver.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream


@Serializable
enum class InternalCommunicationEncoding(val byteOriented: Boolean) {
    Json(false) {
        override fun <T> encodeString(serializer: KSerializer<T>, value: T): String {
            return Serialization.Internal.json.encodeToString(serializer, value)
        }

        override fun <T> decodeString(serializer: KSerializer<T>, string: String): T {
            return Serialization.Internal.json.decodeFromString(
                serializer,
                string
            )
        }

        override fun <T> encodeBytes(serializer: KSerializer<T>, value: T): ByteArray = Serialization.Internal.json.encodeToString(serializer, value).toByteArray(Charsets.UTF_8)
        override fun <T> decodeBytes(serializer: KSerializer<T>, string: ByteArray): T = Serialization.Internal.json.decodeFromString(serializer, string.toString(Charsets.UTF_8))
    },
    JsonGzip(true) {
        override fun <T> encodeString(serializer: KSerializer<T>, value: T): String {
            val payload = Serialization.Internal.json.encodeToString(serializer, value)
            val zipped = ByteArrayOutputStream().use {
                GZIPOutputStream(it).use {
                    it.write(payload.toByteArray(Charsets.UTF_8))
                }
                it.flush()
                it.toByteArray()
            }
            return Base64.getEncoder().encodeToString(zipped)
        }
        override fun <T> decodeString(serializer: KSerializer<T>, string: String): T {
            val data = ByteArrayInputStream(Base64.getDecoder().decode(string)).use {
                GZIPInputStream(it).readBytes()
            }.toString(Charsets.UTF_8)
            return Serialization.Internal.json.decodeFromString(serializer, data)
        }
        override fun <T> encodeBytes(serializer: KSerializer<T>, value: T): ByteArray {
            val payload = Serialization.Internal.json.encodeToString(serializer, value)
            val zipped = ByteArrayOutputStream().use {
                GZIPOutputStream(it).use {
                    it.write(payload.toByteArray(Charsets.UTF_8))
                }
                it.flush()
                it.toByteArray()
            }
            return zipped
        }
        override fun <T> decodeBytes(serializer: KSerializer<T>, string: ByteArray): T {
            val data = ByteArrayInputStream(string).use {
                GZIPInputStream(it).readBytes()
            }.toString(Charsets.UTF_8)
            return Serialization.Internal.json.decodeFromString(serializer, data)
        }
    },
    JavaData(true) {
        override fun <T> encodeString(serializer: KSerializer<T>, value: T): String = Serialization.Internal.javaData.encodeToBase64(serializer, value)
        override fun <T> decodeString(serializer: KSerializer<T>, string: String): T = Serialization.Internal.javaData.decodeFromBase64(serializer, string)
        override fun <T> encodeBytes(serializer: KSerializer<T>, value: T): ByteArray = Serialization.Internal.javaData.encodeToByteArray(serializer, value)
        override fun <T> decodeBytes(serializer: KSerializer<T>, string: ByteArray): T = Serialization.Internal.javaData.decodeFromByteArray(serializer, string)
    }
    ;

    abstract fun <T> encodeString(serializer: KSerializer<T>, value: T): String
    abstract fun <T> decodeString(serializer: KSerializer<T>, string: String): T
    abstract fun <T> encodeBytes(serializer: KSerializer<T>, value: T): ByteArray
    abstract fun <T> decodeBytes(serializer: KSerializer<T>, bytes: ByteArray): T
    fun <T> encodePreferred(serializer: KSerializer<T>, value: T): Any = if(byteOriented) encodeBytes(serializer, value) else encodeString(serializer, value)
    fun <T> decodePreferred(serializer: KSerializer<T>, bytes: Any): T = if(byteOriented) decodeBytes(serializer, bytes as ByteArray) else decodeString(serializer, bytes as String)
}