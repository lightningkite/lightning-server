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
infix fun <K: IsCodableAndHashable, T: IsCodableAndHashable> PropChain<K, T>.inside(values: List<T>) = mapCondition(Condition.Inside(values))
infix fun <K: IsCodableAndHashable, T: IsCodableAndHashable> PropChain<K, T>.nin(values: List<T>) = mapCondition(Condition.NotInside(values))
infix fun <K: IsCodableAndHashable, T: IsCodableAndHashable> PropChain<K, T>.notIn(values: List<T>) = mapCondition(Condition.NotInside(values))
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

@JvmName("listAll")
inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.all(condition: (PropChain<T, T>) -> Condition<T>) =
    mapCondition(Condition.ListAllElements(startChain<T>().let(condition)))

@JvmName("listAny")
inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.any(condition: (PropChain<T, T>) -> Condition<T>) =
    mapCondition(Condition.ListAnyElements(startChain<T>().let(condition)))

@JvmName("listSizedEqual")
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.sizesEquals(count: Int) =
    mapCondition(Condition.ListSizesEquals(count))

@JvmName("setAll")
inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Set<T>>.all(condition: (PropChain<T, T>) -> Condition<T>) =
    mapCondition(Condition.SetAllElements(startChain<T>().let(condition)))

@JvmName("setAny")
inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Set<T>>.any(condition: (PropChain<T, T>) -> Condition<T>) =
    mapCondition(Condition.SetAnyElements(startChain<T>().let(condition)))

@JvmName("setSizedEqual")
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Set<T>>.sizesEquals(count: Int) =
    mapCondition(Condition.SetSizesEquals(count))

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

@JsName("xPropChainPlusItemsList")
infix operator fun <K : IsCodableAndHashable, T> PropChain<K, List<T>>.plus(items: List<T>) =
    mapModification(Modification.AppendList(items))

@JsName("xPropChainPlusItemsSet")
infix operator fun <K : IsCodableAndHashable, T> PropChain<K, Set<T>>.plus(items: Set<T>) =
    mapModification(Modification.AppendSet(items))

@JsName("xPropChainPlusItemList")
@JvmName("plusList")
infix operator fun <K : IsCodableAndHashable, T> PropChain<K, List<T>>.plus(item: T) =
    mapModification(Modification.AppendList(listOf(item)))

@JsName("xPropChainPlusItemSet")
@JvmName("plusSet")
infix operator fun <K : IsCodableAndHashable, T> PropChain<K, Set<T>>.plus(item: T) =
    mapModification(Modification.AppendSet(setOf(item)))

infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.addAll(items: List<T>) =
    mapModification(Modification.AppendList(items))

infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Set<T>>.addAll(items: Set<T>) =
    mapModification(Modification.AppendSet(items))

@JvmName("listRemoveAll")
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.removeAll(condition: (PropChain<T, T>) -> Condition<T>) =
    mapModification(Modification.RemoveList(startChain<T>().let(condition)))

@JvmName("setRemoveAll")
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Set<T>>.removeAll(condition: (PropChain<T, T>) -> Condition<T>) =
    mapModification(Modification.RemoveSet(startChain<T>().let(condition)))

@JsName("xPropChainRemoveList")
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.removeAll(items: List<T>) =
    mapModification(Modification.ListRemoveInstances(items))

@JsName("xPropChainRemoveSet")
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Set<T>>.removeAll(items: Set<T>) =
    mapModification(Modification.SetRemoveInstances(items))

@JvmName("listDropLast")
fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.dropLast() =
    mapModification(Modification.ListDropLast())

@JvmName("setDropLast")
fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Set<T>>.dropLast() =
    mapModification(Modification.SetDropLast())

@JvmName("listDropFirst")
fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.dropFirst() =
    mapModification(Modification.ListDropFirst())

@JvmName("setDropFirst")
fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Set<T>>.dropFirst() =
    mapModification(Modification.SetDropFirst())

@JvmName("listMap")
inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.map(modification: (PropChain<T, T>) -> Modification<T>) =
    mapModification(Modification.ListPerElement(condition = Condition.Always(), startChain<T>().let(modification)))

@JvmName("setMap")
inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Set<T>>.map(modification: (PropChain<T, T>) -> Modification<T>) =
    mapModification(Modification.SetPerElement(condition = Condition.Always(), startChain<T>().let(modification)))

@JvmName("listMapIf")
inline fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.mapIf(
    condition: (PropChain<T, T>) -> Condition<T>,
    modification: (PropChain<T, T>) -> Modification<T>
) = mapModification(
    Modification.ListPerElement(
        condition = startChain<T>().let(condition),
        startChain<T>().let(modification)
    )
)

@JvmName("setMapIf")
inline fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Set<T>>.mapIf(
    condition: (PropChain<T, T>) -> Condition<T>,
    modification: (PropChain<T, T>) -> Modification<T>
) = mapModification(
    Modification.SetPerElement(
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
