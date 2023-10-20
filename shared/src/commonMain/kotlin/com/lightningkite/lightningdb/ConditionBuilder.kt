@file:SharedCode

package com.lightningkite.lightningdb

import com.lightningkite.khrysalis.*
import com.lightningkite.lightningdb.SerializableProperty
import kotlin.jvm.JvmName

inline fun <reified T : IsCodableAndHashable> path(): DataClassPath<T, T> = DataClassPathSelf(serializerOrContextual<T>())

inline fun <reified T : IsCodableAndHashable> condition(setup: (DataClassPath<T, T>) -> Condition<T>): Condition<T> =
    path<T>().let(setup)
fun <T> condition(boolean: Boolean): Condition<T> = if(boolean) Condition.Always() else Condition.Never()

val <K : IsCodableAndHashable> DataClassPath<K, K>.always: Condition<K> get() = Condition.Always<K>()
val <K : IsCodableAndHashable> DataClassPath<K, K>.never: Condition<K> get() = Condition.Never<K>()

infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> DataClassPath<K, T>.eq(value: T) = mapCondition(Condition.Equal(value))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashableNotNull> DataClassPath<K, T>.eqNn(value: T?) = if(value == null) Condition.Never() else mapCondition(Condition.Equal(value))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> DataClassPath<K, T>.neq(value: T) = mapCondition(Condition.NotEqual(value))
@JsName("xDataClassPathNotInSet") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> DataClassPath<K, T>.notInside(values: Set<T>) = mapCondition(Condition.NotInside(values.toList()))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> DataClassPath<K, T>.notInside(values: List<T>) = mapCondition(Condition.NotInside(values))
infix fun <K : IsCodableAndHashable, T : ComparableCodableAndHashable<T>> DataClassPath<K, T>.gt(value: T) = mapCondition(Condition.GreaterThan(value))
infix fun <K : IsCodableAndHashable, T : ComparableCodableAndHashable<T>> DataClassPath<K, T>.lt(value: T) = mapCondition(Condition.LessThan(value))
infix fun <K : IsCodableAndHashable, T : ComparableCodableAndHashable<T>> DataClassPath<K, T>.gte(value: T) = mapCondition(Condition.GreaterThanOrEqual(value))
infix fun <K : IsCodableAndHashable, T : ComparableCodableAndHashable<T>> DataClassPath<K, T>.lte(value: T) = mapCondition(Condition.LessThanOrEqual(value))
infix fun <K : IsCodableAndHashable> DataClassPath<K, Int>.allClear(mask: Int) = mapCondition(Condition.IntBitsClear(mask))
infix fun <K : IsCodableAndHashable> DataClassPath<K, Int>.allSet(mask: Int) = mapCondition(Condition.IntBitsSet(mask))
infix fun <K : IsCodableAndHashable> DataClassPath<K, Int>.anyClear(mask: Int) = mapCondition(Condition.IntBitsAnyClear(mask))
infix fun <K : IsCodableAndHashable> DataClassPath<K, Int>.anySet(mask: Int) = mapCondition(Condition.IntBitsAnySet(mask))
infix fun <K : IsCodableAndHashable> DataClassPath<K, String>.contains(value: String) = mapCondition(Condition.StringContains(value, ignoreCase = true))
@JsName("xDataClassPathContainsCased") fun <K : IsCodableAndHashable> DataClassPath<K, String>.contains(value: String, ignoreCase: Boolean) = mapCondition(Condition.StringContains(value, ignoreCase = ignoreCase))
fun <K : IsCodableAndHashable, V : IsCodableAndHashable> DataClassPath<K, V>.fullTextSearch(value: String, ignoreCase: Boolean, ) = mapCondition(Condition.FullTextSearch<V>(value, ignoreCase = ignoreCase))
@JsName("xDataClassPathListAll") @JvmName("listAll") inline infix fun <K : IsCodableAndHashable, reified T : IsCodableAndHashable> DataClassPath<K, List<T>>.all(condition: (DataClassPath<T, T>) -> Condition<T>) = mapCondition(Condition.ListAllElements(path<T>().let(condition)))
@JsName("xDataClassPathListAny") @JvmName("listAny") inline infix fun <K : IsCodableAndHashable, reified T : IsCodableAndHashable> DataClassPath<K, List<T>>.any(condition: (DataClassPath<T, T>) -> Condition<T>) = mapCondition(Condition.ListAnyElements(path<T>().let(condition)))
@JsName("xDataClassPathSetAll") @JvmName("setAll") inline infix fun <K : IsCodableAndHashable, reified T : IsCodableAndHashable> DataClassPath<K, Set<T>>.all(condition: (DataClassPath<T, T>) -> Condition<T>) = mapCondition(Condition.SetAllElements(path<T>().let(condition)))
@JsName("xDataClassPathSetAny") @JvmName("setAny") inline infix fun <K : IsCodableAndHashable, reified T : IsCodableAndHashable> DataClassPath<K, Set<T>>.any(condition: (DataClassPath<T, T>) -> Condition<T>) = mapCondition(Condition.SetAnyElements(path<T>().let(condition)))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> DataClassPath<K, Map<String, T>>.containsKey(key: String) = mapCondition(Condition.Exists(key))
inline infix fun <K : IsCodableAndHashable, reified T : IsCodableAndHashable> DataClassPath<K, T>.condition(make: (DataClassPath<T, T>) -> Condition<T>): Condition<K> = mapCondition(make(path<T>()))

@Deprecated("Use neq instead", ReplaceWith("this.neq(value)", "com.lightningkite.lightningdb.neq"))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> DataClassPath<K, T>.ne(value: T) = mapCondition(Condition.NotEqual(value))
@JsName("xDataClassPathInsideSet") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> DataClassPath<K, T>.inside(values: Set<T>) = mapCondition(Condition.Inside(values.toList()))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> DataClassPath<K, T>.inside(values: List<T>) = mapCondition(Condition.Inside(values))
@Deprecated("Use notInside instead", ReplaceWith("this.notInside(value)", "com.lightningkite.lightningdb.notInside"))
@JsName("xDataClassPathNinSet") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> DataClassPath<K, T>.nin(values: Set<T>) = mapCondition(Condition.NotInside(values.toList()))
@Deprecated("Use notInside instead", ReplaceWith("this.notInside(value)", "com.lightningkite.lightningdb.notInside"))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> DataClassPath<K, T>.nin(values: List<T>) = mapCondition(Condition.NotInside(values))
@Deprecated("Use notInside instead", ReplaceWith("this.notInside(value)", "com.lightningkite.lightningdb.notInside"))
@JsName("xDataClassPathNotInSet") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> DataClassPath<K, T>.notIn(values: Set<T>) = mapCondition(Condition.NotInside(values.toList()))
@Deprecated("Use notInside instead", ReplaceWith("this.notInside(value)", "com.lightningkite.lightningdb.notInside"))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> DataClassPath<K, T>.notIn(values: List<T>) = mapCondition(Condition.NotInside(values))
@Deprecated("Size equals will be removed in the future in favor of something that detects empty specifically")
@JsName("xDataClassPathListSizedEqual") @JvmName("listSizedEqual") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> DataClassPath<K, List<T>>.sizesEquals(count: Int) = mapCondition(Condition.ListSizesEquals(count))
@Deprecated("Size equals will be removed in the future in favor of something that detects empty specifically")
@JsName("xDataClassPathSetSizedEqual") @JvmName("setSizedEqual") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> DataClassPath<K, Set<T>>.sizesEquals(count: Int) = mapCondition(Condition.SetSizesEquals(count))