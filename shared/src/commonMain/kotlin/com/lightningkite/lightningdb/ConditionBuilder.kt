package com.lightningkite.lightningdb

import com.lightningkite.*
import com.lightningkite.Distance
import com.lightningkite.GeoCoordinate
import com.lightningkite.miles
import kotlin.js.JsName
import kotlin.jvm.JvmName

inline fun <reified T> path(): DataClassPath<T, T> = DataClassPathSelf(serializerOrContextual<T>())

inline fun <reified T> condition(setup: (DataClassPath<T, T>) -> Condition<T>): Condition<T> =
    path<T>().let(setup)
fun <T> condition(boolean: Boolean): Condition<T> = if(boolean) Condition.Always() else Condition.Never()

val <K> DataClassPath<K, K>.always: Condition<K> get() = Condition.Always<K>()
val <K> DataClassPath<K, K>.never: Condition<K> get() = Condition.Never<K>()

infix fun <K, T> DataClassPath<K, T>.eq(value: T) = mapCondition(Condition.Equal(value))
infix fun <K, T: Any> DataClassPath<K, T>.eqNn(value: T?) = if(value == null) Condition.Never() else mapCondition(Condition.Equal(value))
infix fun <K, T> DataClassPath<K, T>.neq(value: T) = mapCondition(Condition.NotEqual(value))
@JsName("xDataClassPathNotInSet") infix fun <K, T> DataClassPath<K, T>.notInside(values: Set<T>) = mapCondition(Condition.NotInside(values.toList()))
infix fun <K, T> DataClassPath<K, T>.notInside(values: List<T>) = mapCondition(Condition.NotInside(values))
infix fun <K, T: Comparable<T>> DataClassPath<K, T>.gt(value: T) = mapCondition(Condition.GreaterThan(value))
infix fun <K, T: Comparable<T>> DataClassPath<K, T>.lt(value: T) = mapCondition(Condition.LessThan(value))
infix fun <K, T: Comparable<T>> DataClassPath<K, T>.gte(value: T) = mapCondition(Condition.GreaterThanOrEqual(value))
infix fun <K, T: Comparable<T>> DataClassPath<K, T>.lte(value: T) = mapCondition(Condition.LessThanOrEqual(value))
infix fun <K> DataClassPath<K, Int>.allClear(mask: Int) = mapCondition(Condition.IntBitsClear(mask))
infix fun <K> DataClassPath<K, Int>.allSet(mask: Int) = mapCondition(Condition.IntBitsSet(mask))
infix fun <K> DataClassPath<K, Int>.anyClear(mask: Int) = mapCondition(Condition.IntBitsAnyClear(mask))
infix fun <K> DataClassPath<K, Int>.anySet(mask: Int) = mapCondition(Condition.IntBitsAnySet(mask))
infix fun <K> DataClassPath<K, String>.contains(value: String) = mapCondition(Condition.StringContains(value, ignoreCase = true))
@JvmName("containsRaw") infix fun <K, T : IsRawString> DataClassPath<K, T>.contains(value: String) = mapCondition(Condition.RawStringContains(value, ignoreCase = true))
fun <K> DataClassPath<K, GeoCoordinate>.distanceBetween(value: GeoCoordinate, greaterThan: Distance = 0.0.miles, lessThan: Distance = 100_000.0.miles) = mapCondition(Condition.GeoDistance(value, greaterThan.kilometers, lessThan.kilometers))
@JsName("xDataClassPathContainsCased") fun <K> DataClassPath<K, String>.contains(value: String, ignoreCase: Boolean) = mapCondition(Condition.StringContains(value, ignoreCase = ignoreCase))
@JvmName("containsRaw") @JsName("xDataClassPathContainsRawCased") fun <K, T : IsRawString> DataClassPath<K, T>.contains(value: String, ignoreCase: Boolean) = mapCondition(Condition.RawStringContains(value, ignoreCase = ignoreCase))
fun <K, V> DataClassPath<K, V>.fullTextSearch(value: String, ignoreCase: Boolean, ) = mapCondition(Condition.FullTextSearch<V>(value, ignoreCase = ignoreCase))
@JsName("xDataClassPathListAll") @JvmName("listAll") inline infix fun <K, reified T> DataClassPath<K, List<T>>.all(condition: (DataClassPath<T, T>) -> Condition<T>) = mapCondition(Condition.ListAllElements(path<T>().let(condition)))
@JsName("xDataClassPathListAny") @JvmName("listAny") inline infix fun <K, reified T> DataClassPath<K, List<T>>.any(condition: (DataClassPath<T, T>) -> Condition<T>) = mapCondition(Condition.ListAnyElements(path<T>().let(condition)))
@JsName("xDataClassPathSetAll") @JvmName("setAll") inline infix fun <K, reified T> DataClassPath<K, Set<T>>.all(condition: (DataClassPath<T, T>) -> Condition<T>) = mapCondition(Condition.SetAllElements(path<T>().let(condition)))
@JsName("xDataClassPathSetAny") @JvmName("setAny") inline infix fun <K, reified T> DataClassPath<K, Set<T>>.any(condition: (DataClassPath<T, T>) -> Condition<T>) = mapCondition(Condition.SetAnyElements(path<T>().let(condition)))
infix fun <K, T> DataClassPath<K, Map<String, T>>.containsKey(key: String) = mapCondition(Condition.Exists(key))
inline infix fun <K, reified T> DataClassPath<K, T>.condition(make: (DataClassPath<T, T>) -> Condition<T>): Condition<K> = mapCondition(make(path<T>()))

