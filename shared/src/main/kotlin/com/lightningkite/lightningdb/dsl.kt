@file:SharedCode
package com.lightningkite.lightningdb

import com.lightningkite.khrysalis.*
import kotlin.reflect.KProperty1

fun <T: IsCodableAndHashable> startChain(): PropChain<T, T> = PropChain({it}, {it})
class PropChain<From: IsCodableAndHashable, To: IsCodableAndHashable>(
    val mapCondition: (Condition<To>)->Condition<From>,
    val mapModification: (Modification<To>)-> Modification<From>
) {
    operator fun <V : IsCodableAndHashable> get(prop: KProperty1<To, V>): PropChain<From, V> = PropChain(
        mapCondition = { mapCondition(Condition.OnField(prop, it)) },
        mapModification = { mapModification(Modification.OnField(prop, it)) }
    )

//    override fun hashCode(): Int = mapCondition(Condition.Always()).hashCode()

    override fun toString(): String = "PropChain(${mapCondition(Condition.Always())})"

//    @Suppress("UNCHECKED_CAST")
//    override fun equals(other: Any?): Boolean = other is PropChain<*, *> && mapCondition(Condition.Always()) == (other as PropChain<Any?, Any?>).mapCondition(Condition.Always())
}

inline fun <T: IsCodableAndHashable> condition(setup: (PropChain<T, T>) -> Condition<T>): Condition<T> = startChain<T>().let(setup)
inline fun <T: IsCodableAndHashable> modification(setup: (PropChain<T, T>) -> Modification<T>): Modification<T> = startChain<T>().let(setup)

val <K: IsCodableAndHashable> PropChain<K, K>.always: Condition<K> get() = Condition.Always<K>()
val <K: IsCodableAndHashable> PropChain<K, K>.never: Condition<K> get() = Condition.Never<K>()

infix fun <K: IsCodableAndHashable, T: IsCodableAndHashable> PropChain<K, T>.eq(value: T) = mapCondition(Condition.Equal(value))
infix fun <K: IsCodableAndHashable, T: IsCodableAndHashable> PropChain<K, T>.neq(value: T) = mapCondition(Condition.NotEqual(value))
infix fun <K: IsCodableAndHashable, T: IsCodableAndHashable> PropChain<K, T>.ne(value: T) = mapCondition(Condition.NotEqual(value))
infix fun <K: IsCodableAndHashable, T: IsCodableAndHashable> PropChain<K, T>.inside(values: List<T>) = mapCondition(Condition.ListInside(values))
infix fun <K: IsCodableAndHashable, T: IsCodableAndHashable> PropChain<K, T>.nin(values: List<T>) = mapCondition(Condition.ListNotInside(values))
infix fun <K: IsCodableAndHashable, T: IsCodableAndHashable> PropChain<K, T>.notIn(values: List<T>) = mapCondition(Condition.ListNotInside(values))
infix fun <K: IsCodableAndHashable, T: IsCodableAndHashable> PropChain<K, T>.inside(values: Set<T>) = mapCondition(Condition.SetInside(values))
infix fun <K: IsCodableAndHashable, T: IsCodableAndHashable> PropChain<K, T>.nin(values: Set<T>) = mapCondition(Condition.SetNotInside(values))
infix fun <K: IsCodableAndHashable, T: IsCodableAndHashable> PropChain<K, T>.notIn(values: Set<T>) = mapCondition(Condition.SetNotInside(values))
infix fun <K : IsCodableAndHashable, T : ComparableCodableAndHashable<T>> PropChain<K, T>.gt(value: T) =
    mapCondition(Condition.GreaterThan(value))

infix fun <K : IsCodableAndHashable, T : ComparableCodableAndHashable<T>> PropChain<K, T>.lt(value: T) =
    mapCondition(Condition.LessThan(value))

infix fun <K : IsCodableAndHashable, T : ComparableCodableAndHashable<T>> PropChain<K, T>.gte(value: T) =
    mapCondition(Condition.GreaterThanOrEqual(value))

infix fun <K : IsCodableAndHashable, T : ComparableCodableAndHashable<T>> PropChain<K, T>.lte(value: T) =
    mapCondition(Condition.LessThanOrEqual(value))

infix fun <K : IsCodableAndHashable> PropChain<K, Int>.allClear(mask: Int) = mapCondition(Condition.IntBitsClear(mask))
infix fun <K : IsCodableAndHashable> PropChain<K, Int>.allSet(mask: Int) = mapCondition(Condition.IntBitsSet(mask))
infix fun <K : IsCodableAndHashable> PropChain<K, Int>.anyClear(mask: Int) =
    mapCondition(Condition.IntBitsAnyClear(mask))

infix fun <K : IsCodableAndHashable> PropChain<K, Int>.anySet(mask: Int) = mapCondition(Condition.IntBitsAnySet(mask))
infix fun <K : IsCodableAndHashable> PropChain<K, String>.contains(value: String) =
    mapCondition(Condition.StringContains(value, ignoreCase = true))

