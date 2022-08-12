package com.lightningkite.lightningserver.db

import com.lightningkite.khrysalis.IsCodableAndHashable
import com.lightningkite.lightningdb.Condition
import com.lightningkite.lightningdb.fieldSerializer
import com.lightningkite.lightningdb.mapValueElement
import com.lightningkite.lightningdb.nullElement
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
