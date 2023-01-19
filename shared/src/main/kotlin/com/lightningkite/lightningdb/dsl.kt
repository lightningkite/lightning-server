@file:SharedCode

package com.lightningkite.lightningdb

import com.lightningkite.khrysalis.*
import java.lang.IllegalStateException
import kotlin.reflect.KProperty1

class PropChain<From : IsCodableAndHashable, To : IsCodableAndHashable>(
    val mapCondition: (Condition<To>) -> Condition<From>,
    val mapModification: (Modification<To>) -> Modification<From>
)

fun <From : IsCodableAndHashable, To : IsCodableAndHashable> KeyPath<From, To>.toPropChain() = PropChain(
    mapCondition = { it: Condition<To> -> mapCondition(it) },
    mapModification = { it: Modification<To> -> mapModification(it) },
)

fun <T : IsCodableAndHashable> startChain(): KeyPath<T, T> = KeyPathSelf()

inline fun <T : IsCodableAndHashable> condition(setup: (KeyPath<T, T>) -> Condition<T>): Condition<T> =
    startChain<T>().let(setup)

inline fun <T : IsCodableAndHashable> modification(setup: (KeyPath<T, T>) -> Modification<T>): Modification<T> =
    startChain<T>().let(setup)

fun <K : IsCodableAndHashable, V : IsCodableAndHashable> KeyPath<K, V>.mapCondition(condition: Condition<V>): Condition<K> {
    @Suppress("UNCHECKED_CAST")
    return when(this) {
        is KeyPathSelf<*> -> condition as Condition<K>
        is KeyPathAccess<*, *, *> -> (first as KeyPath<K, Any?>).mapCondition(Condition.OnField(second as KProperty1<Any?, V>, condition))
        is KeyPathSafeAccess<*, *, *> -> (first as KeyPath<K, Any?>).mapCondition(Condition.IfNotNull((second as KeyPath<Any, V>).mapCondition(condition)))
        else -> fatalError()
    }
}
fun <K : IsCodableAndHashable, V : IsCodableAndHashable> KeyPath<K, V>.mapModification(modification: Modification<V>): Modification<K> {
    @Suppress("UNCHECKED_CAST")
    return when(this) {
        is KeyPathSelf<*> -> modification as Modification<K>
        is KeyPathAccess<*, *, *> -> (first as KeyPath<K, Any?>).mapModification(Modification.OnField(second as KProperty1<Any?, V>, modification))
        is KeyPathSafeAccess<*, *, *> -> (first as KeyPath<K, Any?>).mapModification(Modification.IfNotNull((second as KeyPath<Any, V>).mapModification(modification)))
        else -> fatalError()
    }
}

val <K : IsCodableAndHashable> KeyPath<K, K>.always: Condition<K> get() = Condition.Always<K>()
val <K : IsCodableAndHashable> KeyPath<K, K>.never: Condition<K> get() = Condition.Never<K>()

infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, T>.eq(value: T) = mapCondition(Condition.Equal(value))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, T>.neq(value: T) = mapCondition(Condition.NotEqual(value))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, T>.ne(value: T) = mapCondition(Condition.NotEqual(value))
@JsName("xKeyPathInsideSet") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, T>.inside(values: Set<T>) = mapCondition(Condition.Inside(values.toList()))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, T>.inside(values: List<T>) = mapCondition(Condition.Inside(values))
@JsName("xKeyPathNinSet") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, T>.nin(values: Set<T>) = mapCondition(Condition.NotInside(values.toList()))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, T>.nin(values: List<T>) = mapCondition(Condition.NotInside(values))
@JsName("xKeyPathNotInSet") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, T>.notIn(values: Set<T>) = mapCondition(Condition.NotInside(values.toList()))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, T>.notIn(values: List<T>) = mapCondition(Condition.NotInside(values))
infix fun <K : IsCodableAndHashable, T : ComparableCodableAndHashable<T>> KeyPath<K, T>.gt(value: T) = mapCondition(Condition.GreaterThan(value))
infix fun <K : IsCodableAndHashable, T : ComparableCodableAndHashable<T>> KeyPath<K, T>.lt(value: T) = mapCondition(Condition.LessThan(value))
infix fun <K : IsCodableAndHashable, T : ComparableCodableAndHashable<T>> KeyPath<K, T>.gte(value: T) = mapCondition(Condition.GreaterThanOrEqual(value))
infix fun <K : IsCodableAndHashable, T : ComparableCodableAndHashable<T>> KeyPath<K, T>.lte(value: T) = mapCondition(Condition.LessThanOrEqual(value))
infix fun <K : IsCodableAndHashable> KeyPath<K, Int>.allClear(mask: Int) = mapCondition(Condition.IntBitsClear(mask))
infix fun <K : IsCodableAndHashable> KeyPath<K, Int>.allSet(mask: Int) = mapCondition(Condition.IntBitsSet(mask))
infix fun <K : IsCodableAndHashable> KeyPath<K, Int>.anyClear(mask: Int) = mapCondition(Condition.IntBitsAnyClear(mask))
infix fun <K : IsCodableAndHashable> KeyPath<K, Int>.anySet(mask: Int) = mapCondition(Condition.IntBitsAnySet(mask))
infix fun <K : IsCodableAndHashable> KeyPath<K, String>.contains(value: String) = mapCondition(Condition.StringContains(value, ignoreCase = true))
@JsName("xKeyPathContainsCased") fun <K : IsCodableAndHashable> KeyPath<K, String>.contains(value: String, ignoreCase: Boolean) = mapCondition(Condition.StringContains(value, ignoreCase = ignoreCase))
fun <K : IsCodableAndHashable, V : IsCodableAndHashable> KeyPath<K, V>.fullTextSearch(value: String, ignoreCase: Boolean, ) = mapCondition(Condition.FullTextSearch<V>(value, ignoreCase = ignoreCase))
@JsName("xKeyPathListAll") @JvmName("listAll") inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, List<T>>.all(condition: (KeyPath<T, T>) -> Condition<T>) = mapCondition(Condition.ListAllElements(startChain<T>().let(condition)))
@JsName("xKeyPathListAny") @JvmName("listAny") inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, List<T>>.any(condition: (KeyPath<T, T>) -> Condition<T>) = mapCondition(Condition.ListAnyElements(startChain<T>().let(condition)))
@JsName("xKeyPathListSizedEqual") @JvmName("listSizedEqual") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, List<T>>.sizesEquals(count: Int) = mapCondition(Condition.ListSizesEquals(count))
@JsName("xKeyPathSetAll") @JvmName("setAll") inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, Set<T>>.all(condition: (KeyPath<T, T>) -> Condition<T>) = mapCondition(Condition.SetAllElements(startChain<T>().let(condition)))
@JsName("xKeyPathSetAny") @JvmName("setAny") inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, Set<T>>.any(condition: (KeyPath<T, T>) -> Condition<T>) = mapCondition(Condition.SetAnyElements(startChain<T>().let(condition)))
@JsName("xKeyPathSetSizedEqual") @JvmName("setSizedEqual") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, Set<T>>.sizesEquals(count: Int) = mapCondition(Condition.SetSizesEquals(count))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, Map<String, T>>.containsKey(key: String) = mapCondition(Condition.Exists(key))
inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, T>.condition(make: (KeyPath<T, T>) -> Condition<T>): Condition<K> = mapCondition(make(startChain<T>()))
inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, T>.modification(make: (KeyPath<T, T>) -> Modification<T>): Modification<K> = mapModification(make(startChain<T>()))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, T>.assign(value: T) = mapModification(Modification.Assign(value))
infix fun <K : IsCodableAndHashable, T : Comparable<T>> KeyPath<K, T>.coerceAtMost(value: T) = mapModification(Modification.CoerceAtMost(value))
infix fun <K : IsCodableAndHashable, T : Comparable<T>> KeyPath<K, T>.coerceAtLeast(value: T) = mapModification(Modification.CoerceAtLeast(value))
@JsName("xKeyPathPlusNumber") infix operator fun <K : IsCodableAndHashable, T : Number> KeyPath<K, T>.plus(by: T) = mapModification(Modification.Increment(by))
infix operator fun <K : IsCodableAndHashable, T : Number> KeyPath<K, T>.times(by: T) = mapModification(Modification.Multiply(by))
@JsName("xKeyPathPlusString") infix operator fun <K : IsCodableAndHashable> KeyPath<K, String>.plus(value: String) = mapModification(Modification.AppendString(value))
@JsName("xKeyPathPlusItemsList") infix operator fun <K : IsCodableAndHashable, T> KeyPath<K, List<T>>.plus(items: List<T>) = mapModification(Modification.ListAppend(items))
@JsName("xKeyPathPlusItemsSet") infix operator fun <K : IsCodableAndHashable, T> KeyPath<K, Set<T>>.plus(items: Set<T>) = mapModification(Modification.SetAppend(items))
@JsName("xKeyPathPlusItemList") @JvmName("plusList") infix operator fun <K : IsCodableAndHashable, T> KeyPath<K, List<T>>.plus(item: T) = mapModification(Modification.ListAppend(listOf(item)))
@JsName("xKeyPathPlusItemSet") @JvmName("plusSet") infix operator fun <K : IsCodableAndHashable, T> KeyPath<K, Set<T>>.plus(item: T) = mapModification(Modification.SetAppend(setOf(item)))
@JsName("xKeyPathListAddAll") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, List<T>>.addAll(items: List<T>) = mapModification(Modification.ListAppend(items))
@JsName("xKeyPathSetAddAll") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, Set<T>>.addAll(items: Set<T>) = mapModification(Modification.SetAppend(items))
@JsName("xKeyPathListRemove") @JvmName("listRemoveAll") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, List<T>>.removeAll(condition: (KeyPath<T, T>) -> Condition<T>) = mapModification(Modification.ListRemove(startChain<T>().let(condition)))
@JsName("xKeyPathSetRemove") @JvmName("setRemoveAll") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, Set<T>>.removeAll(condition: (KeyPath<T, T>) -> Condition<T>) = mapModification(Modification.SetRemove(startChain<T>().let(condition)))
@JsName("xKeyPathListRemoveAll") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, List<T>>.removeAll(items: List<T>) = mapModification(Modification.ListRemoveInstances(items))
@JsName("xKeyPathSetRemoveAll") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, Set<T>>.removeAll(items: Set<T>) = mapModification(Modification.SetRemoveInstances(items))
@JsName("xKeyPathListDropLast") @JvmName("listDropLast") fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, List<T>>.dropLast() = mapModification(Modification.ListDropLast())
@JsName("xKeyPathSetDropLast") @JvmName("setDropLast") fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, Set<T>>.dropLast() = mapModification(Modification.SetDropLast())
@JsName("xKeyPathListDropFirst") @JvmName("listDropFirst") fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, List<T>>.dropFirst() = mapModification(Modification.ListDropFirst())
@JsName("xKeyPathSetDropFirst") @JvmName("setDropFirst") fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, Set<T>>.dropFirst() = mapModification(Modification.SetDropFirst())
@JsName("xKeyPathListMap") @JvmName("listMap") inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, List<T>>.map(modification: (KeyPath<T, T>) -> Modification<T>) = mapModification(Modification.ListPerElement(condition = Condition.Always<T>(), startChain<T>().let(modification)))
@JsName("xKeyPathSetMap") @JvmName("setMap") inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, Set<T>>.map(modification: (KeyPath<T, T>) -> Modification<T>) = mapModification(Modification.SetPerElement(condition = Condition.Always<T>(), startChain<T>().let(modification)))
@JsName("xKeyPathListMapIf") @JvmName("listMapIf") inline fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, List<T>>.mapIf(
    condition: (KeyPath<T, T>) -> Condition<T>,
    modification: (KeyPath<T, T>) -> Modification<T>,
) = mapModification(
    Modification.ListPerElement(
        condition = startChain<T>().let(condition),
        startChain<T>().let(modification)
    )
)

