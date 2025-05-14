package com.lightningkite.lightningdb

import com.lightningkite.serialization.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.NothingSerializer
import kotlinx.serialization.serializer

@Serializable
@GenerateDataClassPaths
data class Mask<T>(
    /**
     * If the condition does not pass, then the modification will be applied to mask the values.
     */
    val pairs: List<Pair<Condition<T>, Modification<T>>> = listOf()
) {
    operator fun invoke(on: T): T {
        var value = on
        for(pair in pairs) {
            if(!pair.first(on)) value = pair.second(value)
        }
        return value
    }
    operator fun invoke(on: Partial<T>): Partial<T> {
        var value = on
        for(pair in pairs) {
            val evaluated = pair.first(on)
            if(evaluated != true) value = pair.second(value)
        }
        return value
    }
    fun permitSort(on: List<SortPart<T>>): Condition<T> {
        val totalConditions = ArrayList<Condition<T>>()
        for(pair in pairs) {
            if(on.any { pair.second.affects(it.field) }) totalConditions.add(pair.first)
        }
        return when(totalConditions.size) {
            0 -> Condition.Always
            1 -> totalConditions[0]
            else -> Condition.And(totalConditions)
        }
    }
    operator fun invoke(on: DataClassPathPartial<T>): Condition<T> {
        val totalConditions = ArrayList<Condition<T>>()
        for(pair in pairs) {
            if(pair.second.affects(on)) totalConditions.add(pair.first)
        }
        return when(totalConditions.size) {
            0 -> Condition.Always
            1 -> totalConditions[0]
            else -> Condition.And(totalConditions)
        }
    }
    operator fun invoke(condition: Condition<T>, tableTextPaths: List<List<SerializableProperty<*, *>>> = listOf()): Condition<T> {
        val totalConditions = ArrayList<Condition<T>>()
        for(pair in pairs) {
            if(condition.readsResultOf(pair.second, tableTextPaths)) totalConditions.add(pair.first)
        }
        return when(totalConditions.size) {
            0 -> Condition.Always
            1 -> totalConditions[0]
            else -> Condition.And(totalConditions)
        }
    }
    class Builder<T>(
        serializer: KSerializer<T>,
        val pairs: ArrayList<Pair<Condition<T>, Modification<T>>> = ArrayList()
    ) {
        val it = DataClassPathSelf(serializer)
        fun <V> DataClassPath<T, V>.mask(value: V, unless: Condition<T> = Condition.Never) {
            pairs.add(unless to mapModification(Modification.Assign(value)))
        }
        infix fun <V> DataClassPath<T, V>.maskedTo(value: V) = mapModification(Modification.Assign(value))
        infix fun Modification<T>.unless(condition: Condition<T>) {
            pairs.add(condition to this)
        }
        fun always(modification: Modification<T>) {
            pairs.add(Condition.Never to modification)
        }
        fun build() = Mask(pairs)
        fun include(mask: Mask<T>) { pairs.addAll(mask.pairs) }
    }
}

inline fun <reified T> mask(builder: Mask.Builder<T>.(DataClassPath<T, T>)->Unit): Mask<T> {
    return Mask.Builder<T>(serializer<T>()).apply { builder(path()) }.build()
}

operator fun <T> Condition<T>.invoke(map: Partial<T>): Boolean? {
    return when(this) {
        is Condition.Always -> true
        is Condition.Never -> false
        is Condition.And -> {
            val results = this.conditions.map { it(map) }
            if(results.any { it == false }) false
            else if(results.any { it == null }) null
            else true
        }
        is Condition.Or -> {
            val results = this.conditions.map { it(map) }
            if(results.any { it == true }) true
            else if(results.any { it == null }) null
            else true
        }
        is Condition.Not -> condition(map)?.not()
        is Condition.OnField<*, *> -> if(map.parts.containsKey(key)) map.parts[key].let {
            @Suppress("UNCHECKED_CAST")
            if(it is Partial<*>) (condition as Condition<Any?>).invoke(map = it as Partial<Any?>)
            else (condition as Condition<Any?>)(it)
        } else null
        else -> null
    }
}
@Suppress("UNCHECKED_CAST")
operator fun <T> Modification<T>.invoke(map: Partial<T>): Partial<T> {
    return when(this) {
        is Modification.OnField<*, *> -> if(map.parts.containsKey(key)) {
            val newPartial = Partial<T>(map.parts.toMutableMap())
            newPartial.parts[key as SerializableProperty<T, *>] = map.parts[key].let {
                if (it is Partial<*>) (modification as Modification<Any?>)(it as Partial<Any?>)
                else (modification as Modification<Any?>)(it)
            }
            newPartial
        } else map
        else -> map
    }
}

