package com.lightningkite.lightningserver.db

import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.reactive.asFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import software.amazon.awssdk.core.async.SdkPublisher
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.paginators.ScanPublisher
import java.util.*


fun <T> SdkPublisher<Map<String, AttributeValue>>.parse(serializer: KSerializer<T>): Flow<T> {
    return asFlow().map { serializer.fromDynamoMap(it) }
}

fun <T> KSerializer<T>.toDynamo(value: T): AttributeValue {
    val jsonElement = Serialization.json.encodeToJsonElement(this, value)
    return when (jsonElement) {
        is JsonObject -> AttributeValue.fromM(jsonElement.mapValues { it.value.toDynamoDb() })
        else -> jsonElement.toDynamoDb()
    }
}

fun <T> KSerializer<T>.fromDynamo(value: AttributeValue): T {
    val element = value.toJson()
    return Serialization.json.decodeFromJsonElement(this, element)
}

fun <T> KSerializer<T>.toDynamoMap(value: T): Map<String, AttributeValue> = toDynamo(value).m()
fun <T> KSerializer<T>.fromDynamoMap(value: Map<String, AttributeValue>): T = fromDynamo(AttributeValue.fromM(value))

private fun JsonElement.toDynamoDb(): AttributeValue {
    return when (this) {
        JsonNull -> AttributeValue.fromNul(true)
        is JsonPrimitive -> if (isString) AttributeValue.fromS(this.content)
        else AttributeValue.fromN(this.content)

        is JsonArray -> AttributeValue.fromL(this.map { it.toDynamoDb() })
        is JsonObject -> AttributeValue.fromM(this.mapValues { it.value.toDynamoDb() })
    }
}

private fun AttributeValue.toJson(): JsonElement {
    return when (this.type()) {
        null -> JsonNull
        AttributeValue.Type.S -> JsonPrimitive(this.s())
        AttributeValue.Type.N -> JsonPrimitive(this.n().let { it.toLongOrNull() ?: it.toDoubleOrNull() })
        AttributeValue.Type.B -> JsonPrimitive(Base64.getEncoder().encodeToString(this.b().asByteArray()))
        AttributeValue.Type.SS -> JsonArray(this.ss().map { JsonPrimitive(it) })
        AttributeValue.Type.NS -> JsonArray(this.ns().map { JsonPrimitive(it.toLongOrNull() ?: it.toDoubleOrNull()) })
        AttributeValue.Type.BS -> JsonArray(
            this.bs().map { JsonPrimitive(Base64.getEncoder().encodeToString(it.asByteArray())) })

        AttributeValue.Type.M -> JsonObject(this.m().mapValues { it.value.toJson() })
        AttributeValue.Type.L -> JsonArray(this.l().map { it.toJson() })
        AttributeValue.Type.BOOL -> JsonPrimitive(this.bool())
        AttributeValue.Type.NUL -> JsonNull
        AttributeValue.Type.UNKNOWN_TO_SDK_VERSION -> TODO()
    }
}