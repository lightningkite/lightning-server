package com.lightningkite.lightningdb

import com.lightningkite.lightningdb.SerializableProperty
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.NothingSerializer

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

fun <T> Condition<T>.readsResultOf(modification: Modification<T>): Boolean {
    return when (this) {
        is Condition.Always -> false
        is Condition.Never -> false
        is Condition.OnField<*, *> -> {
            val field = modification as? Modification.OnField<*, *> ?: return false
            @Suppress("UNCHECKED_CAST")
            field.key == this.key && (this.condition as Condition<Any?>).readsResultOf(field.modification as Modification<Any?>)
        }

        is Condition.ListAllElements<*> -> @Suppress("UNCHECKED_CAST") (this.condition as Condition<Any?>).readsResultOf(
            (modification as? Modification.ListPerElement<*>)?.modification as? Modification<Any?> ?: return false
        )

        is Condition.ListAnyElements<*> -> @Suppress("UNCHECKED_CAST") (this.condition as Condition<Any?>).readsResultOf(
            (modification as? Modification.ListPerElement<*>)?.modification as? Modification<Any?> ?: return false
        )

        is Condition.SetAllElements<*> -> @Suppress("UNCHECKED_CAST") (this.condition as Condition<Any?>).readsResultOf(
            (modification as? Modification.ListPerElement<*>)?.modification as? Modification<Any?> ?: return false
        )

        is Condition.SetAnyElements<*> -> @Suppress("UNCHECKED_CAST") (this.condition as Condition<Any?>).readsResultOf(
            (modification as? Modification.ListPerElement<*>)?.modification as? Modification<Any?> ?: return false
        )

        is Condition.And -> this.conditions.any { it.readsResultOf(modification) }
        is Condition.Or -> this.conditions.any { it.readsResultOf(modification) }
        is Condition.IfNotNull<*> -> {
            @Suppress("UNCHECKED_CAST")
            (this.condition as Condition<Any?>).readsResultOf(
                (modification as? Modification.IfNotNull<Any?>)?.modification ?: modification as Modification<Any?>
            )
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