fun <K, V> Modification<K>.valueSetForDataClassPath(path: DataClassPath<K, V>): V? =
    (forDataClassPath<V>(path.properties) as? Modification.Assign<V>)?.value

fun <K, V> Modification<K>.forDataClassPath(path: DataClassPath<K, V>): Modification<V>? =
    forDataClassPath<V>(path.properties)

@Suppress("UNCHECKED_CAST")
private fun <V> Modification<*>.forDataClassPath(list: List<SerializableProperty<*, *>>): Modification<V>? {
    return when (this) {
        is Modification.OnField<*, *> -> if (list.first() == this.key) {
            if (list.size == 1) modification as Modification<V>
            else this.modification.forDataClassPath(list.drop(1))
        } else null

        is Modification.SetPerElement<*> -> this.modification.forDataClassPath(list)
        is Modification.ListPerElement<*> -> this.modification.forDataClassPath(list)
        is Modification.Chain -> this.modifications.mapNotNull { it.forDataClassPath<V>(list) }.let {
            when (it.size) {
                0 -> null
                1 -> it.first()
                else -> Modification.Chain(it)
            }
        }

        is Modification.IfNotNull -> this.modification.forDataClassPath(list)
        is Modification.Assign -> Modification.Assign(list.fold(value) { value, prop ->
            (prop as SerializableProperty<Any?, Any?>).get(
                value
            )
        } as V)

        else -> throw Exception("We have no idea what the partial effect is!")
    }
}

fun Modification<*>.affects(path: DataClassPathPartial<*>): Boolean = affects(path.properties)
private fun Modification<*>.affects(list: List<SerializableProperty<*, *>>): Boolean {
    return when (this) {
        is Modification.OnField<*, *> -> if (list.first() == this.key) {
            if (list.size == 1) true
            else this.modification.affects(list.drop(1))
        } else false

        is Modification.SetPerElement<*> -> this.modification.affects(list)
        is Modification.ListPerElement<*> -> this.modification.affects(list)
        is Modification.Chain -> this.modifications.any { it.affects(list) }
        is Modification.IfNotNull -> this.modification.affects(list)
        else -> true
    }
}

fun Condition<*>.reads(path: DataClassPathPartial<*>): Boolean = reads(path.properties)
private fun Condition<*>.reads(list: List<SerializableProperty<*, *>>): Boolean {
    return when (this) {
        is Condition.OnField<*, *> -> if (list.first() == this.key) {
            if (list.size == 1) true
            else this.condition.reads(list.drop(1))
        } else false

        is Condition.ListAllElements<*> -> this.condition.reads(list)
        is Condition.ListAnyElements<*> -> this.condition.reads(list)
        is Condition.SetAllElements<*> -> this.condition.reads(list)
        is Condition.SetAnyElements<*> -> this.condition.reads(list)
        is Condition.And -> this.conditions.any { it.reads(list) }
        is Condition.Or -> this.conditions.any { it.reads(list) }
        is Condition.IfNotNull -> this.condition.reads(list)
        else -> true
    }
}

fun <T> Condition<T>.readPaths(): Set<DataClassPathPartial<T>> {
    val out = HashSet<DataClassPathPartial<T>>()
    emitReadPaths { out.add(it) }
    return out
}

@OptIn(ExperimentalSerializationApi::class)
@Suppress("UNCHECKED_CAST")
fun <T> Condition<T>.emitReadPaths(out: (DataClassPathPartial<T>) -> Unit) = emitReadPaths(DataClassPathSelf<T>(
    NothingSerializer() as KSerializer<T>
)) { out(it as DataClassPathPartial<T>) }

