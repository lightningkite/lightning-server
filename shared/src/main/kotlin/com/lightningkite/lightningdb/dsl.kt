@file:SharedCode

package com.lightningkite.lightningdb

import com.lightningkite.khrysalis.*
import kotlin.reflect.KProperty1

fun <T : IsCodableAndHashable> startChain(): PropChain<T, T> = PropChain({ it }, { it }, { it }, { _, it -> it })
class PropChain<From : IsCodableAndHashable, To : IsCodableAndHashable>(
    val mapCondition: (Condition<To>) -> Condition<From>,
    val mapModification: (Modification<To>) -> Modification<From>,
    val getProp: (From) -> To,
    val setProp: (From, To) -> From,
) {
    operator fun <V : IsCodableAndHashable> get(prop: KProperty1<To, V>): PropChain<From, V> = PropChain(
        mapCondition = { mapCondition(Condition.OnField(prop, it)) },
        mapModification = { mapModification(Modification.OnField(prop, it)) },
        getProp = { prop.get(getProp(it)) },
        setProp = { from, to -> setProp(from, prop.setCopy(getProp(from), to)) }
    )
    fun <V : IsCodableAndHashable> chain(other: PropChain<To, V>): PropChain<From, V> = PropChain(
        mapCondition = { mapCondition(other.mapCondition(it)) },
        mapModification = { mapModification(other.mapModification(it)) },
        getProp = { other.getProp(getProp(it)) },
        setProp = { from, to -> setProp(from, other.setProp(getProp(from), to)) }
    )

//    override fun hashCode(): Int = mapCondition(Condition.Always()).hashCode()

    override fun toString(): String = "PropChain(${mapCondition(Condition.Always())})"

//    @Suppress("UNCHECKED_CAST")
//    override fun equals(other: Any?): Boolean = other is PropChain<*, *> && mapCondition(Condition.Always()) == (other as PropChain<Any?, Any?>).mapCondition(Condition.Always())
}

inline fun <T : IsCodableAndHashable> condition(setup: (PropChain<T, T>) -> Condition<T>): Condition<T> =
    startChain<T>().let(setup)

val <K : IsCodableAndHashable> PropChain<K, K>.always: Condition<K> get() = Condition.Always<K>()
val <K : IsCodableAndHashable> PropChain<K, K>.never: Condition<K> get() = Condition.Never<K>()

infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, T>.eq(value: T) =
    mapCondition(Condition.Equal(value))

infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, T>.neq(value: T) =
    mapCondition(Condition.NotEqual(value))

infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, T>.ne(value: T) =
    mapCondition(Condition.NotEqual(value))

@JsName("xPropChainInsideSet")
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, T>.inside(values: Set<T>) =
    mapCondition(Condition.Inside(values.toList()))

infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, T>.inside(values: List<T>) =
    mapCondition(Condition.Inside(values))

@JsName("xPropChainNinSet")
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, T>.nin(values: Set<T>) =
    mapCondition(Condition.NotInside(values.toList()))

infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, T>.nin(values: List<T>) =
    mapCondition(Condition.NotInside(values))

@JsName("xPropChainNotInSet")
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, T>.notIn(values: Set<T>) =
    mapCondition(Condition.NotInside(values.toList()))

infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, T>.notIn(values: List<T>) =
    mapCondition(Condition.NotInside(values))

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

fun <K : IsCodableAndHashable, V : IsCodableAndHashable> PropChain<K, V>.fullTextSearch(
    value: String,
    ignoreCase: Boolean,
) =
    mapCondition(Condition.FullTextSearch<V>(value, ignoreCase = ignoreCase))

@JsName("xPropChainListAll")
@JvmName("listAll")
inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.all(condition: (PropChain<T, T>) -> Condition<T>) =
    mapCondition(Condition.ListAllElements(startChain<T>().let(condition)))

@JsName("xPropChainListAny")
@JvmName("listAny")
inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.any(condition: (PropChain<T, T>) -> Condition<T>) =
    mapCondition(Condition.ListAnyElements(startChain<T>().let(condition)))

