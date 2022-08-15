package com.lightningkite.lightningserver.db

import com.lightningkite.khrysalis.IsCodableAndHashable
import com.lightningkite.lightningdb.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import kotlin.reflect.KProperty1

data class DynamoCondition<T>(
    val local: Condition<T>? = null,
    val never: Boolean = false,
    val writeKey: (DynamoQueryBuilder.Part.() -> Unit)? = null,
    val writeFilter: (DynamoQueryBuilder.Part.() -> Unit)? = null,
) {
    val writeCombined: (DynamoQueryBuilder.Part.() -> Unit)?
        get() {
            return if(writeKey != null && writeFilter != null) {
                {
                    filter.append('(')
                    writeKey.invoke(this)
                    filter.append(" and ")
                    writeFilter.invoke(this)
                    filter.append(')')
                }
            } else writeKey ?: writeFilter
        }
    val builder: DynamoQueryBuilder by lazy { DynamoQueryBuilder() }
    val builderKey: DynamoQueryBuilder.Part? by lazy { writeKey?.let { builder.Part().also { p -> it(p) } } }
    val builderFilter: DynamoQueryBuilder.Part? by lazy { writeFilter?.let { builder.Part().also { p -> it(p) } } }
    val builderCombined: DynamoQueryBuilder.Part? by lazy { writeCombined?.let { builder.Part().also { p -> it(p) } } }
}
data class DynamoModification<T>(
    val local: Modification<T>? = null,
    val set: List<DynamoQueryBuilder.Part.() -> Unit> = listOf(),
    val remove: List<DynamoQueryBuilder.Part.() -> Unit> = listOf(),
    val add: List<DynamoQueryBuilder.Part.() -> Unit> = listOf(),
    val delete: List<DynamoQueryBuilder.Part.() -> Unit> = listOf(),
) {
}

fun <T> Condition<T>.dynamo(serializer: KSerializer<T>): DynamoCondition<T> {
    return when (this) {
        is Condition.Never -> DynamoCondition(never = true)
        is Condition.Always -> DynamoCondition()
        is Condition.And -> {
            val subs = conditions.map { it.dynamo(serializer) }
            if (subs.any { it.never }) return DynamoCondition(never = true)
            DynamoCondition(
                writeKey = subs.mapNotNull { it.writeKey }.takeUnless { it.isEmpty() }?.let {
                    {
                        filter.append('(')
                        var first = true
                        it.forEach {
                            if (first) first = false
                            else filter.append(" and ")
                            it(this)
                        }
                        filter.append(')')
                    }
                },
                writeFilter = subs.mapNotNull { it.writeFilter }.takeUnless { it.isEmpty() }?.let {
                    {
                        filter.append('(')
                        var first = true
                        it.forEach {
                            if (first) first = false
                            else filter.append(" and ")
                            it(this)
                        }
                        filter.append(')')
                    }
                },
                local = subs.mapNotNull { it.local }.let {
                    when (it.size) {
                        0 -> null
                        1 -> it[0]
                        else -> Condition.And(it)
                    }
                }
            )
        }

        is Condition.Or -> {
            val subs = conditions.map { it.dynamo(serializer) }.filter { !it.never }
            if (subs.isEmpty()) return DynamoCondition(never = true)
            if (subs.any { it.local != null }) return DynamoCondition(local = this)
            DynamoCondition(
                writeFilter = subs.mapNotNull { it.writeCombined }.takeUnless { it.isEmpty() }?.let {
                    {
                        filter.append('(')
                        var first = true
                        it.forEach {
                            if (first) first = false
                            else filter.append(" and ")
                            it(this)
                        }
                        filter.append(')')
                    }
                },
            )
        }

        is Condition.Not -> {
            val inner = condition.dynamo(serializer)
            DynamoCondition(
                writeFilter = inner.writeCombined?.let {
                    return@let {
                        filter.append("NOT ")
                        it()
                    }
                },
                local = inner.local?.let { this },
                never = inner.writeKey == null && inner.writeFilter == null && inner.local == null && !inner.never
            )
        }

        is Condition.Equal -> DynamoCondition(writeKey = {
            key(field)
            filter.append(" = ")
            value(value, serializer)
        })

        is Condition.NotEqual -> DynamoCondition(writeFilter = {
            key(field)
            filter.append(" <> ")
            value(value, serializer)
        })

        is Condition.Inside -> DynamoCondition(writeKey = {
            key(field)
            filter.append(" IN ")
            value(values, ListSerializer(serializer))
        })

        is Condition.NotInside -> Condition.Not(Condition.Inside(values)).dynamo(serializer)
        is Condition.GreaterThan -> DynamoCondition(writeKey = {
            key(field)
            filter.append(" > ")
            value(value, serializer)
        })

        is Condition.LessThan -> DynamoCondition(writeKey = {
            key(field)
            filter.append(" < ")
            value(value, serializer)
        })

        is Condition.GreaterThanOrEqual -> DynamoCondition(writeKey = {
            key(field)
            filter.append(" >= ")
            value(value, serializer)
        })

        is Condition.LessThanOrEqual -> DynamoCondition(writeKey = {
            key(field)
            filter.append(" <= ")
            value(value, serializer)
        })

        is Condition.StringContains -> DynamoCondition(writeFilter = {
            filter.append("contains(")
            key(field)
            filter.append(", ")
            value(value, String.serializer())
            filter.append(")")
        })

        is Condition.SetSizesEquals<*> -> DynamoCondition(writeFilter = {
            filter.append("size(")
            key(field)
            filter.append(')')
        })

        is Condition.ListSizesEquals<*> -> DynamoCondition(writeFilter = {
            filter.append("size(")
            key(field)
            filter.append(')')
        })

        is Condition.Exists<*> -> DynamoCondition(writeFilter = {
            filter.append("attribute_exists(")
            key(field)
            filter.append('.')
            filter.append(key)
            filter.append(')')
        })

        is Condition.OnKey<*> -> {
            @Suppress("UNCHECKED_CAST")
            val inner = (condition as Condition<Any?>).dynamo(serializer.mapValueElement()!! as KSerializer<Any?>)
            DynamoCondition(
                local = (this as Condition<T>).takeIf { inner.local != null },
                never = inner.never,
                writeKey = inner.writeKey?.let {
                    {
                        val oldField = field
                        if (field.isEmpty()) field = key
                        else field += ".$key"
                        it(this)
                        field = oldField
                    }
                },
                writeFilter = inner.writeFilter?.let {
                    {
                        val oldField = field
                        if (field.isEmpty()) field = key
                        else field += ".$key"
                        it(this)
                        field = oldField
                    }
                }
            )
        }

        is Condition.OnField<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            val inner =
                (condition as Condition<IsCodableAndHashable>).dynamo(serializer.fieldSerializer(key as KProperty1<T, IsCodableAndHashable>)!!)
            val indexed = key.name == "_id"
            if(indexed) {
                DynamoCondition(
                    local = (this as Condition<T>).takeIf { inner.local != null },
                    never = inner.never,
                    writeKey = inner.writeKey?.let {
                        {
                            val oldField = field
                            if (field.isEmpty()) field = key.name
                            else field += ".${key.name}"
                            it(this)
                            field = oldField
                        }
                    },
                    writeFilter = inner.writeFilter?.let {
                        {
                            val oldField = field
                            if (field.isEmpty()) field = key.name
                            else field += ".${key.name}"
                            it(this)
                            field = oldField
                        }
                    }
                )
            } else {
                DynamoCondition(
                    local = (this as Condition<T>).takeIf { inner.local != null },
                    never = inner.never,
                    writeFilter = inner.writeCombined?.let {
                        {
                            val oldField = field
                            if (field.isEmpty()) field = key.name
                            else field += ".${key.name}"
                            it(this)
                            field = oldField
                        }
                    }
                )
            }
        }

        is Condition.IfNotNull<*> -> {
            @Suppress("UNCHECKED_CAST")
            val inner = (condition as Condition<T>).dynamo(serializer.nullElement()!! as KSerializer<T>)
            DynamoCondition<T>(
                local = (this as Condition<T>).takeIf { inner.local != null },
                never = inner.never,
                writeKey = inner.writeKey,
                writeFilter = inner.writeFilter
            )
        }

        else -> DynamoCondition(local = this)
    }
}

