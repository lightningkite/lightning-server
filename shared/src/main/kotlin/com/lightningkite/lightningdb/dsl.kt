@file:SharedCode

package com.lightningkite.lightningdb

import com.lightningkite.khrysalis.*
import kotlin.reflect.KProperty1

class CMBuilder<From : IsCodableAndHashable, To : IsCodableAndHashable>(
    val mapCondition: (Condition<To>) -> Condition<From>,
    val mapModification: (Modification<To>) -> Modification<From>
)

fun <From : IsCodableAndHashable, To : IsCodableAndHashable> KeyPath<From, To>.toCMBuilder() = CMBuilder(
    mapCondition = { it: Condition<To> -> mapCondition(it) },
    mapModification = { it: Modification<To> -> mapModification(it) },
)

fun <T : IsCodableAndHashable> path(): KeyPath<T, T> = KeyPathSelf()

inline fun <T : IsCodableAndHashable> condition(setup: (KeyPath<T, T>) -> Condition<T>): Condition<T> =
    path<T>().let(setup)

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
@JsName("xKeyPathListAll") @JvmName("listAll") inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, List<T>>.all(condition: (KeyPath<T, T>) -> Condition<T>) = mapCondition(Condition.ListAllElements(path<T>().let(condition)))
@JsName("xKeyPathListAny") @JvmName("listAny") inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, List<T>>.any(condition: (KeyPath<T, T>) -> Condition<T>) = mapCondition(Condition.ListAnyElements(path<T>().let(condition)))
@JsName("xKeyPathListSizedEqual") @JvmName("listSizedEqual") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, List<T>>.sizesEquals(count: Int) = mapCondition(Condition.ListSizesEquals(count))
@JsName("xKeyPathSetAll") @JvmName("setAll") inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, Set<T>>.all(condition: (KeyPath<T, T>) -> Condition<T>) = mapCondition(Condition.SetAllElements(path<T>().let(condition)))
@JsName("xKeyPathSetAny") @JvmName("setAny") inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, Set<T>>.any(condition: (KeyPath<T, T>) -> Condition<T>) = mapCondition(Condition.SetAnyElements(path<T>().let(condition)))
@JsName("xKeyPathSetSizedEqual") @JvmName("setSizedEqual") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, Set<T>>.sizesEquals(count: Int) = mapCondition(Condition.SetSizesEquals(count))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, Map<String, T>>.containsKey(key: String) = mapCondition(Condition.Exists(key))
inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, T>.condition(make: (KeyPath<T, T>) -> Condition<T>): Condition<K> = mapCondition(make(path<T>()))
inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, T>.modification(make: (KeyPath<T, T>) -> Modification<T>): Modification<K> = mapModification(make(path<T>()))
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
@JsName("xKeyPathListRemove") @JvmName("listRemoveAll") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, List<T>>.removeAll(condition: (KeyPath<T, T>) -> Condition<T>) = mapModification(Modification.ListRemove(path<T>().let(condition)))
@JsName("xKeyPathSetRemove") @JvmName("setRemoveAll") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, Set<T>>.removeAll(condition: (KeyPath<T, T>) -> Condition<T>) = mapModification(Modification.SetRemove(path<T>().let(condition)))
@JsName("xKeyPathListRemoveAll") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, List<T>>.removeAll(items: List<T>) = mapModification(Modification.ListRemoveInstances(items))
@JsName("xKeyPathSetRemoveAll") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, Set<T>>.removeAll(items: Set<T>) = mapModification(Modification.SetRemoveInstances(items))
@JsName("xKeyPathListDropLast") @JvmName("listDropLast") fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, List<T>>.dropLast() = mapModification(Modification.ListDropLast())
@JsName("xKeyPathSetDropLast") @JvmName("setDropLast") fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, Set<T>>.dropLast() = mapModification(Modification.SetDropLast())
@JsName("xKeyPathListDropFirst") @JvmName("listDropFirst") fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, List<T>>.dropFirst() = mapModification(Modification.ListDropFirst())
@JsName("xKeyPathSetDropFirst") @JvmName("setDropFirst") fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, Set<T>>.dropFirst() = mapModification(Modification.SetDropFirst())
@JsName("xKeyPathListMap") @JvmName("listMap") inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, List<T>>.map(modification: (KeyPath<T, T>) -> Modification<T>) = mapModification(Modification.ListPerElement(condition = Condition.Always<T>(), path<T>().let(modification)))
@JsName("xKeyPathSetMap") @JvmName("setMap") inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, Set<T>>.map(modification: (KeyPath<T, T>) -> Modification<T>) = mapModification(Modification.SetPerElement(condition = Condition.Always<T>(), path<T>().let(modification)))
@JsName("xKeyPathListMapIf") @JvmName("listMapIf") inline fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, List<T>>.mapIf(
    condition: (KeyPath<T, T>) -> Condition<T>,
    modification: (KeyPath<T, T>) -> Modification<T>,
) = mapModification(
    Modification.ListPerElement(
        condition = path<T>().let(condition),
        path<T>().let(modification)
    )
)