private fun Condition<*>.emitReadPaths(soFar: DataClassPath<*, *>, out: (DataClassPathPartial<*>) -> Unit) {
    @Suppress("UNCHECKED_CAST")
    when (this) {
        is Condition.Always -> {}
        is Condition.Never -> {}
        is Condition.OnField<*, *> -> condition.emitReadPaths(DataClassPathAccess(soFar as DataClassPath<Any?, Any>, key as SerializableProperty<Any, Any?>), out)
        is Condition.Not -> this.condition.emitReadPaths(soFar, out)
        is Condition.And -> this.conditions.forEach { it.emitReadPaths(soFar, out) }
        is Condition.Or -> this.conditions.forEach { it.emitReadPaths(soFar, out) }
        is Condition.IfNotNull<*> -> this.condition.emitReadPaths(DataClassPathNotNull(soFar as DataClassPath<Any?, Any?>), out)
        else -> out(soFar)
    }
    // COND: (a, (b, (c, x)))
    // PATH: (((root, a), b), c)
}

fun <T> Condition<T>.readsResultOf(modification: Modification<T>, tableTextPaths: List<List<SerializableProperty<*, *>>> = listOf()): Boolean {
    return when (this) {
        is Condition.Always -> false
        is Condition.Never -> false
        is Condition.OnField<*, *> -> {
            val field = modification as? Modification.OnField<*, *> ?: return false
            @Suppress("UNCHECKED_CAST")
            field.key == this.key && (this.condition as Condition<Any?>).readsResultOf(field.modification as Modification<Any?>, tableTextPaths.mapNotNull {
                if(it.isEmpty()) null
                else if(it[0] == key) it.drop(1)
                else null
            })
        }

        is Condition.ListAllElements<*> -> @Suppress("UNCHECKED_CAST") (this.condition as Condition<Any?>).readsResultOf(
            (modification as? Modification.ListPerElement<*>)?.modification as? Modification<Any?> ?: return false,
            tableTextPaths
        )

        is Condition.ListAnyElements<*> -> @Suppress("UNCHECKED_CAST") (this.condition as Condition<Any?>).readsResultOf(
            (modification as? Modification.ListPerElement<*>)?.modification as? Modification<Any?> ?: return false,
            tableTextPaths
        )

        is Condition.SetAllElements<*> -> @Suppress("UNCHECKED_CAST") (this.condition as Condition<Any?>).readsResultOf(
            (modification as? Modification.ListPerElement<*>)?.modification as? Modification<Any?> ?: return false,
            tableTextPaths
        )

        is Condition.SetAnyElements<*> -> @Suppress("UNCHECKED_CAST") (this.condition as Condition<Any?>).readsResultOf(
            (modification as? Modification.ListPerElement<*>)?.modification as? Modification<Any?> ?: return false,
            tableTextPaths
        )

        is Condition.And -> this.conditions.any { it.readsResultOf(modification, tableTextPaths) }
        is Condition.Or -> this.conditions.any { it.readsResultOf(modification, tableTextPaths) }
        is Condition.IfNotNull<*> -> {
            @Suppress("UNCHECKED_CAST")
            (this.condition as Condition<Any?>).readsResultOf(
                (modification as? Modification.IfNotNull<Any?>)?.modification ?: modification as Modification<Any?>,
                tableTextPaths
            )
        }

        is Condition.FullTextSearch -> {
            tableTextPaths.any {
                @Suppress("UNCHECKED_CAST")
                it.reversed().fold(Condition.Equal<Any?>(null)) { acc: Condition<Any?>, it: SerializableProperty<*, *> -> Condition.OnField(it as SerializableProperty<Any?, Any?>, acc) }
                    .readsResultOf(modification, listOf())
            }
        }

        is Condition.Not -> {
            this.condition.readsResultOf(modification, tableTextPaths)
        }

        else -> true
    }
}