@Deprecated("Use neq instead", ReplaceWith("this.neq(value)", "com.lightningkite.lightningdb.neq"))
infix fun <K, T> DataClassPath<K, T>.ne(value: T) = mapCondition(Condition.NotEqual(value))
@JsName("xDataClassPathInsideSet") infix fun <K, T> DataClassPath<K, T>.inside(values: Set<T>) = mapCondition(Condition.Inside(values.toList()))
infix fun <K, T> DataClassPath<K, T>.inside(values: List<T>) = mapCondition(Condition.Inside(values))
@Deprecated("Use notInside instead", ReplaceWith("this.notInside(value)", "com.lightningkite.lightningdb.notInside"))
@JsName("xDataClassPathNinSet") infix fun <K, T> DataClassPath<K, T>.nin(values: Set<T>) = mapCondition(Condition.NotInside(values.toList()))
@Deprecated("Use notInside instead", ReplaceWith("this.notInside(value)", "com.lightningkite.lightningdb.notInside"))
infix fun <K, T> DataClassPath<K, T>.nin(values: List<T>) = mapCondition(Condition.NotInside(values))
@Deprecated("Use notInside instead", ReplaceWith("this.notInside(value)", "com.lightningkite.lightningdb.notInside"))
@JsName("xDataClassPathNotInSet2") infix fun <K, T> DataClassPath<K, T>.notIn(values: Set<T>) = mapCondition(Condition.NotInside(values.toList()))
@Deprecated("Use notInside instead", ReplaceWith("this.notInside(values)", "com.lightningkite.lightningdb.notInside"))
infix fun <K, T> DataClassPath<K, T>.notIn(values: List<T>) = mapCondition(Condition.NotInside(values))
@Deprecated("Size equals will be removed in the future in favor of something that detects empty specifically")
@JsName("xDataClassPathListSizedEqual") @JvmName("listSizedEqual") infix fun <K, T> DataClassPath<K, List<T>>.sizesEquals(count: Int) = mapCondition(Condition.ListSizesEquals(count))
@Deprecated("Size equals will be removed in the future in favor of something that detects empty specifically")
@JsName("xDataClassPathSetSizedEqual") @JvmName("setSizedEqual") infix fun <K, T> DataClassPath<K, Set<T>>.sizesEquals(count: Int) = mapCondition(Condition.SetSizesEquals(count))