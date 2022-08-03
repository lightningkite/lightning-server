package com.lightningkite.lightningdb

import com.lightningkite.lightningdb.*
import com.mongodb.client.model.UpdateOptions
import org.bson.BsonDocument
import org.bson.Document
import java.util.concurrent.atomic.AtomicInteger

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
        is Condition.And -> if(conditions.isNotEmpty()) into["\$and"] = conditions.map { it.dump(key = key)  }
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
                it["\$regex"] = Regex.fromLiteral(this.value).pattern
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
        is Modification.ListAppend<*> -> into.sub("\$push").sub(key)["\$each"] = items
        is Modification.SetAppend<*> -> into.sub("\$addToSet").sub(key)["\$each"] = items
        is Modification.AppendString -> TODO("Appending strings is not supported yet")
        is Modification.SetDropFirst<*> -> into["\$pop", key] = -1
        is Modification.SetDropLast<*> -> into["\$pop", key] = 1
        is Modification.ListDropFirst<*> -> into["\$pop", key] = -1
        is Modification.ListDropLast<*> -> into["\$pop", key] = 1
        is Modification.IfNotNull -> this.modification.dump(update, key)
        is Modification.OnField<*, *> -> modification.dump(update, if (key == null) this.key.name else "$key.${this.key.name}")
        is Modification.SetPerElement<*> -> {
            val condIdentifier = genName()
            update.options = update.options.arrayFilters(
                (update.options.arrayFilters ?: listOf()) + condition.dump(key = condIdentifier)
            )
            modification.dump(update, "$key.$[$condIdentifier]")
        }
        is Modification.ListPerElement<*> -> {
            val condIdentifier = genName()
            update.options = update.options.arrayFilters(
                (update.options.arrayFilters ?: listOf()) + condition.dump(key = condIdentifier)
            )
            modification.dump(update, "$key.$[$condIdentifier]")
        }
        is Modification.SetRemove<*> -> into["\$pull", key] = condition.bson()
        is Modification.SetRemoveInstances<*> -> into["\$pullAll", key] = items
        is Modification.ListRemove<*> -> into["\$pull", key] = condition.bson()
        is Modification.ListRemoveInstances<*> -> into["\$pullAll", key] = items
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
fun Modification<*>.bson(): UpdateWithOptions = UpdateWithOptions().also { dump(it, null) }
