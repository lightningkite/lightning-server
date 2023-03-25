package com.lightningkite.lightningdb

import com.github.jershell.kbson.*
import com.lightningkite.lightningdb.*
import com.mongodb.client.model.UpdateOptions
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.*
import org.bson.BsonDocument
import org.bson.BsonTimestamp
import org.bson.BsonType
import org.bson.Document
import org.bson.types.Binary
import org.bson.types.ObjectId
import org.litote.kmongo.Id
import org.litote.kmongo.id.StringId
import org.litote.kmongo.id.WrappedObjectId
import org.litote.kmongo.serialization.*
import org.litote.kmongo.serialization.InstantSerializer
import org.litote.kmongo.serialization.LocalDateSerializer
import org.litote.kmongo.serialization.LocalTimeSerializer
import org.litote.kmongo.serialization.OffsetDateTimeSerializer
import org.litote.kmongo.serialization.ZonedDateTimeSerializer
import java.math.BigDecimal
import java.time.*
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import kotlin.reflect.KProperty1

fun documentOf(): Document {
    return Document()
}
fun documentOf(pair: Pair<String, Any?>): Document {
    return Document(pair.first, pair.second)
}
fun documentOf(vararg pairs: Pair<String, Any?>): Document {
    return Document().apply {
        for(entry in pairs) {
            this[entry.first] = entry.second
        }
    }
}

fun Condition<*>.dump(into: Document = Document(), key: String?): Document {
    when (this) {
        is Condition.Always -> {}
        is Condition.Never -> into["thisFieldWillNeverExist"] = "no never"
        is Condition.And -> {
            if(conditions.any { it is Condition.Or }) {
                into["\$and"] = conditions.map { it.dump(key = key)  }
            } else {
                conditions.forEach {
                    it.dump(into, key)
                }
            }
        }
        is Condition.Or -> if(conditions.isEmpty()) into["thisFieldWillNeverExist"] = "no never" else into["\$or"] = conditions.map { it.dump(key = key)  }
        is Condition.Equal -> into.sub(key)["\$eq"] = value
        is Condition.NotEqual -> into.sub(key)["\$ne"] = value
        is Condition.SetAllElements<*> -> condition.dump(into.sub(key).sub("\$not").sub("\$elemMatch"), key = "\$not")
        is Condition.SetAnyElements<*> -> into.sub(key)["\$elemMatch"] = condition.bson()
        is Condition.ListAllElements<*> -> condition.dump(into.sub(key).sub("\$not").sub("\$elemMatch"), key = "\$not")
        is Condition.ListAnyElements<*> -> into.sub(key)["\$elemMatch"] = condition.bson()
        is Condition.Exists<*> -> into[if (key == null) this.key else "$key.${this.key}"] = documentOf("\$exists" to true)
        is Condition.GreaterThan -> into.sub(key)["\$gt"] = value
        is Condition.LessThan -> into.sub(key)["\$lt"] = value
        is Condition.GreaterThanOrEqual -> into.sub(key)["\$gte"] = value
        is Condition.LessThanOrEqual -> into.sub(key)["\$lte"] = value
        is Condition.IfNotNull -> condition.dump(into, key)
        is Condition.Inside -> into.sub(key)["\$in"] = values
        is Condition.NotInside -> into.sub(key)["\$nin"] = values
        is Condition.IntBitsAnyClear -> into.sub(key)["\$bitsAllClear"] = mask
        is Condition.IntBitsAnySet -> into.sub(key)["\$bitsAllSet"] = mask
        is Condition.IntBitsClear -> into.sub(key)["\$bitsAnyClear"] = mask
        is Condition.IntBitsSet -> into.sub(key)["\$bitsAnySet"] = mask
        is Condition.Not -> TODO("Condition inversion is not supported yet")
        is Condition.OnKey<*> -> condition.dump(into, if (key == null) this.key else "$key.${this.key}")
        is Condition.StringContains -> {
            into.sub(key).also {
                it["\$regex"] = Regex.escape(this.value)
                it["\$options"] = if(this.ignoreCase) "i" else ""
            }
        }
        is Condition.RegexMatches -> {
            into.sub(key).also {
                it["\$regex"] = this.pattern
                it["\$options"] = if(this.ignoreCase) "i" else ""
            }
        }
        is Condition.FullTextSearch -> into["\$text"] = documentOf(
            "\$search" to value,
            "\$caseSensitive" to !this.ignoreCase
        )
        is Condition.SetSizesEquals<*> -> into.sub(key)["\$size"] = count
        is Condition.ListSizesEquals<*> -> into.sub(key)["\$size"] = count
        is Condition.OnField<*, *> -> condition.dump(into, if (key == null) this.key.name else "$key.${this.key.name}")
    }
    return into
}