@JsName("xPropChainListSizedEqual")
@JvmName("listSizedEqual")
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.sizesEquals(count: Int) =
    mapCondition(Condition.ListSizesEquals(count))

@JsName("xPropChainSetAll")
@JvmName("setAll")
inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Set<T>>.all(condition: (PropChain<T, T>) -> Condition<T>) =
    mapCondition(Condition.SetAllElements(startChain<T>().let(condition)))

@JsName("xPropChainSetAny")
@JvmName("setAny")
inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Set<T>>.any(condition: (PropChain<T, T>) -> Condition<T>) =
    mapCondition(Condition.SetAnyElements(startChain<T>().let(condition)))

@JsName("xPropChainSetSizedEqual")
@JvmName("setSizedEqual")
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Set<T>>.sizesEquals(count: Int) =
    mapCondition(Condition.SetSizesEquals(count))

infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Map<String, T>>.containsKey(key: String) =
    mapCondition(Condition.Exists(key))

val <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, T?>.notNull
    get() = PropChain<K, T>(
        mapCondition = { mapCondition(Condition.IfNotNull(it)) },
        mapModification = { mapModification(Modification.IfNotNull(it)) },
        getProp = { getProp(it)!! },
        setProp = { it, value -> setProp(it, value) }
    )

operator fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Map<String, T>>.get(key: String) =
    PropChain<K, T>(
        mapCondition = { mapCondition(Condition.OnKey(key, it)) },
        mapModification = { mapModification(Modification.ModifyByKey(mapOf(key to it))) },
        getProp = { getProp(it)[key]!! },
        setProp = { from, to -> setProp(from, getProp(from).plus(key to to)) }
    )

@JsName("xPropChainListAll")
@get:JvmName("listAll")
inline val <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.all get() =
    PropChain<K, T>(
        mapCondition = { mapCondition(Condition.ListAllElements(it)) },
        mapModification = { mapModification(Modification.ListPerElement(Condition.Always(), it)) },
        getProp = { getProp(it).first() },
        setProp = { from, to -> setProp(from, getProp(from).plus(to)) }
    )

@JsName("xPropChainSetAll")
@get:JvmName("setAll")
inline val <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Set<T>>.all get() =
    PropChain<K, T>(
        mapCondition = { mapCondition(Condition.SetAllElements(it)) },
        mapModification = { mapModification(Modification.SetPerElement(Condition.Always(), it)) },
        getProp = { getProp(it).first() },
        setProp = { from, to -> setProp(from, getProp(from).plus(to)) }
    )

@JsName("xPropChainListAny")
@get:JvmName("listAny")
inline val <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.any get() =
    PropChain<K, T>(
        mapCondition = { mapCondition(Condition.ListAnyElements(it)) },
        mapModification = { mapModification(Modification.ListPerElement(Condition.Always(), it)) },
        getProp = { getProp(it).first() },
        setProp = { from, to -> setProp(from, getProp(from).plus(to)) }
    )

@JsName("xPropChainSetAny")
@get:JvmName("setAny")
inline val <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Set<T>>.any get() =
    PropChain<K, T>(
        mapCondition = { mapCondition(Condition.SetAnyElements(it)) },
        mapModification = { mapModification(Modification.SetPerElement(Condition.Always(), it)) },
        getProp = { getProp(it).first() },
        setProp = { from, to -> setProp(from, getProp(from).plus(to)) }
    )

inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, T>.condition(make: (PropChain<T, T>) -> Condition<T>): Condition<K> =
    mapCondition(make(startChain<T>()))

@CheckReturnValue
@Deprecated("Using this method to create modifications is no longer recommended.  We recommend using the new modification{} builder instead.")
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, T>.assign(value: T) =
    mapModification(Modification.Assign(value))

@CheckReturnValue
@Deprecated("Using this method to create modifications is no longer recommended.  We recommend using the new modification{} builder instead.")
infix fun <K : IsCodableAndHashable, T : Comparable<T>> PropChain<K, T>.coerceAtMost(value: T) =
    mapModification(Modification.CoerceAtMost(value))

@CheckReturnValue
@Deprecated("Using this method to create modifications is no longer recommended.  We recommend using the new modification{} builder instead.")
infix fun <K : IsCodableAndHashable, T : Comparable<T>> PropChain<K, T>.coerceAtLeast(value: T) =
    mapModification(Modification.CoerceAtLeast(value))

