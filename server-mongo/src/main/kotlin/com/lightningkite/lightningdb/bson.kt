package com.lightningkite.lightningdb

import com.lightningkite.lightningserver.serialization.bson.*
import com.lightningkite.GeoCoordinateGeoJsonSerializer
import com.lightningkite.lightningdb.*
import com.lightningkite.serialization.*
import com.lightningkite.lightningserver.serialization.Serialization
import com.mongodb.client.model.UpdateOptions
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.*
import org.bson.BsonDocument
import org.bson.BsonNumber
import org.bson.BsonTimestamp
import org.bson.BsonType
import org.bson.BsonValue
import org.bson.Document
import org.bson.types.Binary
import org.bson.types.ObjectId
import java.math.BigDecimal
import kotlinx.datetime.*
import java.util.*
import com.lightningkite.UUID
import java.util.regex.Pattern
import org.bson.BsonArray

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

// TODO: This whole file is terrible

@Serializable private data class Wrapper<T>(val value: T)
fun <T> KBson.stringifyAny(serializer: KSerializer<T>, obj: T): BsonValue {
    return stringify(Wrapper.serializer(serializer), Wrapper(obj))["value"]!!
}


@Suppress("UNCHECKED_CAST")
private fun <T> Condition<T>.dump(serializer: KSerializer<T>, into: Document = Document(), key: String?, atlasSearch: Boolean): Document {
    when (this) {
        is Condition.Always -> {}
        is Condition.Never -> into["thisFieldWillNeverExist"] = "no never"
        is Condition.And -> {
            into["\$and"] = conditions.map { it.dump(serializer, key = key, atlasSearch = atlasSearch)  }
        }
        is Condition.Or -> if(conditions.isEmpty()) into["thisFieldWillNeverExist"] = "no never" else into["\$or"] = conditions.map { it.dump(serializer, key = key, atlasSearch = atlasSearch)  }
        is Condition.Equal -> into.sub(key)["\$eq"] = value.let { Serialization.Internal.bson.stringifyAny(serializer, it) }
        is Condition.NotEqual -> into.sub(key)["\$ne"] = value.let { Serialization.Internal.bson.stringifyAny(serializer, it) }
        is Condition.SetAllElements<*> -> allElements(condition as Condition<Any?>, serializer.listElement()!! as KSerializer<Any?>, into.sub(key), atlasSearch)
        is Condition.SetAnyElements<*> -> into.sub(key)["\$elemMatch"] = (condition as Condition<Any?>).bson(serializer.listElement()!! as KSerializer<Any?>)
        is Condition.ListAllElements<*> -> allElements(condition as Condition<Any?>, serializer.listElement()!! as KSerializer<Any?>, into.sub(key), atlasSearch)
        is Condition.ListAnyElements<*> -> into.sub(key)["\$elemMatch"] = (condition as Condition<Any?>).bson(serializer.listElement()!! as KSerializer<Any?>)
        is Condition.Exists<*> -> into[if (key == null) this.key else "$key.${this.key}"] = documentOf("\$exists" to true)
        is Condition.GreaterThan -> into.sub(key)["\$gt"] = value.let { Serialization.Internal.bson.stringifyAny(serializer, it) }
        is Condition.LessThan -> into.sub(key)["\$lt"] = value.let { Serialization.Internal.bson.stringifyAny(serializer, it) }
        is Condition.GreaterThanOrEqual -> into.sub(key)["\$gte"] = value.let { Serialization.Internal.bson.stringifyAny(serializer, it) }
        is Condition.LessThanOrEqual -> into.sub(key)["\$lte"] = value.let { Serialization.Internal.bson.stringifyAny(serializer, it) }
        // Mongo's $ne, $nin, $not and an empty condition all match a null or missing value, so for those the null check
        // that IfNotNull does in memory has to be spelled out.
        is Condition.IfNotNull<*> -> {
            val nonNullSerializer = serializer.nullElement()!! as KSerializer<Any?>
            // Mongo compares an array with null element by element, so `$ne: null` would reject [1, null].
            val notNull = if (nonNullSerializer.descriptor.kind == StructureKind.LIST) documentOf("\$type" to "array")
                else documentOf("\$ne" to null)
            if (!condition.matchesNullInMongo()) {
                (condition as Condition<Any?>).dump(nonNullSerializer, into, key, atlasSearch = atlasSearch)
            } else if (key != null) {
                into["\$and"] = listOfNotNull(
                    documentOf(key to notNull),
                    (condition as Condition<Any?>).takeUnless { it is Condition.Always }
                        ?.dump(nonNullSerializer, key = key, atlasSearch = atlasSearch),
                )
            } else {
                // No key means this is a list element inside $elemMatch. The operators there apply to the element itself,
                // so the check joins them, with a $ne rewritten as the equivalent $nin to make room for it.
                // Conditions on the element's fields need no check: $elemMatch never matches a null element with those.
                // $and/$or/$nor aren't operators on the element, so those forms get no check (and Mongo rejects them here).
                val inner = (condition as? Condition.NotEqual<Any?>)?.let { Condition.NotInside(listOf(it.value)) } ?: condition
                val operators = (inner as Condition<Any?>).dump(nonNullSerializer, key = null, atlasSearch = atlasSearch)
                into.putAll(operators)
                if (operators.keys.all { it.startsWith("\$") && it !in setOf("\$and", "\$or", "\$nor") }) into.putAll(notNull)
            }
        }
        is Condition.Inside -> into.sub(key)["\$in"] = values.let { Serialization.Internal.bson.stringifyAny(ListSerializer(serializer), it) }
        is Condition.NotInside -> into.sub(key)["\$nin"] = values.let { Serialization.Internal.bson.stringifyAny(ListSerializer(serializer), it) }
        is Condition.IntBitsAnyClear -> into.sub(key)["\$bitsAllClear"] = mask
        is Condition.IntBitsAnySet -> into.sub(key)["\$bitsAllSet"] = mask
        is Condition.IntBitsClear -> into.sub(key)["\$bitsAnyClear"] = mask
        is Condition.IntBitsSet -> into.sub(key)["\$bitsAnySet"] = mask
        is Condition.Not -> {
            val inner = condition.dump(serializer, key = key, atlasSearch = atlasSearch)
            // Inside $elemMatch on plain values there's no field name, and $nor only takes queries on fields.
            val isOperatorDocument = inner.keys.all { it.startsWith("\$") && it !in setOf("\$and", "\$or", "\$nor", "\$not") }
            if (inner.isNotEmpty() && isOperatorDocument) into["\$not"] = inner
            else into["\$nor"] = listOf(inner)
        }
//        is Condition.Not -> condition.dump(serializer, into.sub(key)["\$not"], key)
        // Like IfNotNull: OnKey never matches a missing key in memory, but $ne, $nin and $not do.
        is Condition.OnKey<*> -> {
            val path = if (key == null) this.key else "$key.${this.key}"
            val valueSerializer = serializer.mapValueElement() as KSerializer<Any?>
            if (!condition.matchesNullInMongo()) {
                (condition as Condition<Any?>).dump(valueSerializer, into, path, atlasSearch = atlasSearch)
            } else {
                into["\$and"] = listOfNotNull(
                    documentOf(path to documentOf("\$exists" to true)),
                    (condition as Condition<Any?>).takeUnless { it is Condition.Always }
                        ?.dump(valueSerializer, key = path, atlasSearch = atlasSearch),
                )
            }
        }
        is Condition.GeoDistance -> into.sub(key)["\$geoWithin"] = documentOf(
            "\$centerSphere" to listOf(
                listOf(this.value.longitude, this.value.latitude),
                this.lessThanKilometers / 6378.1
            )
//            "\$maxDistance" to this.lessThanKilometers * 1000,
//            "\$minDistance" to this.greaterThanKilometers * 1000,
//            "\$geometry" to Serialization.Internal.bson.stringifyAny(GeoCoordinateGeoJsonSerializer, this.value),
        )
        is Condition.StringContains -> {
            into.sub(key).also {
                it["\$regex"] = Regex.escape(this.value)
                it["\$options"] = if(this.ignoreCase) "i" else ""
            }
        }
        is Condition.RawStringContains -> {
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
        is Condition.FullTextSearch -> {
            if(atlasSearch) {
                val terms = value.split(' ')
                val ser = DataClassPathSerializer(serializer)
                val paths = serializer.descriptor.annotations.filterIsInstance<TextIndex>().firstOrNull()?.fields?.map {
                    ser.fromString(it) as DataClassPath<T, String>
                }?.takeIf { it.isNotEmpty() } ?: return into
                val subs = terms.filter { !it.termShouldUseFuzzySearch() }
                    .map { term ->
                        Condition.Or(paths.map { it.mapCondition(Condition.StringContains(term, true)) })
                    }
                if(subs.isNotEmpty()) {
                    if (this.requireAllTermsPresent)
                        Condition.And(subs).dump(serializer, into, key, atlasSearch)
                    else
                        Condition.Or(subs).dump(serializer, into, key, atlasSearch)
                }
            } else into["\$text"] = documentOf(
                "\$search" to value,
                "\$caseSensitive" to false
            )
        }
        is Condition.SetSizesEquals<*> -> into.sub(key)["\$size"] = count
        is Condition.ListSizesEquals<*> -> into.sub(key)["\$size"] = count
        is Condition.OnField<*, *> -> (condition as Condition<Any?>).dump(this.key.serializer as KSerializer<Any?>, into, if (key == null) this.key.name else "$key.${this.key.name}", atlasSearch = atlasSearch)
    }
    return into
}

// "All elements match" is "no element fails". A null element can't fail a condition on its fields inside $elemMatch, so
// when the condition rejects null, null elements are ruled out separately.
@Suppress("UNCHECKED_CAST")
private fun allElements(condition: Condition<Any?>, elementSerializer: KSerializer<Any?>, into: Document, atlasSearch: Boolean) {
    into["\$not"] = documentOf("\$elemMatch" to Condition.Not(condition).dump(elementSerializer, key = null, atlasSearch = atlasSearch))
    if (elementSerializer.descriptor.isNullable && !condition(null)) into["\$ne"] = null
}

// False only where it's certain that this condition's translation can't match a null or missing value.
private fun Condition<*>.matchesNullInMongo(): Boolean = when (this) {
    is Condition.Never, is Condition.GreaterThan, is Condition.LessThan, is Condition.GreaterThanOrEqual,
    is Condition.LessThanOrEqual, is Condition.StringContains, is Condition.RawStringContains, is Condition.RegexMatches,
    is Condition.IntBitsClear, is Condition.IntBitsSet, is Condition.IntBitsAnyClear, is Condition.IntBitsAnySet,
    is Condition.ListSizesEquals<*>, is Condition.SetSizesEquals<*>, is Condition.Exists<*>, is Condition.OnKey<*>,
    is Condition.IfNotNull<*>, is Condition.GeoDistance, is Condition.ListAnyElements<*>, is Condition.SetAnyElements<*> -> false
    is Condition.Equal -> value == null
    is Condition.Inside -> values.any { it == null }
    is Condition.And -> conditions.all { it.matchesNullInMongo() }
    is Condition.Or -> conditions.any { it.matchesNullInMongo() }
    // A missing parent makes the field missing too.
    is Condition.OnField<*, *> -> condition.matchesNullInMongo()
    else -> true
}

@Suppress("UNCHECKED_CAST")
private fun <T> Modification<T>.dump(serializer: KSerializer<T>, update: UpdateWithOptions = UpdateWithOptions(), key: String?): UpdateWithOptions {
    val into = update.document
    when(this) {
        is Modification.Nothing -> TODO("Not supported")
        is Modification.Chain -> modifications.forEach { it.dump(serializer, update, key) }
        is Modification.Assign -> into["\$set", key] = value.let { Serialization.Internal.bson.stringifyAny(serializer, it) }
        is Modification.CoerceAtLeast -> into["\$max", key] = value.let { Serialization.Internal.bson.stringifyAny(serializer, it) }
        is Modification.CoerceAtMost -> into["\$min", key] = value.let { Serialization.Internal.bson.stringifyAny(serializer, it) }
        is Modification.Increment -> into["\$inc", key] = by.let { Serialization.Internal.bson.stringifyAny(serializer, it) }
        is Modification.Multiply -> into["\$mul", key] = by.let { Serialization.Internal.bson.stringifyAny(serializer, it) }
        is Modification.AppendString -> TODO("Appending strings is not supported yet")
        is Modification.AppendRawString -> TODO("Appending raw strings is not supported yet")
        // IfNotNull only gets here once something else does its check: the update's filter or an array filter (see [bson]
        // and [perElement]). Update operators can't.
        is Modification.IfNotNull<*> -> (modification as Modification<Any?>).dump(serializer.nullElement()!! as KSerializer<Any?>, update, key)
        is Modification.OnField<*, *> ->
            (modification as Modification<Any?>).dump(this.key.serializer as KSerializer<Any?>, update, if (key == null) this.key.name else "$key.${this.key.name}")
        is Modification.ListAppend<*> -> into.sub("\$push").sub(key)["\$each"] = items.let { Serialization.Internal.bson.stringifyAny(serializer as KSerializer<List<Any?>>, it) }
        is Modification.ListRemove<*> -> into["\$pull", key] = (condition as Condition<Any?>).bson(serializer.listElement() as KSerializer<Any?>)
        is Modification.ListRemoveInstances<*> -> into["\$pullAll", key] = items.let { Serialization.Internal.bson.stringifyAny(serializer as KSerializer<List<Any?>>, it) }
        is Modification.ListDropFirst<*> -> into["\$pop", key] = -1
        is Modification.ListDropLast<*> -> into["\$pop", key] = 1
        is Modification.ListPerElement<*> -> perElement(condition as Condition<Any?>, modification as Modification<Any?>, serializer.listElement() as KSerializer<Any?>, update, key)
        is Modification.SetAppend<*> -> into.sub("\$addToSet").sub(key)["\$each"] = items.let { Serialization.Internal.bson.stringifyAny(serializer as KSerializer<Set<Any?>>, it) }
        is Modification.SetRemove<*> -> into["\$pull", key] = (condition as Condition<Any?>).bson(serializer.listElement() as KSerializer<Any?>)
        is Modification.SetRemoveInstances<*> -> into["\$pullAll", key] = items.let { Serialization.Internal.bson.stringifyAny(serializer as KSerializer<Set<Any?>>, it) }
        is Modification.SetDropFirst<*> -> into["\$pop", key] = -1
        is Modification.SetDropLast<*> -> into["\$pop", key] = 1
        is Modification.SetPerElement<*> -> perElement(condition as Condition<Any?>, modification as Modification<Any?>, serializer.listElement() as KSerializer<Any?>, update, key)
        is Modification.Combine<*> -> map.forEach {
            into.sub("\$set")[if (key == null) it.key else "$key.${it.key}"] = it.value.let { Serialization.Internal.bson.stringifyAny(serializer.mapValueElement() as KSerializer<Any?>, it) }
        }
        is Modification.ModifyByKey<*> -> map.forEach {
            (it.value as Modification<Any?>).dump(serializer.mapValueElement() as KSerializer<Any?>, update, if (key == null) it.key else "$key.${it.key}")
        }
        is Modification.RemoveKeys<*> -> this.fields.forEach {
            into.sub("\$unset")[if (key == null) it else "$key.${it}"] = ""
        }
    }
    return update
}

// Splits a modification into parts, each with the notNull checks (and, for modifyByKey, key checks) it needs.
// Applying the parts in order is the same as applying the whole, and a part never changes the outcome of its own check,
// since it only writes below the checked value.
@Suppress("UNCHECKED_CAST")
private fun Modification<Any?>.checkedParts(): List<Pair<Condition<Any?>, Modification<Any?>>> = when (this) {
    is Modification.Chain -> modifications.flatMap { it.checkedParts() }
    is Modification.OnField<*, *> -> (modification as Modification<Any?>).checkedParts().map { (check, part) ->
        val field = this.key as SerializableProperty<Any?, Any?>
        (if (check is Condition.Always) check else Condition.OnField(field, check)) to Modification.OnField(field, part)
    }
    is Modification.IfNotNull<*> -> (modification as Modification<Any?>).checkedParts().map { (check, part) ->
        Condition.IfNotNull(check) to Modification.IfNotNull(part)
    }
    // In memory, modifying a missing key throws; Mongo would create it.
    is Modification.ModifyByKey<*> -> map.entries.flatMap { (key, modification) ->
        (modification as Modification<Any?>).checkedParts().map { (check, part) ->
            Condition.OnKey(key, check) as Condition<Any?> to Modification.ModifyByKey(mapOf(key to part)) as Modification<Any?>
        }
    }
    else -> listOf(Condition.Always to this)
}

// Update operators can't check a value before changing it, so each check inside an element gets its own array filter.
@Suppress("UNCHECKED_CAST")
private fun perElement(
    condition: Condition<Any?>,
    modification: Modification<Any?>,
    elementSerializer: KSerializer<Any?>,
    update: UpdateWithOptions,
    key: String?,
) {
    for ((check, parts) in modification.checkedParts().groupBy({ it.first }, { it.second })) {
        val filter = Condition.And(listOf(condition, check)).simplify()
        if (filter is Condition.Always) {
            Modification.Chain(parts).dump(elementSerializer, update, "$key.$[]")
        } else {
            val identifier = "f${update.options.arrayFilters?.size ?: 0}"
            update.options = update.options.arrayFilters(
                (update.options.arrayFilters ?: listOf()) + filter.dump(elementSerializer, key = identifier, atlasSearch = false)
            )
            Modification.Chain(parts).dump(elementSerializer, update, "$key.$[$identifier]")
        }
    }
}

private fun Document.sub(key: String?): Document = if(key == null) this else getOrPut(key) { Document() } as Document
private operator fun Document.set(owner: String, key: String?, value: Any?) {
    if(key == null) this[owner] = value
    else this.sub(owner)[key] = value
}

data class UpdateWithOptions(
    val document: Document = Document(),
    var options: UpdateOptions = UpdateOptions(),
    /** The notNull and modifyByKey checks outside lists, which the update's filter must include. */
    val check: Document? = null,
)

fun <T> Condition<T>.bson(serializer: KSerializer<T>, atlasSearch: Boolean = false) = Document().also { dump(serializer, it, null, atlasSearch) }
/**
 * Update operators can't check a value before changing it, so every notNull check outside a list goes into
 * [UpdateWithOptions.check]: a row failing any of them isn't written at all. Inside a list, [perElement] uses array filters.
 */
@Suppress("UNCHECKED_CAST")
fun <T> Modification<T>.bson(serializer: KSerializer<T>): UpdateWithOptions {
    val anySerializer = serializer as KSerializer<Any?>
    val parts = (this as Modification<Any?>).checkedParts()
    val check = Condition.And(parts.map { it.first }.distinct()).simplify()
    val update = UpdateWithOptions(check = if (check is Condition.Always) null else check.bson(anySerializer))
    Modification.Chain(parts.map { it.second }).dump(anySerializer, update, null)
    return update
}
fun <T> UpdateWithOptions.upsert(model: T, serializer: KSerializer<T>): Boolean {
    // With a check, Mongo would insert where a row matched but failed the check.
    if (check != null) return false
    val set: Document? = (document["\$set"] as? Document) ?: (document["\$set"] as? BsonDocument)?.toDocument()
    val inc = (document["\$inc"] as? Document) ?: (document["\$inc"] as? BsonDocument)?.toDocument()
    val restrict = document.entries.asSequence()
        .filter { it.key != "\$set" && it.key != "\$inc" }
        .map { it.value }
        .filterIsInstance<Document>()
        .flatMap { it.keys }
        .toSet()
    document["\$setOnInsert"] = Serialization.Internal.bson.stringify(serializer, model).toDocument().also {
        set?.keys?.forEach { k ->
            if(it[k] == set[k]) it.remove(k)
            else {
                return false
            }
        }
        inc?.keys?.forEach { k ->
            if((it[k] as Number).toDouble() == (inc[k] as BsonNumber).doubleValue()) it.remove(k)
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
        LocalDate::class -> BsonType.DATE_TIME
        LocalDateTime::class -> BsonType.DATE_TIME
        LocalTime::class -> BsonType.DATE_TIME
        BsonTimestamp::class -> BsonType.DATE_TIME
        Locale::class -> BsonType.STRING
        Binary::class -> BsonType.BINARY
        Pattern::class -> BsonType.DOCUMENT
        Regex::class -> BsonType.DOCUMENT
        UUID::class -> BsonType.BINARY
        else -> Serialization.Internal.bson.serializersModule.getContextualDescriptor(this)!!.bsonType()
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