fun Modification<*>.dump(update: UpdateWithOptions = UpdateWithOptions(), key: String?): UpdateWithOptions {
    val into = update.document
    when(this) {
        is Modification.Chain -> modifications.forEach { it.dump(update, key) }
        is Modification.Assign -> into["\$set", key] = value
        is Modification.CoerceAtLeast -> into["\$max", key] = value
        is Modification.CoerceAtMost -> into["\$min", key] = value
        is Modification.Increment -> into["\$inc", key] = by
        is Modification.Multiply -> into["\$mul", key] = by
        is Modification.AppendString -> TODO("Appending strings is not supported yet")
        is Modification.IfNotNull -> this.modification.dump(update, key)
        is Modification.OnField<*, *> -> modification.dump(update, if (key == null) this.key.name else "$key.${this.key.name}")
        is Modification.ListAppend<*> -> into.sub("\$push").sub(key)["\$each"] = items
        is Modification.ListRemove<*> -> into["\$pull", key] = condition.bson()
        is Modification.ListRemoveInstances<*> -> into["\$pullAll", key] = items
        is Modification.ListDropFirst<*> -> into["\$pop", key] = -1
        is Modification.ListDropLast<*> -> into["\$pop", key] = 1
        is Modification.ListPerElement<*> -> {
            val condIdentifier = genName()
            update.options = update.options.arrayFilters(
                (update.options.arrayFilters ?: listOf()) + condition.dump(key = condIdentifier)
            )
            modification.dump(update, "$key.$[$condIdentifier]")
        }
        is Modification.SetAppend<*> -> into.sub("\$addToSet").sub(key)["\$each"] = items
        is Modification.SetRemove<*> -> into["\$pull", key] = condition.bson()
        is Modification.SetRemoveInstances<*> -> into["\$pullAll", key] = items
        is Modification.SetDropFirst<*> -> into["\$pop", key] = -1
        is Modification.SetDropLast<*> -> into["\$pop", key] = 1
        is Modification.SetPerElement<*> -> {
            val condIdentifier = genName()
            update.options = update.options.arrayFilters(
                (update.options.arrayFilters ?: listOf()) + condition.dump(key = condIdentifier)
            )
            modification.dump(update, "$key.$[$condIdentifier]")
        }
        is Modification.Combine<*> -> map.forEach {
            into.sub("\$set")[if (key == null) it.key else "$key.${it.key}"] = it.value
        }
        is Modification.ModifyByKey<*> -> map.forEach {
            it.value.dump(update, if (key == null) it.key else "$key.${it.key}")
        }
        is Modification.RemoveKeys<*> -> this.fields.forEach {
            into.sub("\$unset")[if (key == null) it else "$key.${it}"] = ""
        }
    }
    return update
}


private fun Document.sub(key: String?): Document = if(key == null) this else getOrPut(key) { Document() } as Document
private operator fun Document.set(owner: String, key: String?, value: Any?) {
    if(key == null) this[owner] = value
    else this.sub(owner)[key] = value
}
private var lastNum = AtomicInteger()
private fun genName(): String {
    val r = 'a' + (lastNum.getAndIncrement() % 26)
    return r.toString()
}