@JsName("xKeyPathSetMapIf") @JvmName("setMapIf") inline fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, Set<T>>.mapIf(
    condition: (KeyPath<T, T>) -> Condition<T>,
    modification: (KeyPath<T, T>) -> Modification<T>,
) = mapModification(
    Modification.SetPerElement(
        condition = startChain<T>().let(condition),
        startChain<T>().let(modification)
    )
)

@JsName("xKeyPathPlusMap") infix operator fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, Map<String, T>>.plus(map: Map<String, T>) = mapModification(Modification.Combine(map))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, Map<String, T>>.modifyByKey(map: Map<String, (KeyPath<T, T>) -> Modification<T>>) = mapModification(Modification.ModifyByKey(map.mapValues { startChain<T>().let(it.value) }))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, Map<String, T>>.removeKeys(fields: Set<String>) = mapModification(Modification.RemoveKeys(fields))


//////////////

val <K : IsCodableAndHashable> PropChain<K, K>.always: Condition<K> get() = Condition.Always<K>()
val <K : IsCodableAndHashable> PropChain<K, K>.never: Condition<K> get() = Condition.Never<K>()

infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, T>.eq(value: T) = mapCondition(Condition.Equal(value))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, T>.neq(value: T) = mapCondition(Condition.NotEqual(value))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, T>.ne(value: T) = mapCondition(Condition.NotEqual(value))
@JsName("xPropChainInsideSet") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, T>.inside(values: Set<T>) = mapCondition(Condition.Inside(values.toList()))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, T>.inside(values: List<T>) = mapCondition(Condition.Inside(values))
@JsName("xPropChainNinSet") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, T>.nin(values: Set<T>) = mapCondition(Condition.NotInside(values.toList()))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, T>.nin(values: List<T>) = mapCondition(Condition.NotInside(values))
@JsName("xPropChainNotInSet") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, T>.notIn(values: Set<T>) = mapCondition(Condition.NotInside(values.toList()))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, T>.notIn(values: List<T>) = mapCondition(Condition.NotInside(values))
infix fun <K : IsCodableAndHashable, T : ComparableCodableAndHashable<T>> PropChain<K, T>.gt(value: T) = mapCondition(Condition.GreaterThan(value))
infix fun <K : IsCodableAndHashable, T : ComparableCodableAndHashable<T>> PropChain<K, T>.lt(value: T) = mapCondition(Condition.LessThan(value))
infix fun <K : IsCodableAndHashable, T : ComparableCodableAndHashable<T>> PropChain<K, T>.gte(value: T) = mapCondition(Condition.GreaterThanOrEqual(value))
infix fun <K : IsCodableAndHashable, T : ComparableCodableAndHashable<T>> PropChain<K, T>.lte(value: T) = mapCondition(Condition.LessThanOrEqual(value))
infix fun <K : IsCodableAndHashable> PropChain<K, Int>.allClear(mask: Int) = mapCondition(Condition.IntBitsClear(mask))
infix fun <K : IsCodableAndHashable> PropChain<K, Int>.allSet(mask: Int) = mapCondition(Condition.IntBitsSet(mask))
infix fun <K : IsCodableAndHashable> PropChain<K, Int>.anyClear(mask: Int) = mapCondition(Condition.IntBitsAnyClear(mask))
infix fun <K : IsCodableAndHashable> PropChain<K, Int>.anySet(mask: Int) = mapCondition(Condition.IntBitsAnySet(mask))
infix fun <K : IsCodableAndHashable> PropChain<K, String>.contains(value: String) = mapCondition(Condition.StringContains(value, ignoreCase = true))
@JsName("xPropChainContainsCased") fun <K : IsCodableAndHashable> PropChain<K, String>.contains(value: String, ignoreCase: Boolean) = mapCondition(Condition.StringContains(value, ignoreCase = ignoreCase))
fun <K : IsCodableAndHashable, V : IsCodableAndHashable> PropChain<K, V>.fullTextSearch(value: String, ignoreCase: Boolean, ) = mapCondition(Condition.FullTextSearch<V>(value, ignoreCase = ignoreCase))
@JsName("xPropChainListAll") @JvmName("listAll") inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.all(condition: (KeyPath<T, T>) -> Condition<T>) = mapCondition(Condition.ListAllElements(startChain<T>().let(condition)))
@JsName("xPropChainListAny") @JvmName("listAny") inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.any(condition: (KeyPath<T, T>) -> Condition<T>) = mapCondition(Condition.ListAnyElements(startChain<T>().let(condition)))
@JsName("xPropChainListSizedEqual") @JvmName("listSizedEqual") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.sizesEquals(count: Int) = mapCondition(Condition.ListSizesEquals(count))
@JsName("xPropChainSetAll") @JvmName("setAll") inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Set<T>>.all(condition: (KeyPath<T, T>) -> Condition<T>) = mapCondition(Condition.SetAllElements(startChain<T>().let(condition)))
@JsName("xPropChainSetAny") @JvmName("setAny") inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Set<T>>.any(condition: (KeyPath<T, T>) -> Condition<T>) = mapCondition(Condition.SetAnyElements(startChain<T>().let(condition)))
@JsName("xPropChainSetSizedEqual") @JvmName("setSizedEqual") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Set<T>>.sizesEquals(count: Int) = mapCondition(Condition.SetSizesEquals(count))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Map<String, T>>.containsKey(key: String) = mapCondition(Condition.Exists(key))
inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, T>.condition(make: (KeyPath<T, T>) -> Condition<T>): Condition<K> = mapCondition(make(startChain<T>()))
inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, T>.modification(make: (KeyPath<T, T>) -> Modification<T>): Modification<K> = mapModification(make(startChain<T>()))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, T>.assign(value: T) = mapModification(Modification.Assign(value))
infix fun <K : IsCodableAndHashable, T : Comparable<T>> PropChain<K, T>.coerceAtMost(value: T) = mapModification(Modification.CoerceAtMost(value))
infix fun <K : IsCodableAndHashable, T : Comparable<T>> PropChain<K, T>.coerceAtLeast(value: T) = mapModification(Modification.CoerceAtLeast(value))
@JsName("xPropChainPlusNumber") infix operator fun <K : IsCodableAndHashable, T : Number> PropChain<K, T>.plus(by: T) = mapModification(Modification.Increment(by))
infix operator fun <K : IsCodableAndHashable, T : Number> PropChain<K, T>.times(by: T) = mapModification(Modification.Multiply(by))
@JsName("xPropChainPlusString") infix operator fun <K : IsCodableAndHashable> PropChain<K, String>.plus(value: String) = mapModification(Modification.AppendString(value))
@JsName("xPropChainPlusItemsList") infix operator fun <K : IsCodableAndHashable, T> PropChain<K, List<T>>.plus(items: List<T>) = mapModification(Modification.ListAppend(items))
@JsName("xPropChainPlusItemsSet") infix operator fun <K : IsCodableAndHashable, T> PropChain<K, Set<T>>.plus(items: Set<T>) = mapModification(Modification.SetAppend(items))
@JsName("xPropChainPlusItemList") @JvmName("plusList") infix operator fun <K : IsCodableAndHashable, T> PropChain<K, List<T>>.plus(item: T) = mapModification(Modification.ListAppend(listOf(item)))
@JsName("xPropChainPlusItemSet") @JvmName("plusSet") infix operator fun <K : IsCodableAndHashable, T> PropChain<K, Set<T>>.plus(item: T) = mapModification(Modification.SetAppend(setOf(item)))
@JsName("xPropChainListAddAll") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.addAll(items: List<T>) = mapModification(Modification.ListAppend(items))
@JsName("xPropChainSetAddAll") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Set<T>>.addAll(items: Set<T>) = mapModification(Modification.SetAppend(items))
@JsName("xPropChainListRemove") @JvmName("listRemoveAll") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.removeAll(condition: (KeyPath<T, T>) -> Condition<T>) = mapModification(Modification.ListRemove(startChain<T>().let(condition)))
@JsName("xPropChainSetRemove") @JvmName("setRemoveAll") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Set<T>>.removeAll(condition: (KeyPath<T, T>) -> Condition<T>) = mapModification(Modification.SetRemove(startChain<T>().let(condition)))
@JsName("xPropChainListRemoveAll") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.removeAll(items: List<T>) = mapModification(Modification.ListRemoveInstances(items))
@JsName("xPropChainSetRemoveAll") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Set<T>>.removeAll(items: Set<T>) = mapModification(Modification.SetRemoveInstances(items))
@JsName("xPropChainListDropLast") @JvmName("listDropLast") fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.dropLast() = mapModification(Modification.ListDropLast())
@JsName("xPropChainSetDropLast") @JvmName("setDropLast") fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Set<T>>.dropLast() = mapModification(Modification.SetDropLast())
@JsName("xPropChainListDropFirst") @JvmName("listDropFirst") fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.dropFirst() = mapModification(Modification.ListDropFirst())
@JsName("xPropChainSetDropFirst") @JvmName("setDropFirst") fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Set<T>>.dropFirst() = mapModification(Modification.SetDropFirst())
@JsName("xPropChainListMap") @JvmName("listMap") inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.map(modification: (KeyPath<T, T>) -> Modification<T>) = mapModification(Modification.ListPerElement(condition = Condition.Always<T>(), startChain<T>().let(modification)))
@JsName("xPropChainSetMap") @JvmName("setMap") inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Set<T>>.map(modification: (KeyPath<T, T>) -> Modification<T>) = mapModification(Modification.SetPerElement(condition = Condition.Always<T>(), startChain<T>().let(modification)))
@JsName("xPropChainListMapIf") @JvmName("listMapIf") inline fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.mapIf(
    condition: (KeyPath<T, T>) -> Condition<T>,
    modification: (KeyPath<T, T>) -> Modification<T>,
) = mapModification(
    Modification.ListPerElement(
        condition = startChain<T>().let(condition),
        startChain<T>().let(modification)
    )
)

