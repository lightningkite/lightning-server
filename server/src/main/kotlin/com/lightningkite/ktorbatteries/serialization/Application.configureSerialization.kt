package com.lightningkite.ktorbatteries.serialization

import com.lightningkite.ktorbatteries.files.MultipartJsonConverter
import io.ktor.server.application.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.*
import io.ktor.serialization.kotlinx.cbor.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.modules.SerializersModule

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(Serialization.json)
        cbor(Serialization.cbor)
        serialization(ContentType.Text.Xml, Serialization.xml)
        serialization(ContentType.Text.CSV, Serialization.csv)
        serialization(ContentType.Application.Xml, Serialization.xml)
        serialization(ContentType.Application.OctetStream, Serialization.javaData)
        serialization(ContentType.Application.Bson, object : BinaryFormat {
            override val serializersModule: SerializersModule get() = Serialization.module
            override fun <T> decodeFromByteArray(deserializer: DeserializationStrategy<T>, bytes: ByteArray): T =
                Serialization.bson.load(deserializer, bytes)
            override fun <T> encodeToByteArray(serializer: SerializationStrategy<T>, value: T): ByteArray =
                Serialization.bson.dump(serializer, value)
        })
        register(ContentType.MultiPart.FormData, MultipartJsonConverter(Serialization.json))
    }
}

val ContentType.Application.Bson get() = ContentType("application", "bson")