@CheckReturnValue
@Deprecated("Using this method to create modifications is no longer recommended.  We recommend using the new modification{} builder instead.")
@JsName("xPropChainPlusNumber")
infix operator fun <K : IsCodableAndHashable, T : Number> PropChain<K, T>.plus(by: T) =
    mapModification(Modification.Increment(by))

@CheckReturnValue
@Deprecated("Using this method to create modifications is no longer recommended.  We recommend using the new modification{} builder instead.")
infix operator fun <K : IsCodableAndHashable, T : Number> PropChain<K, T>.times(by: T) =
    mapModification(Modification.Multiply(by))

@CheckReturnValue
@Deprecated("Using this method to create modifications is no longer recommended.  We recommend using the new modification{} builder instead.")
@JsName("xPropChainPlusString")
infix operator fun <K : IsCodableAndHashable> PropChain<K, String>.plus(value: String) =
    mapModification(Modification.AppendString(value))

@CheckReturnValue
@Deprecated("Using this method to create modifications is no longer recommended.  We recommend using the new modification{} builder instead.")
@JsName("xPropChainPlusItemsList")
infix operator fun <K : IsCodableAndHashable, T> PropChain<K, List<T>>.plus(items: List<T>) =
    mapModification(Modification.ListAppend(items))

@CheckReturnValue
@Deprecated("Using this method to create modifications is no longer recommended.  We recommend using the new modification{} builder instead.")
@JsName("xPropChainPlusItemsSet")
infix operator fun <K : IsCodableAndHashable, T> PropChain<K, Set<T>>.plus(items: Set<T>) =
    mapModification(Modification.SetAppend(items))

@CheckReturnValue
@Deprecated("Using this method to create modifications is no longer recommended.  We recommend using the new modification{} builder instead.")
@JsName("xPropChainPlusItemList")
@JvmName("plusList")
infix operator fun <K : IsCodableAndHashable, T> PropChain<K, List<T>>.plus(item: T) =
    mapModification(Modification.ListAppend(listOf(item)))

@CheckReturnValue
@Deprecated("Using this method to create modifications is no longer recommended.  We recommend using the new modification{} builder instead.")
@JsName("xPropChainPlusItemSet")
@JvmName("plusSet")
infix operator fun <K : IsCodableAndHashable, T> PropChain<K, Set<T>>.plus(item: T) =
    mapModification(Modification.SetAppend(setOf(item)))

@CheckReturnValue
@Deprecated("Using this method to create modifications is no longer recommended.  We recommend using the new modification{} builder instead.")
@JsName("xPropChainListAddAll")
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.addAll(items: List<T>) =
    mapModification(Modification.ListAppend(items))

@CheckReturnValue
@Deprecated("Using this method to create modifications is no longer recommended.  We recommend using the new modification{} builder instead.")
@JsName("xPropChainSetAddAll")
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Set<T>>.addAll(items: Set<T>) =
    mapModification(Modification.SetAppend(items))

@CheckReturnValue
@Deprecated("Using this method to create modifications is no longer recommended.  We recommend using the new modification{} builder instead.")
@JsName("xPropChainListRemove")
@JvmName("listRemoveAll")
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.removeAll(condition: (PropChain<T, T>) -> Condition<T>) =
    mapModification(Modification.ListRemove(startChain<T>().let(condition)))

@CheckReturnValue
@Deprecated("Using this method to create modifications is no longer recommended.  We recommend using the new modification{} builder instead.")
@JsName("xPropChainSetRemove")
@JvmName("setRemoveAll")
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Set<T>>.removeAll(condition: (PropChain<T, T>) -> Condition<T>) =
    mapModification(Modification.SetRemove(startChain<T>().let(condition)))

@CheckReturnValue
@Deprecated("Using this method to create modifications is no longer recommended.  We recommend using the new modification{} builder instead.")
@JsName("xPropChainListRemoveAll")
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.removeAll(items: List<T>) =
    mapModification(Modification.ListRemoveInstances(items))

