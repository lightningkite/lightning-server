@file:SharedCode

package com.lightningkite.lightningdb

import com.lightningkite.khrysalis.*
import kotlin.reflect.KProperty1

class CMBuilder<From : IsCodableAndHashable, To : IsCodableAndHashable>(
    val mapCondition: (Condition<To>) -> Condition<From>,
    val mapModification: (Modification<To>) -> Modification<From>
)

fun <From : IsCodableAndHashable, To : IsCodableAndHashable> DataClassPath<From, To>.toCMBuilder() = CMBuilder(
    mapCondition = { it: Condition<To> -> mapCondition(it) },
    mapModification = { it: Modification<To> -> mapModification(it) },
)

fun <T : IsCodableAndHashable> path(): DataClassPath<T, T> = DataClassPathSelf()

inline fun <T : IsCodableAndHashable> condition(setup: (DataClassPath<T, T>) -> Condition<T>): Condition<T> =
    path<T>().let(setup)

fun <K : IsCodableAndHashable, V : IsCodableAndHashable> DataClassPath<K, V>.mapCondition(condition: Condition<V>): Condition<K> {
    @Suppress("UNCHECKED_CAST")
    return when(this) {
        is DataClassPathSelf<*> -> condition as Condition<K>
        is DataClassPathAccess<*, *, *> -> (first as DataClassPath<K, Any?>).mapCondition(Condition.OnField(second as KProperty1<Any?, V>, condition))
        is DataClassPathSafeAccess<*, *, *> -> (first as DataClassPath<K, Any?>).mapCondition(Condition.IfNotNull((second as DataClassPath<Any, V>).mapCondition(condition)))
        else -> fatalError()
    }
}
fun <K : IsCodableAndHashable, V : IsCodableAndHashable> DataClassPath<K, V>.mapModification(modification: Modification<V>): Modification<K> {
    @Suppress("UNCHECKED_CAST")
    return when(this) {
        is DataClassPathSelf<*> -> modification as Modification<K>
        is DataClassPathAccess<*, *, *> -> (first as DataClassPath<K, Any?>).mapModification(Modification.OnField(second as KProperty1<Any?, V>, modification))
        is DataClassPathSafeAccess<*, *, *> -> (first as DataClassPath<K, Any?>).mapModification(Modification.IfNotNull((second as DataClassPath<Any, V>).mapModification(modification)))
        else -> fatalError()
    }
}

val <K : IsCodableAndHashable> DataClassPath<K, K>.always: Condition<K> get() = Condition.Always<K>()
val <K : IsCodableAndHashable> DataClassPath<K, K>.never: Condition<K> get() = Condition.Never<K>()

infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> DataClassPath<K, T>.eq(value: T) = mapCondition(Condition.Equal(value))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> DataClassPath<K, T>.neq(value: T) = mapCondition(Condition.NotEqual(value))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> DataClassPath<K, T>.ne(value: T) = mapCondition(Condition.NotEqual(value))
@JsName("xDataClassPathInsideSet") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> DataClassPath<K, T>.inside(values: Set<T>) = mapCondition(Condition.Inside(values.toList()))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> DataClassPath<K, T>.inside(values: List<T>) = mapCondition(Condition.Inside(values))
@JsName("xDataClassPathNinSet") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> DataClassPath<K, T>.nin(values: Set<T>) = mapCondition(Condition.NotInside(values.toList()))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> DataClassPath<K, T>.nin(values: List<T>) = mapCondition(Condition.NotInside(values))
@JsName("xDataClassPathNotInSet") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> DataClassPath<K, T>.notIn(values: Set<T>) = mapCondition(Condition.NotInside(values.toList()))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> DataClassPath<K, T>.notIn(values: List<T>) = mapCondition(Condition.NotInside(values))
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
@JsName("xDataClassPathListAll") @JvmName("listAll") inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> DataClassPath<K, List<T>>.all(condition: (DataClassPath<T, T>) -> Condition<T>) = mapCondition(Condition.ListAllElements(path<T>().let(condition)))
@JsName("xDataClassPathListAny") @JvmName("listAny") inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> DataClassPath<K, List<T>>.any(condition: (DataClassPath<T, T>) -> Condition<T>) = mapCondition(Condition.ListAnyElements(path<T>().let(condition)))
@JsName("xDataClassPathListSizedEqual") @JvmName("listSizedEqual") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> DataClassPath<K, List<T>>.sizesEquals(count: Int) = mapCondition(Condition.ListSizesEquals(count))
@JsName("xDataClassPathSetAll") @JvmName("setAll") inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> DataClassPath<K, Set<T>>.all(condition: (DataClassPath<T, T>) -> Condition<T>) = mapCondition(Condition.SetAllElements(path<T>().let(condition)))
@JsName("xDataClassPathSetAny") @JvmName("setAny") inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> DataClassPath<K, Set<T>>.any(condition: (DataClassPath<T, T>) -> Condition<T>) = mapCondition(Condition.SetAnyElements(path<T>().let(condition)))
@JsName("xDataClassPathSetSizedEqual") @JvmName("setSizedEqual") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> DataClassPath<K, Set<T>>.sizesEquals(count: Int) = mapCondition(Condition.SetSizesEquals(count))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> DataClassPath<K, Map<String, T>>.containsKey(key: String) = mapCondition(Condition.Exists(key))
inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> DataClassPath<K, T>.condition(make: (DataClassPath<T, T>) -> Condition<T>): Condition<K> = mapCondition(make(path<T>()))

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
@JsName("xCMBuilderListAll") @JvmName("listAll") inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, List<T>>.all(condition: (DataClassPath<T, T>) -> Condition<T>) = mapCondition(Condition.ListAllElements(path<T>().let(condition)))
@JsName("xCMBuilderListAny") @JvmName("listAny") inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, List<T>>.any(condition: (DataClassPath<T, T>) -> Condition<T>) = mapCondition(Condition.ListAnyElements(path<T>().let(condition)))
@JsName("xCMBuilderListSizedEqual") @JvmName("listSizedEqual") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, List<T>>.sizesEquals(count: Int) = mapCondition(Condition.ListSizesEquals(count))
@JsName("xCMBuilderSetAll") @JvmName("setAll") inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, Set<T>>.all(condition: (DataClassPath<T, T>) -> Condition<T>) = mapCondition(Condition.SetAllElements(path<T>().let(condition)))
@JsName("xCMBuilderSetAny") @JvmName("setAny") inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, Set<T>>.any(condition: (DataClassPath<T, T>) -> Condition<T>) = mapCondition(Condition.SetAnyElements(path<T>().let(condition)))
@JsName("xCMBuilderSetSizedEqual") @JvmName("setSizedEqual") infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, Set<T>>.sizesEquals(count: Int) = mapCondition(Condition.SetSizesEquals(count))
infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, Map<String, T>>.containsKey(key: String) = mapCondition(Condition.Exists(key))
inline infix fun <K : IsCodableAndHashable, T : IsCodableAndHashable> CMBuilder<K, T>.condition(make: (DataClassPath<T, T>) -> Condition<T>): Condition<K> = mapCondition(make(path<T>()))


//////////////


val <K : IsCodableAndHashable, T : IsCodableAndHashable> DataClassPath<K, T?>.notNull
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