@JsName("xKeyPathSetMapIf") @JvmName("setMapIf") inline fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, Set<T>>.mapIf(
    condition: (KeyPath<T, T>) -> Condition<T>,
    modification: (KeyPath<T, T>) -> Modification<T>,
) = mapModification(
    Modification.SetPerElement(
        condition = path<T>().let(condition),
        path<T>().let(modification)
    )
)

@JsName("xKeyPathPlusMap") infix operator fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, Map<String, T>>.plus(map: Map<String, T>) = mapModification(Modification.Combine(map))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, Map<String, T>>.modifyByKey(map: Map<String, (KeyPath<T, T>) -> Modification<T>>) = mapModification(Modification.ModifyByKey(map.mapValues { path<T>().let(it.value) }))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, Map<String, T>>.removeKeys(fields: Set<String>) = mapModification(Modification.RemoveKeys(fields))


//////////////

val <K : IsCodableAndHashable> CMBuilder<K, K>.always: Condition<K> get() = Condition.Always<K>()
val <K : IsCodableAndHashable> CMBuilder<K, K>.never: Condition<K> get() = Condition.Never<K>()

infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, T>.eq(value: T) = mapCondition(Condition.Equal(value))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, T>.neq(value: T) = mapCondition(Condition.NotEqual(value))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, T>.ne(value: T) = mapCondition(Condition.NotEqual(value))
@JsName("xCMBuilderInsideSet") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, T>.inside(values: Set<T>) = mapCondition(Condition.Inside(values.toList()))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, T>.inside(values: List<T>) = mapCondition(Condition.Inside(values))
@JsName("xCMBuilderNinSet") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, T>.nin(values: Set<T>) = mapCondition(Condition.NotInside(values.toList()))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, T>.nin(values: List<T>) = mapCondition(Condition.NotInside(values))
@JsName("xCMBuilderNotInSet") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, T>.notIn(values: Set<T>) = mapCondition(Condition.NotInside(values.toList()))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, T>.notIn(values: List<T>) = mapCondition(Condition.NotInside(values))
infix fun <K : IsCodableAndHashable, T : ComparableCodableAndHashable<T>> CMBuilder<K, T>.gt(value: T) = mapCondition(Condition.GreaterThan(value))
infix fun <K : IsCodableAndHashable, T : ComparableCodableAndHashable<T>> CMBuilder<K, T>.lt(value: T) = mapCondition(Condition.LessThan(value))
infix fun <K : IsCodableAndHashable, T : ComparableCodableAndHashable<T>> CMBuilder<K, T>.gte(value: T) = mapCondition(Condition.GreaterThanOrEqual(value))
infix fun <K : IsCodableAndHashable, T : ComparableCodableAndHashable<T>> CMBuilder<K, T>.lte(value: T) = mapCondition(Condition.LessThanOrEqual(value))
infix fun <K : IsCodableAndHashable> CMBuilder<K, Int>.allClear(mask: Int) = mapCondition(Condition.IntBitsClear(mask))
infix fun <K : IsCodableAndHashable> CMBuilder<K, Int>.allSet(mask: Int) = mapCondition(Condition.IntBitsSet(mask))
infix fun <K : IsCodableAndHashable> CMBuilder<K, Int>.anyClear(mask: Int) = mapCondition(Condition.IntBitsAnyClear(mask))
infix fun <K : IsCodableAndHashable> CMBuilder<K, Int>.anySet(mask: Int) = mapCondition(Condition.IntBitsAnySet(mask))
infix fun <K : IsCodableAndHashable> CMBuilder<K, String>.contains(value: String) = mapCondition(Condition.StringContains(value, ignoreCase = true))
@JsName("xCMBuilderContainsCased") fun <K : IsCodableAndHashable> CMBuilder<K, String>.contains(value: String, ignoreCase: Boolean) = mapCondition(Condition.StringContains(value, ignoreCase = ignoreCase))
fun <K : IsCodableAndHashable, V : IsCodableAndHashable> CMBuilder<K, V>.fullTextSearch(value: String, ignoreCase: Boolean, ) = mapCondition(Condition.FullTextSearch<V>(value, ignoreCase = ignoreCase))
@JsName("xCMBuilderListAll") @JvmName("listAll") inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, List<T>>.all(condition: (KeyPath<T, T>) -> Condition<T>) = mapCondition(Condition.ListAllElements(path<T>().let(condition)))
@JsName("xCMBuilderListAny") @JvmName("listAny") inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, List<T>>.any(condition: (KeyPath<T, T>) -> Condition<T>) = mapCondition(Condition.ListAnyElements(path<T>().let(condition)))
@JsName("xCMBuilderListSizedEqual") @JvmName("listSizedEqual") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, List<T>>.sizesEquals(count: Int) = mapCondition(Condition.ListSizesEquals(count))
@JsName("xCMBuilderSetAll") @JvmName("setAll") inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, Set<T>>.all(condition: (KeyPath<T, T>) -> Condition<T>) = mapCondition(Condition.SetAllElements(path<T>().let(condition)))
@JsName("xCMBuilderSetAny") @JvmName("setAny") inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, Set<T>>.any(condition: (KeyPath<T, T>) -> Condition<T>) = mapCondition(Condition.SetAnyElements(path<T>().let(condition)))
@JsName("xCMBuilderSetSizedEqual") @JvmName("setSizedEqual") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, Set<T>>.sizesEquals(count: Int) = mapCondition(Condition.SetSizesEquals(count))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, Map<String, T>>.containsKey(key: String) = mapCondition(Condition.Exists(key))
inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, T>.condition(make: (KeyPath<T, T>) -> Condition<T>): Condition<K> = mapCondition(make(path<T>()))
inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, T>.modification(make: (KeyPath<T, T>) -> Modification<T>): Modification<K> = mapModification(make(path<T>()))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, T>.assign(value: T) = mapModification(Modification.Assign(value))
infix fun <K : IsCodableAndHashable, T : Comparable<T>> CMBuilder<K, T>.coerceAtMost(value: T) = mapModification(Modification.CoerceAtMost(value))
infix fun <K : IsCodableAndHashable, T : Comparable<T>> CMBuilder<K, T>.coerceAtLeast(value: T) = mapModification(Modification.CoerceAtLeast(value))
@JsName("xCMBuilderPlusNumber") infix operator fun <K : IsCodableAndHashable, T : Number> CMBuilder<K, T>.plus(by: T) = mapModification(Modification.Increment(by))
infix operator fun <K : IsCodableAndHashable, T : Number> CMBuilder<K, T>.times(by: T) = mapModification(Modification.Multiply(by))
@JsName("xCMBuilderPlusString") infix operator fun <K : IsCodableAndHashable> CMBuilder<K, String>.plus(value: String) = mapModification(Modification.AppendString(value))
@JsName("xCMBuilderPlusItemsList") infix operator fun <K : IsCodableAndHashable, T> CMBuilder<K, List<T>>.plus(items: List<T>) = mapModification(Modification.ListAppend(items))
@JsName("xCMBuilderPlusItemsSet") infix operator fun <K : IsCodableAndHashable, T> CMBuilder<K, Set<T>>.plus(items: Set<T>) = mapModification(Modification.SetAppend(items))
@JsName("xCMBuilderPlusItemList") @JvmName("plusList") infix operator fun <K : IsCodableAndHashable, T> CMBuilder<K, List<T>>.plus(item: T) = mapModification(Modification.ListAppend(listOf(item)))
@JsName("xCMBuilderPlusItemSet") @JvmName("plusSet") infix operator fun <K : IsCodableAndHashable, T> CMBuilder<K, Set<T>>.plus(item: T) = mapModification(Modification.SetAppend(setOf(item)))
@JsName("xCMBuilderListAddAll") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, List<T>>.addAll(items: List<T>) = mapModification(Modification.ListAppend(items))
@JsName("xCMBuilderSetAddAll") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, Set<T>>.addAll(items: Set<T>) = mapModification(Modification.SetAppend(items))
@JsName("xCMBuilderListRemove") @JvmName("listRemoveAll") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, List<T>>.removeAll(condition: (KeyPath<T, T>) -> Condition<T>) = mapModification(Modification.ListRemove(path<T>().let(condition)))
@JsName("xCMBuilderSetRemove") @JvmName("setRemoveAll") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, Set<T>>.removeAll(condition: (KeyPath<T, T>) -> Condition<T>) = mapModification(Modification.SetRemove(path<T>().let(condition)))
@JsName("xCMBuilderListRemoveAll") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, List<T>>.removeAll(items: List<T>) = mapModification(Modification.ListRemoveInstances(items))
@JsName("xCMBuilderSetRemoveAll") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, Set<T>>.removeAll(items: Set<T>) = mapModification(Modification.SetRemoveInstances(items))
@JsName("xCMBuilderListDropLast") @JvmName("listDropLast") fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, List<T>>.dropLast() = mapModification(Modification.ListDropLast())
@JsName("xCMBuilderSetDropLast") @JvmName("setDropLast") fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, Set<T>>.dropLast() = mapModification(Modification.SetDropLast())
@JsName("xCMBuilderListDropFirst") @JvmName("listDropFirst") fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, List<T>>.dropFirst() = mapModification(Modification.ListDropFirst())
@JsName("xCMBuilderSetDropFirst") @JvmName("setDropFirst") fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, Set<T>>.dropFirst() = mapModification(Modification.SetDropFirst())
@JsName("xCMBuilderListMap") @JvmName("listMap") inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, List<T>>.map(modification: (KeyPath<T, T>) -> Modification<T>) = mapModification(Modification.ListPerElement(condition = Condition.Always<T>(), path<T>().let(modification)))
@JsName("xCMBuilderSetMap") @JvmName("setMap") inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, Set<T>>.map(modification: (KeyPath<T, T>) -> Modification<T>) = mapModification(Modification.SetPerElement(condition = Condition.Always<T>(), path<T>().let(modification)))
@JsName("xCMBuilderListMapIf") @JvmName("listMapIf") inline fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, List<T>>.mapIf(
    condition: (KeyPath<T, T>) -> Condition<T>,
    modification: (KeyPath<T, T>) -> Modification<T>,
) = mapModification(
    Modification.ListPerElement(
        condition = path<T>().let(condition),
        path<T>().let(modification)
    )
)