@Suppress("UNCHECKED_CAST")
fun <T> Condition<T>.guaranteedAfter(modification: Modification<T>): Boolean {
    return when (modification) {
        is Modification.Assign -> this(modification.value)
        is Modification.OnField<*, *> -> {
            val field = this as? Condition.OnField<*, *> ?: return true
            @Suppress("UNCHECKED_CAST")
            field.key != modification.key || (field.condition as Condition<Any?>).guaranteedAfter<Any?>(modification.modification as Modification<Any?>)
        }

        is Modification.SetPerElement<*> -> {
            @Suppress("UNCHECKED_CAST")
            ((this as? Condition.SetAllElements<Any?>)?.condition
                        ?: (this as? Condition.SetAnyElements<Any?>)?.condition)?.guaranteedAfter<Any?>(modification.modification as Modification<Any?>)
                ?: false
        }

        is Modification.ListPerElement<*> -> {
            @Suppress("UNCHECKED_CAST")
            ((this as? Condition.ListAllElements<Any?>)?.condition
                        ?: (this as? Condition.ListAnyElements<Any?>)?.condition)?.guaranteedAfter<Any?>(modification.modification as Modification<Any?>)
                ?: false
        }

        is Modification.Chain -> modification.modifications.all { guaranteedAfter(it) }
        is Modification.IfNotNull<*> -> {
            @Suppress("UNCHECKED_CAST")
            (this as Condition<Any?>).guaranteedAfter<Any?>(modification.modification as Modification<Any?>)
        }

        else -> false
    }
}

@Suppress("UNCHECKED_CAST")
fun <T, V> Modification<T>.map(
    path: DataClassPath<T, V>,
    onModification: (Modification<V>) -> Modification<V>,
): Modification<T> = (this as Modification<Any?>).map<V>(path.properties, onModification) as Modification<T>

@Suppress("UNCHECKED_CAST")
private fun <V> Modification<*>.map(
    list: List<SerializableProperty<*, *>>,
    onModification: (Modification<V>) -> Modification<V>,
): Modification<*> {
    return when (this) {
        is Modification.Chain -> modifications.map { it.map(list, onModification) as Modification<Any?> }
            .let { Modification.Chain(it) }

        is Modification.OnField<*, *> -> if (list.first() == this.key) {
            if (list.size == 1) Modification.OnField(
                key = this.key as SerializableProperty<Any?, Any?>,
                modification = onModification(modification as Modification<V>) as Modification<Any?>
            )
            else this.modification.map(list.drop(1), onModification)
        } else this

        is Modification.SetPerElement<*> -> (this.modification as Modification<Any?>).map(list, onModification)
        is Modification.ListPerElement<*> -> (this.modification as Modification<Any?>).map(list, onModification)
        is Modification.IfNotNull -> (this.modification as Modification<Any?>).map(list, onModification)
        is Modification.Assign -> {
            fun mapValue(
                value: Any?,
                list: List<SerializableProperty<Any?, Any?>>,
                onValue: (V) -> V,
            ): Any? {
                if (value == null) return null
                if (list.isEmpty()) return onValue(value as V)
                return list.first().setCopy(value, mapValue(list.first().get(value), list.drop(1), onValue))
            }
            Modification.Assign(mapValue(value, list as List<SerializableProperty<Any?, Any?>>) {
                (onModification(Modification.Assign(it)) as Modification.Assign).value
            })
        }

        else -> throw Exception("We have no idea what the partial effect is!")
    }
}


fun Condition<*>.walk(action: (Condition<*>)->Unit) {
    action(this)
    when(this) {
        is Condition.And -> this.conditions.forEach { it.walk(action) }
        is Condition.Or -> this.conditions.forEach { it.walk(action) }
        is Condition.Not -> this.condition.walk(action)
        is Condition.ListAllElements<*> -> this.condition.walk(action)
        is Condition.ListAnyElements<*> -> this.condition.walk(action)
        is Condition.SetAllElements<*> -> this.condition.walk(action)
        is Condition.SetAnyElements<*> -> this.condition.walk(action)
        is Condition.OnKey<*> -> this.condition.walk(action)
        is Condition.OnField<*, *> -> this.condition.walk(action)
        is Condition.IfNotNull -> this.condition.walk(action)
        else -> {}
    }
}