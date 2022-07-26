package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.Aggregate
import com.lightningkite.lightningdb.Condition
import com.lightningkite.lightningdb.FieldCollection
import com.lightningkite.lightningdb.SortPart
import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.future.await
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.*
import java.util.Base64
import kotlin.reflect.KProperty1

class DynamoDbCollection<T: Any>(val table: DynamoDbAsyncClient, val serializer: KSerializer<T>, val tableName: String): FieldCollection<T> {

    override suspend fun find(
        condition: Condition<T>,
        orderBy: List<SortPart<T>>,
        skip: Int,
        limit: Int,
        maxQueryMs: Long
    ): Flow<T> {
        table.queryPaginator {
            it.
        }
        table.query {
            it.tableName(tableName)
            it.limit(limit)
        }
    }

    override suspend fun insert(models: List<T>): List<T> {
        table.batchWriteItem {
            it.requestItems(mapOf("inserts" to models.map { WriteRequest.builder().putRequest(
                PutRequest.builder().item(Serialization.json.encodeToDynamoDB(serializer, it)).build()
            ).build()}))
        }.await()
        return models
    }

}

fun <T> Json.encodeToDynamoDB(serializer: KSerializer<T>, value: T): Map<String, AttributeValue?> {
    val jsonElement = encodeToJsonElement(serializer, value)
    return when(jsonElement) {
        is JsonObject -> jsonElement.mapValues { it.value.toDynamoDb() }
        else -> mapOf("value" to jsonElement.toDynamoDb())
    }
}

private fun JsonElement.toDynamoDb(): AttributeValue {
    return when(this) {
        JsonNull -> AttributeValue.fromNul(true)
        is JsonPrimitive -> if(isString) AttributeValue.fromS(this.content)
        else AttributeValue.fromN(this.content)
        is JsonArray -> AttributeValue.fromL(this.map { it.toDynamoDb() })
        is JsonObject -> AttributeValue.fromM(this.mapValues { it.value.toDynamoDb() })
    }
}

private fun AttributeValue.toJson(): JsonElement {
    return when(this.type()) {
        null -> JsonNull
        AttributeValue.Type.S -> JsonPrimitive(this.s())
        AttributeValue.Type.N -> JsonPrimitive(this.n().toDoubleOrNull())
        AttributeValue.Type.B -> JsonPrimitive(Base64.getEncoder().encodeToString(this.b().asByteArray()))
        AttributeValue.Type.SS -> JsonArray(this.ss().map { JsonPrimitive(it) })
        AttributeValue.Type.NS -> JsonArray(this.ns().map { JsonPrimitive(it.toDoubleOrNull()) })
        AttributeValue.Type.BS -> JsonArray(this.bs().map { JsonPrimitive(Base64.getEncoder().encodeToString(it.asByteArray())) })
        AttributeValue.Type.M -> JsonObject(this.m().mapValues { it.value.toJson() })
        AttributeValue.Type.L -> JsonArray(this.l().map { it.toJson() })
        AttributeValue.Type.BOOL -> JsonPrimitive(this.bool())
        AttributeValue.Type.NUL -> JsonNull
        AttributeValue.Type.UNKNOWN_TO_SDK_VERSION -> TODO()
    }
}