fun <T> Modification<T>.dynamo(serializer: KSerializer<T>): DynamoModification<T> {
    return when(this) {
        is Modification.Chain -> {
            val subs = this.modifications.mapNotNull { it.dynamo() }
            DynamoModification(
                local = subs.mapNotNull { it.local }.let {
                    when(it.size) {
                        0 -> null
                        1 -> it[0]
                        else -> Modification.Chain(it)
                    }
                },
                set = subs.flatMap { it.set },
                remove = subs.flatMap { it.remove },
                add = subs.flatMap { it.add },
                delete = subs.flatMap { it.delete },
            )
        }
        is Modification.Assign -> DynamoModification<T>(set = listOf {
            key(field)
            filter.append(" = ")
            value(this@dynamo.value, serializer)
        })
//        is Modification.CoerceAtLeast -> into["\$max", key] = value
//        is Modification.CoerceAtMost -> into["\$min", key] = value
        is Modification.Increment -> DynamoModification<T>(set = listOf {
            key(field)
            filter.append(" = ")
            key(field)
            filter.append(" + ")
            value(this@dynamo.by, serializer)
        })
//        is Modification.Multiply -> into["\$mul", key] = by
        is Modification.AppendString -> DynamoModification<T>(set = listOf {
            key(field)
            filter.append(" = ")
            key(field)
            filter.append(" + ")
            value(this@dynamo.value, String.serializer())
        })
        is Modification.IfNotNull<*> -> {
            @Suppress("UNCHECKED_CAST")
            val inner = (modification as Modification<T>).dynamo(serializer.nullElement()!! as KSerializer<T>)
            inner
        }
        is Modification.OnField<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            val inner =
                (condition as Modification<IsCodableAndHashable>).dynamo(serializer.fieldSerializer(key as KProperty1<T, IsCodableAndHashable>)!!)
            DynamoModification(
                local = (this as Modification<T>).takeIf { inner.local != null },
                writeFilter = inner.writeCombined?.let {
                    {
                        val oldField = field
                        if (field.isEmpty()) field = key.name
                        else field += ".${key.name}"
                        it(this)
                        field = oldField
                    }
                }
            )
        }
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
}

class DynamoQueryBuilder {
    inner class Part(val filter: StringBuilder = StringBuilder()) {
        var field: String
            get() = this@DynamoQueryBuilder.field
            set(value: String) {
                this@DynamoQueryBuilder.field = value
            }

        fun key(name: String) {
            val identifier = "#i${counter++}"
            nameMap[identifier] = name
            filter.append(identifier)
        }

        fun <T> value(value: T, serializer: KSerializer<T>) {
            val identifier = ":i${counter++}"
            valueMap[identifier] = serializer.toDynamo(value)
            filter.append(identifier)
        }
    }

    var field: String = ""
    val nameMap = HashMap<String, String>()
    val valueMap = HashMap<String, AttributeValue>()
    var counter = 0
    var kotlin: (Any?) -> Boolean = { true }
}