@JsName("xCMBuilderSetMapIf") @JvmName("setMapIf") inline fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, Set<T>>.mapIf(
    condition: (KeyPath<T, T>) -> Condition<T>,
    modification: (KeyPath<T, T>) -> Modification<T>,
) = mapModification(
    Modification.SetPerElement(
        condition = path<T>().let(condition),
        path<T>().let(modification)
    )
)

@JsName("xCMBuilderPlusMap") infix operator fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, Map<String, T>>.plus(map: Map<String, T>) = mapModification(Modification.Combine(map))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, Map<String, T>>.modifyByKey(map: Map<String, (KeyPath<T, T>) -> Modification<T>>) = mapModification(Modification.ModifyByKey(map.mapValues { path<T>().let(it.value) }))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, Map<String, T>>.removeKeys(fields: Set<String>) = mapModification(Modification.RemoveKeys(fields))


//////////////


val <K : IsCodableAndHashable, T : IsCodableAndHashable> KeyPath<K, T?>.notNull
    get() = CMBuilder<K, T>(
        mapCondition = { mapCondition(Condition.IfNotNull(it)) },
        mapModification = { mapModification(Modification.IfNotNull(it)) }
    )


val <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, T?>.notNull
    get() = CMBuilder<K, T>(
        mapCondition = { mapCondition(Condition.IfNotNull(it)) },
        mapModification = { mapModification(Modification.IfNotNull(it)) }
    )