@JsName("xPropChainContainsCased")
fun <K : IsCodableAndHashable> PropChain<K, String>.contains(value: String, ignoreCase: Boolean) =
    mapCondition(Condition.StringContains(value, ignoreCase = ignoreCase))

fun <K : IsCodableAndHashable, V: IsCodableAndHashable> PropChain<K, V>.fullTextSearch(value: String, ignoreCase: Boolean) =
    mapCondition(Condition.FullTextSearch<V>(value, ignoreCase = ignoreCase))

inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.all(condition: (PropChain<T, T>) -> Condition<T>) =
    mapCondition(Condition.AllElements(startChain<T>().let(condition)))

inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.any(condition: (PropChain<T, T>) -> Condition<T>) =
    mapCondition(Condition.AnyElements(startChain<T>().let(condition)))

infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.sizesEquals(count: Int) =
    mapCondition(Condition.SizesEquals(count))

inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Set<T>>.setAll(condition: (PropChain<T, T>) -> Condition<T>) =
    mapCondition(Condition.AllElements(startChain<T>().let(condition)))

inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Set<T>>.setAny(condition: (PropChain<T, T>) -> Condition<T>) =
    mapCondition(Condition.AnyElements(startChain<T>().let(condition)))

infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Set<T>>.setSizesEquals(count: Int) =
    mapCondition(Condition.SizesEquals(count))

infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Map<String, T>>.containsKey(key: String) =
    mapCondition(Condition.Exists(key))

val <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, T?>.notNull
    get() = PropChain<K, T>(
        mapCondition = { mapCondition(Condition.IfNotNull(it)) },
        mapModification = { mapModification(Modification.IfNotNull(it)) }
    )

operator fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Map<String, T>>.get(key: String) =
    PropChain<K, T>(
        mapCondition = { mapCondition(Condition.OnKey(key, it)) },
        mapModification = { mapModification(Modification.ModifyByKey(mapOf(key to it))) }
    )

inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, T>.condition(make: (PropChain<T, T>) -> Condition<T>): Condition<K> =
    mapCondition(make(startChain<T>()))

inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, T>.modification(make: (PropChain<T, T>) -> Modification<T>): Modification<K> =
    mapModification(make(startChain<T>()))

infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, T>.assign(value: T) =
    mapModification(Modification.Assign(value))

infix fun <K : IsCodableAndHashable, T : Comparable<T>> PropChain<K, T>.coerceAtMost(value: T) =
    mapModification(Modification.CoerceAtMost(value))

infix fun <K : IsCodableAndHashable, T : Comparable<T>> PropChain<K, T>.coerceAtLeast(value: T) =
    mapModification(Modification.CoerceAtLeast(value))

@JsName("xPropChainPlusNumber")
infix operator fun <K : IsCodableAndHashable, T : Number> PropChain<K, T>.plus(by: T) =
    mapModification(Modification.Increment(by))

infix operator fun <K : IsCodableAndHashable, T : Number> PropChain<K, T>.times(by: T) =
    mapModification(Modification.Multiply(by))

@JsName("xPropChainPlusString")
infix operator fun <K : IsCodableAndHashable> PropChain<K, String>.plus(value: String) =
    mapModification(Modification.AppendString(value))

@JsName("xPropChainPlusItems")
infix operator fun <K : IsCodableAndHashable, T> PropChain<K, List<T>>.plus(items: List<T>) =
    mapModification(Modification.AppendList(items))

@JsName("xPropChainPlusItem")
infix operator fun <K : IsCodableAndHashable, T> PropChain<K, List<T>>.plus(item: T) =
    mapModification(Modification.AppendList(listOf(item)))

infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.addAll(items: List<T>) =
    mapModification(Modification.AppendList(items))

infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.addUnique(items: List<T>) =
    mapModification(Modification.AppendSet(items))

infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.removeAll(condition: (PropChain<T, T>) -> Condition<T>) =
    mapModification(Modification.Remove(startChain<T>().let(condition)))

@JsName("xPropChainRemoveList")
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.removeAll(items: List<T>) =
    mapModification(Modification.RemoveInstances(items))

fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.dropLast() =
    mapModification(Modification.DropLast())

fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.dropFirst() =
    mapModification(Modification.DropFirst())

inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.map(modification: (PropChain<T, T>) -> Modification<T>) =
    mapModification(Modification.PerElement(condition = Condition.Always(), startChain<T>().let(modification)))

inline fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.mapIf(
    condition: (PropChain<T, T>) -> Condition<T>,
    modification: (PropChain<T, T>) -> Modification<T>
) = mapModification(
    Modification.PerElement(
        condition = startChain<T>().let(condition),
        startChain<T>().let(modification)
    )
)

@JsName("xPropChainPlusMap")
infix operator fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Map<String, T>>.plus(map: Map<String, T>) =
    mapModification(Modification.Combine(map))

infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Map<String, T>>.modifyByKey(map: Map<String, (PropChain<T, T>) -> Modification<T>>) =
    mapModification(Modification.ModifyByKey(map.mapValues { startChain<T>().let(it.value) }))

infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Map<String, T>>.removeKeys(fields: Set<String>) =
    mapModification(Modification.RemoveKeys(fields))