data class UpdateWithOptions(
    val document: Document = Document(),
    var options: UpdateOptions = UpdateOptions()
)

fun Condition<*>.bson() = Document().also { simplify().dump(it, null) }
fun Modification<*>.bson(): UpdateWithOptions = UpdateWithOptions().also { simplify().dump(it, null) }
fun <T> UpdateWithOptions.upsert(model: T, serializer: KSerializer<T>): Boolean {
    val set = (document["\$set"] as? Document)
    val inc = (document["\$inc"] as? Document)
    val restrict = document.entries.asSequence()
        .filter { it.key != "\$set" && it.key != "\$inc" }
        .map { it.value }
        .filterIsInstance<Document>()
        .flatMap { it.keys }
        .toSet()
    document["\$setOnInsert"] = MongoDatabase.bson.stringify(serializer, model).toDocument().also {
        set?.keys?.forEach { k ->
            if(it[k] == set[k]) it.remove(k)
            else {
                return false
            }
        }
        inc?.keys?.forEach { k ->
            if((it[k] as Number).toDouble() == (inc[k] as Number).toDouble()) it.remove(k)
            else {
                return false
            }
        }
        restrict.forEach { k ->
            if(it.containsKey(k)) return false
        }
    }
    options = options.upsert(true)
    return true
}

@OptIn(ExperimentalSerializationApi::class)
fun SerialDescriptor.bsonType(): BsonType = when(kind) {
    SerialKind.ENUM -> BsonType.STRING
    SerialKind.CONTEXTUAL -> when(this.capturedKClass){
        ObjectId::class -> BsonType.OBJECT_ID
        BigDecimal::class -> BsonType.DECIMAL128
        ByteArray::class -> BsonType.BINARY
        Date::class -> BsonType.DATE_TIME
        Calendar::class -> BsonType.DATE_TIME
        GregorianCalendar::class -> BsonType.DATE_TIME
        Instant::class -> BsonType.DATE_TIME
        ZonedDateTime::class -> BsonType.DATE_TIME
        OffsetDateTime::class -> BsonType.DATE_TIME
        LocalDate::class -> BsonType.DATE_TIME
        LocalDateTime::class -> BsonType.DATE_TIME
        LocalTime::class -> BsonType.DATE_TIME
        OffsetTime::class -> BsonType.DATE_TIME
        BsonTimestamp::class -> BsonType.DATE_TIME
        Locale::class -> BsonType.STRING
        Binary::class -> BsonType.BINARY
        Pattern::class -> BsonType.DOCUMENT
        Regex::class -> BsonType.DOCUMENT
        UUID::class -> BsonType.BINARY
        else -> MongoDatabase.bson.serializersModule.getContextualDescriptor(this)!!.bsonType()
    }
    PrimitiveKind.BOOLEAN -> BsonType.BOOLEAN
    PrimitiveKind.BYTE -> BsonType.INT32
    PrimitiveKind.CHAR -> BsonType.SYMBOL
    PrimitiveKind.SHORT -> BsonType.INT32
    PrimitiveKind.INT -> BsonType.INT32
    PrimitiveKind.LONG -> BsonType.INT64
    PrimitiveKind.FLOAT -> BsonType.DOUBLE
    PrimitiveKind.DOUBLE -> BsonType.DOUBLE
    PrimitiveKind.STRING -> BsonType.STRING
    StructureKind.CLASS -> BsonType.DOCUMENT
    StructureKind.LIST -> BsonType.ARRAY
    StructureKind.MAP -> BsonType.DOCUMENT
    StructureKind.OBJECT -> BsonType.STRING
    PolymorphicKind.SEALED -> TODO()
    PolymorphicKind.OPEN -> TODO()
}