@JsName("xPropChainSetMapIf") @JvmName("setMapIf") inline fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Set<T>>.mapIf(
    condition: (KeyPath<T, T>) -> Condition<T>,
    modification: (KeyPath<T, T>) -> Modification<T>,
) = mapModification(
    Modification.SetPerElement(
        condition = startChain<T>().let(condition),
        startChain<T>().let(modification)
    )
)

@JsName("xPropChainPlusMap") infix operator fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Map<String, T>>.plus(map: Map<String, T>) = mapModification(Modification.Combine(map))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Map<String, T>>.modifyByKey(map: Map<String, (KeyPath<T, T>) -> Modification<T>>) = mapModification(Modification.ModifyByKey(map.mapValues { startChain<T>().let(it.value) }))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Map<String, T>>.removeKeys(fields: Set<String>) = mapModification(Modification.RemoveKeys(fields))


//////////////


val <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, T?>.notNull
    get() = PropChain<K, T>(
        mapCondition = { mapCondition(Condition.IfNotNull(it)) },
        mapModification = { mapModification(Modification.IfNotNull(it)) }
    )


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

@JsName("xPropChainListAll") @get:JvmName("listAll")
inline val <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.all get() =
    PropChain<K, T>(
        mapCondition = { mapCondition(Condition.ListAllElements(it)) },
        mapModification = { mapModification(Modification.ListPerElement(Condition.Always(), it)) }
    )

@JsName("xPropChainSetAll") @get:JvmName("setAll")
inline val <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Set<T>>.all get() =
    PropChain<K, T>(
        mapCondition = { mapCondition(Condition.SetAllElements(it)) },
        mapModification = { mapModification(Modification.SetPerElement(Condition.Always(), it)) }
    )

@JsName("xPropChainListAny") @get:JvmName("listAny")
inline val <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, List<T>>.any get() =
    PropChain<K, T>(
        mapCondition = { mapCondition(Condition.ListAnyElements(it)) },
        mapModification = { mapModification(Modification.ListPerElement(Condition.Always(), it)) }
    )

@JsName("xPropChainSetAny") @get:JvmName("setAny")
inline val <K : IsCodableAndHashable, T : IsCodableAndHashable> PropChain<K, Set<T>>.any get() =
    PropChain<K, T>(
        mapCondition = { mapCondition(Condition.SetAnyElements(it)) },
        mapModification = { mapModification(Modification.SetPerElement(Condition.Always(), it)) }
    )