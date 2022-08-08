package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningdb.Condition
import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.future.await
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.*
import java.util.Base64
import kotlin.reflect.KProperty
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
            it.tableName(tableName)
            it.limit(limit)
            it.filterExpression()
            it.expressionAttributeValues()
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

fun <T> Json.encodeToDynamoDB(serializer: KSerializer<T>, value: T): AttributeValue {
    val jsonElement = encodeToJsonElement(serializer, value)
    return when(jsonElement) {
        is JsonObject -> AttributeValue.fromM(jsonElement.mapValues { it.value.toDynamoDb() })
        else -> jsonElement.toDynamoDb()
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

class DynamoQueryBuilder {
    val query = StringBuilder()
    val valueMap = HashMap<String, AttributeValue>()
    var counter = 0
    var field: String = ""
    var kotlin: (Any?)->Boolean = { true }
    fun <T> value(value: T, serializer: KSerializer<T>) {
        val name = "i${counter++}"
        valueMap[name] = Serialization.json.encodeToDynamoDB(serializer, value)
        query.append(name)
    }
    fun <T> handle(condition: Condition<T>, serializer: KSerializer<T>) {
        when(condition) {
            is Condition.Never -> query.append("false")
            is Condition.Always -> query.append("true")
            is Condition.And -> {
                query.append('(')
                var first = true
                condition.conditions.forEach {
                    if(first) first = false
                    else query.append(" and ")
                    handle(it, serializer)
                }
                query.append(')')
            }
            is Condition.Or -> {
                query.append('(')
                var first = true
                condition.conditions.forEach {
                    if(first) first = false
                    else query.append(" or ")
                    handle(it, serializer)
                }
                query.append(')')
            }
            is Condition.Not -> {
                query.append("NOT ")
                handle(condition.condition, serializer)
            }
            is Condition.Equal -> {
                query.append(field)
                query.append(" = ")
                value(condition.value, serializer)
            }
            is Condition.NotEqual -> {
                query.append(field)
                query.append(" <> ")
                value(condition.value, serializer)
            }
            is Condition.Inside -> {
                query.append(field)
                query.append(" IN ")
                value(condition.values, ListSerializer(serializer))
            }
            is Condition.NotInside -> {
                handle(Condition.Not(Condition.Inside(condition.values)), serializer)
            }
            is Condition.GreaterThan -> {
                query.append(field)
                query.append(" > ")
                value(condition.value, serializer)
            }
            is Condition.LessThan -> {
                query.append(field)
                query.append(" < ")
                value(condition.value, serializer)
            }
            is Condition.GreaterThanOrEqual -> {
                query.append(field)
                query.append(" >= ")
                value(condition.value, serializer)
            }
            is Condition.LessThanOrEqual -> {
                query.append(field)
                query.append(" <= ")
                value(condition.value, serializer)
            }
            is Condition.StringContains -> {
                query.append("contains(")
                query.append(field)
                query.append(", ")
                value(condition.value, String.serializer())
                query.append(")")
            }
            is Condition.FullTextSearch -> throw NotImplementedError()
            is Condition.RegexMatches -> throw NotImplementedError()
            is Condition.IntBitsClear -> throw NotImplementedError()
            is Condition.IntBitsSet -> throw NotImplementedError()
            is Condition.IntBitsAnyClear -> throw NotImplementedError()
            is Condition.IntBitsAnySet -> throw NotImplementedError()
            is Condition.AllElements<*> -> throw NotImplementedError()
            is Condition.AnyElements<*> -> throw NotImplementedError()
            is Condition.SizesEquals<*> -> {
                query.append("size(")
                query.append(field)
                query.append(')')
            }
            is Condition.Exists<*> -> {
                query.append("attribute_exists(")
                query.append(field)
                query.append('.')
                query.append(condition.key)
                query.append(')')
            }
            is Condition.OnKey<*> -> {
                val oldField = field
                if(field.isEmpty()) field = condition.key
                else field += "." + condition.key
                @Suppress("UNCHECKED_CAST")
                handle(condition.condition as Condition<Any?>, serializer.mapValueElement()!! as KSerializer<Any?>)
                field = oldField
            }
            is Condition.OnField<*, *> -> {
                val oldField = field
                if(field.isEmpty()) field = condition.key.name
                else field += "." + condition.key.name
                @Suppress("UNCHECKED_CAST")
                handle(condition.condition as Condition<Any?>, (serializer as KSerializer<Any?>).fieldSerializer(condition.key as KProperty1<Any?, Any?>) as KSerializer<Any?>)
                field = oldField
            }
            is Condition.IfNotNull<*> -> {
                @Suppress("UNCHECKED_CAST")
                handle(condition.condition as Condition<Any>, serializer.nullElement()!! as KSerializer<Any>)
            }
        }
    }
}
