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

    /**
     * The `unless` of every mask on what [modification] reads from the stored value to decide what to write: element
     * filters (`forEachIf`), removal conditions (`removeAll`), and whole-element matches (`-=`, a set's `+=`).
     * Their effects show up in parts of the row the user can read, so they reveal masked values as a filter would.
     */
    operator fun invoke(modification: Modification<T>, tableTextPaths: List<List<SerializableProperty<*, *>>> = listOf()): Condition<T> {
        @Suppress("UNCHECKED_CAST")
        val reads = modification.readCondition() as Condition<T>? ?: return Condition.Always
        return invoke(reads, tableTextPaths)
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
            else false
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
operator fun <T> Modification<T>.invoke(map: Partial<T>): Partial<T> =
    (this as Modification<Any?>).applyToPartialEntry(map) as Partial<T>

// A Partial stores every composite field as a nested Partial (see setMap), so a modification reaching one must be applied field by field.
@Suppress("UNCHECKED_CAST")
private fun Modification<Any?>.applyToPartialEntry(entry: Any?): Any? {
    if (entry !is Partial<*>) return this(entry)
    entry as Partial<Any?>
    return when (this) {
        is Modification.OnField<*, *> -> if (entry.parts.containsKey(key)) {
            val newPartial = Partial(entry.parts.toMutableMap())
            newPartial.parts[key as SerializableProperty<Any?, *>] =
                (modification as Modification<Any?>).applyToPartialEntry(entry.parts[key])
            newPartial
        } else entry

        is Modification.Chain -> modifications.fold(entry as Any?) { acc, it -> it.applyToPartialEntry(acc) }
        is Modification.IfNotNull<*> -> (modification as Modification<Any?>).applyToPartialEntry(entry)
        is Modification.Assign -> value?.let { assigned ->
            Partial(entry.parts.mapValuesTo(mutableMapOf()) { (property, part) ->
                property as SerializableProperty<Any?, Any?>
                val fieldValue = property.get(assigned)
                if (part is Partial<*>) Modification.Assign(fieldValue).applyToPartialEntry(part)
                else property.toPartialEntry(fieldValue)
            })
        }

        // E.g. a per-element mask on a list read through an element path: there's no field to apply it to, so hide it all.
        else -> Partial<Any?>()
    }
}

@Suppress("UNCHECKED_CAST")
private fun SerializableProperty<Any?, Any?>.toPartialEntry(value: Any?): Any? {
    val inner = (serializer.nullElement() ?: serializer) as KSerializer<Any>
    val properties = inner.serializableProperties ?: return value
    if (value == null) return null
    return partialOf(value, properties.map { DataClassPathAccess(DataClassPathSelf(inner), it as SerializableProperty<Any, Any?>) })
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
// Paths overlap when one is a prefix of the other: writing `a` rewrites `a.b`, and writing `a.b` changes `a`.
private fun Modification<*>.affects(list: List<SerializableProperty<*, *>>): Boolean =
    affectsPaths().any { it.take(list.size) == list.take(it.size) }

/**
 * The paths this modification writes.  Anything other than navigation into a field, element or non-null value
 * rewrites the whole value it sits on, so e.g. `items += x` writes `[items]` and a root [Modification.Assign]
 * writes `[]`.  Like [DataClassPath.properties], paths don't record nullability or list/set elements.
 */
fun Modification<*>.affectsPaths(): List<List<SerializableProperty<*, *>>> =
    when (this) {
        is Modification.OnField<*, *> -> modification.affectsPaths().map { listOf(key) + it }
        is Modification.SetPerElement<*> -> this.modification.affectsPaths()
        is Modification.ListPerElement<*> -> this.modification.affectsPaths()
        is Modification.Chain -> this.modifications.flatMap { it.affectsPaths() }
        is Modification.IfNotNull -> this.modification.affectsPaths()
        is Modification.Nothing -> emptyList()
        else -> listOf(emptyList())
    }

// A condition reading what this modification reads from the stored value to decide what it writes, or null if it reads
// nothing.  Increments, appends and the like only feed a value into itself, so they read nothing here.
@Suppress("UNCHECKED_CAST")
private fun Modification<*>.readCondition(): Condition<*>? {
    fun Condition<*>.unlessConstant() = takeUnless { it is Condition.Always || it is Condition.Never }
    fun allOf(conditions: List<Condition<*>>) = conditions.takeIf { it.isNotEmpty() }?.let { Condition.And(it as List<Condition<Any?>>) }
    return when (this) {
        is Modification.OnField<*, *> -> modification.readCondition()
            ?.let { Condition.OnField(key as SerializableProperty<Any?, Any?>, it as Condition<Any?>) }
        is Modification.Chain -> allOf(modifications.mapNotNull { it.readCondition() })
        is Modification.IfNotNull<*> -> modification.readCondition()?.let { Condition.IfNotNull(it as Condition<Any?>) }
        is Modification.ModifyByKey<*> -> allOf(map.mapNotNull { (key, it) ->
            it.readCondition()?.let { Condition.OnKey(key, it as Condition<Any?>) }
        })
        is Modification.ListPerElement<*> -> allOf(listOfNotNull(condition.unlessConstant(), modification.readCondition()))
            ?.let { Condition.ListAnyElements(it) }
        // A set drops elements that become equal, which compares whole elements; a constant under an element check
        // reads the whole element (see readsResultOf).
        is Modification.SetPerElement<*> -> Condition.SetAllElements(Condition.Always)
        is Modification.ListRemove<*> -> condition.unlessConstant()?.let { Condition.ListAnyElements(it as Condition<Any?>) }
        is Modification.SetRemove<*> -> condition.unlessConstant()?.let { Condition.SetAnyElements(it as Condition<Any?>) }
        is Modification.ListRemoveInstances<*> -> Condition.ListAnyElements(Condition.Inside(items))
        is Modification.SetRemoveInstances<*> -> Condition.SetAnyElements(Condition.Inside(items.toList()))
        is Modification.SetAppend<*> -> Condition.SetAnyElements(Condition.Inside(items.toList()))
        else -> null
    }
}

fun Condition<*>.reads(path: DataClassPathPartial<*>): Boolean = reads(path.properties)
private fun Condition<*>.reads(list: List<SerializableProperty<*, *>>): Boolean {
    return when (this) {
        is Condition.OnField<*, *> -> if (list.first() == this.key) {
            if (list.size == 1) true
            else this.condition.reads(list.drop(1))
        } else false

        is Condition.Not -> this.condition.reads(list)
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

/**
 * Whether filtering by this condition could reveal anything [modification] changes, i.e. whether any path this
 * condition reads overlaps a path the modification writes.  Anything not understood counts as a read of the whole
 * value it sits on, so unknown shapes err towards "reads it".
 *
 * [tableTextPaths] are the fields a [Condition.FullTextSearch] searches, at any depth; without them, a search reads everything.
 */
fun <T> Condition<T>.readsResultOf(
    modification: Modification<T>,
    tableTextPaths: List<List<SerializableProperty<*, *>>> = listOf(),
): Boolean = readsResultOf(modification, listOf(), tableTextPaths)

private fun Condition<*>.readsResultOf(
    modification: Modification<*>,
    at: List<SerializableProperty<*, *>>,
    tableTextPaths: List<List<SerializableProperty<*, *>>>,
): Boolean = when (this) {
    // Below the root, a constant still sits under a null or element check that reads the value, e.g. `x.notNull.always`.
    is Condition.Always -> at.isNotEmpty() && modification.affects(at)
    is Condition.Never -> at.isNotEmpty() && modification.affects(at)
    is Condition.And -> conditions.any { it.readsResultOf(modification, at, tableTextPaths) }
    is Condition.Or -> conditions.any { it.readsResultOf(modification, at, tableTextPaths) }
    is Condition.Not -> condition.readsResultOf(modification, at, tableTextPaths)
    is Condition.OnField<*, *> -> condition.readsResultOf(modification, at + key, tableTextPaths)
    // Paths don't record nullability or list/set elements, so these read the same path as their inner condition.
    // A mask only changes whether the value is null or has elements by replacing it, which overlaps every inner read.
    is Condition.IfNotNull<*> -> condition.readsResultOf(modification, at, tableTextPaths)
    is Condition.ListAllElements<*> -> condition.readsResultOf(modification, at, tableTextPaths)
    is Condition.ListAnyElements<*> -> condition.readsResultOf(modification, at, tableTextPaths)
    is Condition.SetAllElements<*> -> condition.readsResultOf(modification, at, tableTextPaths)
    is Condition.SetAnyElements<*> -> condition.readsResultOf(modification, at, tableTextPaths)
    // Wherever a search sits, MongoDB searches the model's whole text index ($text and Atlas $search are query-wide),
    // while in memory a nested search reads the value it sits on.
    is Condition.FullTextSearch<*> -> (at.isNotEmpty() && modification.affects(at)) ||
            if (tableTextPaths.isEmpty()) modification.affects(listOf()) else tableTextPaths.any { modification.affects(it) }

    else -> modification.affects(at)
}

/**
 * A condition the row must meet before [modification] for this condition to hold after it, checked as part of the
 * write's filter: [Condition.Always] when the modification makes it hold whatever the row was (e.g. it assigns a value
 * that passes), [Condition.Never] when it can't tell, and otherwise the parts of this condition the modification
 * leaves as they were (plus, for writes under `notNull`, that the value is non-null).
 */
@Suppress("UNCHECKED_CAST")
fun <T> Condition<T>.requiredBefore(modification: Modification<T>): Condition<T> =
    requiredBeforeUntyped(modification) as Condition<T>

/** Whether this condition is sure to hold after [modification], given that it held before; see [requiredBefore]. */
fun <T> Condition<T>.guaranteedAfter(modification: Modification<T>): Boolean =
    requiredBefore(modification) != Condition.Never

@Suppress("UNCHECKED_CAST")
private fun Condition<*>.requiredBeforeUntyped(modification: Modification<*>): Condition<*> {
    fun allOf(conditions: List<Condition<*>>): Condition<*> {
        if (conditions.any { it == Condition.Never }) return Condition.Never
        val needed = conditions.filter { it != Condition.Always }.distinct()
        return when (needed.size) {
            0 -> Condition.Always
            1 -> needed[0]
            else -> Condition.And(needed as List<Condition<Any?>>)
        }
    }

    if (this is Condition.Always || this is Condition.Never) return this
    if (modification is Modification.Assign) {
        return if ((this as Condition<Any?>)(modification.value)) Condition.Always else Condition.Never
    }
    if (this is Condition.And) return allOf(conditions.map { it.requiredBeforeUntyped(modification) })
    if (modification is Modification.Chain) {
        return modification.modifications.foldRight(this as Condition<*>) { step, neededAfterStep ->
            neededAfterStep.requiredBeforeUntyped(step)
        }
    }
    // Only an assign can make a value null, and one under notNull has a non-null type.
    // `eq null` gets no such rule: a write inside a non-null value it forbids would change what it forbids.
    if (!readsResultOf(modification, listOf(), listOf()) || this == Condition.NotEqual(null)) return this
    // An Or can't be split: the part that held before may be the one the modification breaks.
    return when (modification) {
        is Modification.OnField<*, *> -> when {
            this !is Condition.OnField<*, *> -> Condition.Never
            key != modification.key -> this
            else -> when (val needed = condition.requiredBeforeUntyped(modification.modification)) {
                Condition.Always, Condition.Never -> needed
                else -> Condition.OnField(key as SerializableProperty<Any?, Any?>, needed as Condition<Any?>)
            }
        }

        // On a null value the write does nothing and the condition fails either way.
        is Modification.IfNotNull<*> ->
            if (this is Condition.IfNotNull<*>) when (val needed = condition.requiredBeforeUntyped(modification.modification)) {
                Condition.Always -> Condition.NotEqual(null)
                Condition.Never -> Condition.Never
                else -> Condition.IfNotNull(needed as Condition<Any?>)
            } else if (requiredBeforeUntyped(modification.modification) == Condition.Always) this
            else Condition.Never

        // Elements the filter skips keep their old values; an element that held keeps holding if writing it can't break it.
        is Modification.ListPerElement<*> -> when (this) {
            is Condition.ListAllElements<*> -> {
                val needed = condition.requiredBeforeUntyped(modification.modification)
                when (val perElement = if (modification.condition == Condition.Always) needed else allOf(listOf(condition, needed))) {
                    Condition.Always, Condition.Never -> perElement
                    else -> Condition.ListAllElements(perElement as Condition<Any?>)
                }
            }
            is Condition.ListAnyElements<*> ->
                if (condition.requiredBeforeUntyped(modification.modification) == Condition.Always) this else Condition.Never
            else -> Condition.Never
        }

        is Modification.SetPerElement<*> -> when (this) {
            is Condition.SetAllElements<*> -> {
                val needed = condition.requiredBeforeUntyped(modification.modification)
                when (val perElement = if (modification.condition == Condition.Always) needed else allOf(listOf(condition, needed))) {
                    Condition.Always, Condition.Never -> perElement
                    else -> Condition.SetAllElements(perElement as Condition<Any?>)
                }
            }
            is Condition.SetAnyElements<*> ->
                if (condition.requiredBeforeUntyped(modification.modification) == Condition.Always) this else Condition.Never
            else -> Condition.Never
        }

        else -> Condition.Never
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