operator fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, Map<String, T>>.get(key: String) =
    CMBuilder<K, T>(
        mapCondition = { mapCondition(Condition.OnKey(key, it)) },
        mapModification = { mapModification(Modification.ModifyByKey(mapOf(key to it))) }
    )

@JsName("xCMBuilderListAll") @get:JvmName("listAll")
inline val <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, List<T>>.all get() =
    CMBuilder<K, T>(
        mapCondition = { mapCondition(Condition.ListAllElements(it)) },
        mapModification = { mapModification(Modification.ListPerElement(Condition.Always(), it)) }
    )

@JsName("xCMBuilderSetAll") @get:JvmName("setAll")
inline val <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, Set<T>>.all get() =
    CMBuilder<K, T>(
        mapCondition = { mapCondition(Condition.SetAllElements(it)) },
        mapModification = { mapModification(Modification.SetPerElement(Condition.Always(), it)) }
    )

@JsName("xCMBuilderListAny") @get:JvmName("listAny")
inline val <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, List<T>>.any get() =
    CMBuilder<K, T>(
        mapCondition = { mapCondition(Condition.ListAnyElements(it)) },
        mapModification = { mapModification(Modification.ListPerElement(Condition.Always(), it)) }
    )

@JsName("xCMBuilderSetAny") @get:JvmName("setAny")
inline val <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, Set<T>>.any get() =
    CMBuilder<K, T>(
        mapCondition = { mapCondition(Condition.SetAnyElements(it)) },
        mapModification = { mapModification(Modification.SetPerElement(Condition.Always(), it)) }
    )


@CheckReturnValue
@Deprecated("This is a no-op now.  Just use the parts.", ReplaceWith("this\nignored"))
infix fun Unit.then(ignored: Unit): Unit = Unit