@CheckReturnValue
@Deprecated("Using this method to create modifications is no longer recommended.  We recommend using the new modification{} builder instead.")
@JsName("xPropChainSetRemoveAll")
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Set<T>>.removeAll(items: Set<T>) =
    mapModification(Modification.SetRemoveInstances(items))

@CheckReturnValue
@Deprecated("Using this method to create modifications is no longer recommended.  We recommend using the new modification{} builder instead.")
@JsName("xPropChainListDropLast")
@JvmName("listDropLast")
fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.dropLast() =
    mapModification(Modification.ListDropLast())

@CheckReturnValue
@Deprecated("Using this method to create modifications is no longer recommended.  We recommend using the new modification{} builder instead.")
@JsName("xPropChainSetDropLast")
@JvmName("setDropLast")
fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Set<T>>.dropLast() =
    mapModification(Modification.SetDropLast())

@CheckReturnValue
@Deprecated("Using this method to create modifications is no longer recommended.  We recommend using the new modification{} builder instead.")
@JsName("xPropChainListDropFirst")
@JvmName("listDropFirst")
fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.dropFirst() =
    mapModification(Modification.ListDropFirst())

@CheckReturnValue
@Deprecated("Using this method to create modifications is no longer recommended.  We recommend using the new modification{} builder instead.")
@JsName("xPropChainSetDropFirst")
@JvmName("setDropFirst")
fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Set<T>>.dropFirst() =
    mapModification(Modification.SetDropFirst())

@CheckReturnValue
@Deprecated("Using this method to create modifications is no longer recommended.  We recommend using the new modification{} builder instead.")
@JsName("xPropChainListMap")
@JvmName("listMap")
inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.map(modification: (PropChain<T, T>) -> Modification<T>) =
    mapModification(Modification.ListPerElement(condition = Condition.Always<T>(), startChain<T>().let(modification)))

@CheckReturnValue
@Deprecated("Using this method to create modifications is no longer recommended.  We recommend using the new modification{} builder instead.")
@JsName("xPropChainSetMap")
@JvmName("setMap")
inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Set<T>>.map(modification: (PropChain<T, T>) -> Modification<T>) =
    mapModification(Modification.SetPerElement(condition = Condition.Always<T>(), startChain<T>().let(modification)))

@CheckReturnValue
@Deprecated("Using this method to create modifications is no longer recommended.  We recommend using the new modification{} builder instead.")
@JsName("xPropChainListMapIf")
@JvmName("listMapIf")
inline fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.mapIf(
    condition: (PropChain<T, T>) -> Condition<T>,
    modification: (PropChain<T, T>) -> Modification<T>,
) = mapModification(
    Modification.ListPerElement(
        condition = startChain<T>().let(condition),
        startChain<T>().let(modification)
    )
)

@CheckReturnValue
@Deprecated("Using this method to create modifications is no longer recommended.  We recommend using the new modification{} builder instead.")
@JsName("xPropChainSetMapIf")
@JvmName("setMapIf")
inline fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Set<T>>.mapIf(
    condition: (PropChain<T, T>) -> Condition<T>,
    modification: (PropChain<T, T>) -> Modification<T>,
) = mapModification(
    Modification.SetPerElement(
        condition = startChain<T>().let(condition),
        startChain<T>().let(modification)
    )
)

@CheckReturnValue
@Deprecated("Using this method to create modifications is no longer recommended.  We recommend using the new modification{} builder instead.")
@JsName("xPropChainPlusMap")
infix operator fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Map<String, T>>.plus(map: Map<String, T>) =
    mapModification(Modification.Combine(map))

@CheckReturnValue
@Deprecated("Using this method to create modifications is no longer recommended.  We recommend using the new modification{} builder instead.")
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Map<String, T>>.modifyByKey(map: Map<String, (PropChain<T, T>) -> Modification<T>>) =
    mapModification(Modification.ModifyByKey(map.mapValues { startChain<T>().let(it.value) }))

@CheckReturnValue
@Deprecated("Using this method to create modifications is no longer recommended.  We recommend using the new modification{} builder instead.")
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Map<String, T>>.removeKeys(fields: Set<String>) =
    mapModification(Modification.RemoveKeys(fields))
