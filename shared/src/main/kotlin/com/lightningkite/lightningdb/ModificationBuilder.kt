@file:SharedCode
package com.lightningkite.lightningdb

import com.lightningkite.khrysalis.IsCodableAndHashable
import com.lightningkite.khrysalis.JsName
import com.lightningkite.khrysalis.SharedCode

inline fun <T : IsCodableAndHashable> modification(setup: ModificationBuilder<T>.(DataClassPath<T, T>) -> Unit): Modification<T> {
    return ModificationBuilder<T>().apply {
        setup(path())
    }.build()
}

inline fun <T : IsCodableAndHashable> Modification<T>.and(setup: ModificationBuilder<T>.(DataClassPath<T, T>) -> Unit): Modification<T> {
    return ModificationBuilder<T>().apply {
        modifications.add(this@and)
        setup(path())
    }.build()
}

class ModificationBuilder<K : IsCodableAndHashable>() {
    val modifications = ArrayList<Modification<K>>()
    fun add(modification: Modification<K>) = modifications.add(modification)
    fun build(): Modification<K> = modifications.singleOrNull() ?: Modification.Chain(modifications)

    infix fun <T : IsCodableAndHashable> CMBuilder<K, T>.assign(value: T) {
        modifications.add(mapModification(Modification.Assign(value)))
    }

    infix fun <T : Comparable<T>> CMBuilder<K, T>.coerceAtMost(value: T) {
        modifications.add(mapModification(Modification.CoerceAtMost(value)))
    }

    infix fun <T : Comparable<T>> CMBuilder<K, T>.coerceAtLeast(value: T) {
        modifications.add(mapModification(Modification.CoerceAtLeast(value)))
    }

    @JsName("xCMBuilderPlusNumberOld")
    @Deprecated("Use plusAssign instead", ReplaceWith("this.plusAssign(by)"))
    infix operator fun <T : Number> CMBuilder<K, T>.plus(by: T) {
        modifications.add(mapModification(Modification.Increment(by)))
    }

    @Deprecated("Use timesAssign instead", ReplaceWith("this.timesAssign(by)"))
    infix operator fun <T : Number> CMBuilder<K, T>.times(by: T) {
        modifications.add(mapModification(Modification.Multiply(by)))
    }

    @JsName("xCMBuilderPlusStringOld")
    @Deprecated("Use plusAssign instead", ReplaceWith("this.plusAssign(value)"))
    infix operator fun CMBuilder<K, String>.plus(value: String) {
        modifications.add(mapModification(Modification.AppendString(value)))
    }

    @JsName("xCMBuilderPlusItemsListOld")
    @Deprecated("Use : Assign instead", ReplaceWith("this.plusAssign(items)"))
    infix operator fun <T> CMBuilder<K, List<T>>.plus(items: List<T>) {
        modifications.add(mapModification(Modification.ListAppend(items)))
    }

    @JsName("xCMBuilderPlusItemsSetOld")
    @Deprecated("Use : Assign instead", ReplaceWith("this.plusAssign(items)"))
    infix operator fun <T> CMBuilder<K, Set<T>>.plus(items: Set<T>) {
        modifications.add(mapModification(Modification.SetAppend(items)))
    }

    @JsName("xCMBuilderPlusItemListOld")
    @JvmName("plusListOld")
    @Deprecated("Use plusAssign instead", ReplaceWith("this.plusAssign(item)"))
    infix operator fun <T> CMBuilder<K, List<T>>.plus(item: T) {
        modifications.add(mapModification(Modification.ListAppend(listOf(item))))
    }

    @JsName("xCMBuilderPlusItemSetOld")
    @JvmName("plusSetOld")
    @Deprecated("Use plusAssign instead", ReplaceWith("this.plusAssign(item)"))
    infix operator fun <T> CMBuilder<K, Set<T>>.plus(item: T) {
        modifications.add(mapModification(Modification.SetAppend(setOf(item))))
    }

    @JsName("xCMBuilderPlusNumber")
    infix operator fun <T : Number> CMBuilder<K, T>.plusAssign(by: T) {
        modifications.add(mapModification(Modification.Increment(by)))
    }

    infix operator fun <T : Number> CMBuilder<K, T>.timesAssign(by: T) {
        modifications.add(mapModification(Modification.Multiply(by)))
    }

    @JsName("xCMBuilderPlusString")
    infix operator fun CMBuilder<K, String>.plusAssign(value: String) {
        modifications.add(mapModification(Modification.AppendString(value)))
    }

    @JsName("xCMBuilderPlusItemsList")
    infix operator fun <T> CMBuilder<K, List<T>>.plusAssign(items: List<T>) {
        modifications.add(mapModification(Modification.ListAppend(items)))
    }

    @JsName("xCMBuilderPlusItemsSet")
    infix operator fun <T> CMBuilder<K, Set<T>>.plusAssign(items: Set<T>) {
        modifications.add(mapModification(Modification.SetAppend(items)))
    }

    @JsName("xCMBuilderPlusItemList")
    @JvmName("plusList")
    infix operator fun <T> CMBuilder<K, List<T>>.plusAssign(item: T) {
        modifications.add(mapModification(Modification.ListAppend(listOf(item))))
    }

    @JsName("xCMBuilderPlusItemSet")
    @JvmName("plusSet")
    infix operator fun <T> CMBuilder<K, Set<T>>.plusAssign(item: T) {
        modifications.add(mapModification(Modification.SetAppend(setOf(item))))
    }

    @JsName("xCMBuilderListAddAll")
    infix fun <T : IsCodableAndHashable> CMBuilder<K, List<T>>.addAll(items: List<T>) {
        modifications.add(mapModification(Modification.ListAppend(items)))
    }

    @JsName("xCMBuilderSetAddAll")
    infix fun <T : IsCodableAndHashable> CMBuilder<K, Set<T>>.addAll(items: Set<T>) {
        modifications.add(mapModification(Modification.SetAppend(items)))
    }

    @JsName("xCMBuilderListRemove")
    @JvmName("listRemoveAll")
    infix fun <T : IsCodableAndHashable> CMBuilder<K, List<T>>.removeAll(condition: (DataClassPath<T, T>) -> Condition<T>) {
        modifications.add(mapModification(Modification.ListRemove(path<T>().let(condition))))
    }

    @JsName("xCMBuilderSetRemove")
    @JvmName("setRemoveAll")
    infix fun <T : IsCodableAndHashable> CMBuilder<K, Set<T>>.removeAll(condition: (DataClassPath<T, T>) -> Condition<T>) {
        modifications.add(mapModification(Modification.SetRemove(path<T>().let(condition))))
    }

    @JsName("xCMBuilderListRemoveAll")
    infix fun <T : IsCodableAndHashable> CMBuilder<K, List<T>>.removeAll(items: List<T>) {
        modifications.add(mapModification(Modification.ListRemoveInstances(items)))
    }

    @JsName("xCMBuilderSetRemoveAll")
    infix fun <T : IsCodableAndHashable> CMBuilder<K, Set<T>>.removeAll(items: Set<T>) {
        modifications.add(mapModification(Modification.SetRemoveInstances(items)))
    }

    @JsName("xCMBuilderListDropLast")
    @JvmName("listDropLast")
    fun <T : IsCodableAndHashable> CMBuilder<K, List<T>>.dropLast() {
        modifications.add(mapModification(Modification.ListDropLast()))
    }

    @JsName("xCMBuilderSetDropLast")
    @JvmName("setDropLast")
    fun <T : IsCodableAndHashable> CMBuilder<K, Set<T>>.dropLast() {
        modifications.add(mapModification(Modification.SetDropLast()))
    }

    @JsName("xCMBuilderListDropFirst")
    @JvmName("listDropFirst")
    fun <T : IsCodableAndHashable> CMBuilder<K, List<T>>.dropFirst() {
        modifications.add(mapModification(Modification.ListDropFirst()))
    }

    @JsName("xCMBuilderSetDropFirst")
    @JvmName("setDropFirst")
    fun <T : IsCodableAndHashable> CMBuilder<K, Set<T>>.dropFirst() {
        modifications.add(mapModification(Modification.SetDropFirst()))
    }

    @JsName("xCMBuilderListMap")
    @JvmName("listMap")
    inline infix fun <T : IsCodableAndHashable> CMBuilder<K, List<T>>.map(modification: ModificationBuilder<T>.(DataClassPath<T, T>) -> Unit) {
        modifications.add(
            mapModification(
                Modification.ListPerElement(
                    condition = Condition.Always<T>(),
                    ModificationBuilder<T>().apply {
                        modification(path())
                    }.build()
                )
            )
        )
    }

    @JsName("xCMBuilderSetMap")
    @JvmName("setMap")
    inline infix fun <T : IsCodableAndHashable> CMBuilder<K, Set<T>>.map(modification: ModificationBuilder<T>.(DataClassPath<T, T>) -> Unit) {
        modifications.add(
            mapModification(
                Modification.SetPerElement(
                    condition = Condition.Always<T>(),
                    ModificationBuilder<T>().apply {
                        modification(path())
                    }.build()
                )
            )
        )
    }

    @JsName("xCMBuilderListMapIf")
    @JvmName("listMapIf")
    inline fun <T : IsCodableAndHashable> CMBuilder<K, List<T>>.mapIf(
        condition: (DataClassPath<T, T>) -> Condition<T>,
        modification: ModificationBuilder<T>.(DataClassPath<T, T>) -> Unit,
    ) {
        modifications.add(
            mapModification(
                Modification.ListPerElement(
                    condition = path<T>().let(condition),
                    ModificationBuilder<T>().apply {
                        modification(path())
                    }.build()
                )
            )
        )
    }

    @JsName("xCMBuilderSetMapIf")
    @JvmName("setMapIf")
    inline fun <T : IsCodableAndHashable> CMBuilder<K, Set<T>>.mapIf(
        condition: (DataClassPath<T, T>) -> Condition<T>,
        modification: ModificationBuilder<T>.(DataClassPath<T, T>) -> Unit,
    ) {
        modifications.add(
            mapModification(
                Modification.SetPerElement(
                    condition = path<T>().let(condition),
                    ModificationBuilder<T>().apply {
                        modification(path())
                    }.build()
                )
            )
        )
    }

    @JsName("xCMBuilderPlusMap")
    infix operator fun <T : IsCodableAndHashable> CMBuilder<K, Map<String, T>>.plus(map: Map<String, T>) {
        modifications.add(mapModification(Modification.Combine(map)))
    }

    infix fun <T : IsCodableAndHashable> CMBuilder<K, Map<String, T>>.modifyByKey(map: Map<String, ModificationBuilder<T>.(DataClassPath<T, T>) -> Unit>) {
        modifications.add(mapModification(Modification.ModifyByKey(map.mapValues { modification(it.value) })))
    }

    infix fun <T : IsCodableAndHashable> CMBuilder<K, Map<String, T>>.removeKeys(fields: Set<String>) {
        modifications.add(mapModification(Modification.RemoveKeys(fields)))
    }

    infix fun <T : IsCodableAndHashable> DataClassPath<K, T>.assign(value: T) {
        modifications.add(mapModification(Modification.Assign(value)))
    }

    infix fun <T : Comparable<T>> DataClassPath<K, T>.coerceAtMost(value: T) {
        modifications.add(mapModification(Modification.CoerceAtMost(value)))
    }

    infix fun <T : Comparable<T>> DataClassPath<K, T>.coerceAtLeast(value: T) {
        modifications.add(mapModification(Modification.CoerceAtLeast(value)))
    }

    @JsName("xDataClassPathPlusNumberOld")
    @Deprecated("Use plusAssign instead", ReplaceWith("this.plusAssign(by)"))
    infix operator fun <T : Number> DataClassPath<K, T>.plus(by: T) {
        modifications.add(mapModification(Modification.Increment(by)))
    }

    @Deprecated("Use timesAssign instead", ReplaceWith("this.timesAssign(by)"))
    infix operator fun <T : Number> DataClassPath<K, T>.times(by: T) {
        modifications.add(mapModification(Modification.Multiply(by)))
    }

    @JsName("xDataClassPathPlusStringOld")
    @Deprecated("Use plusAssign instead", ReplaceWith("this.plusAssign(value)"))
    infix operator fun DataClassPath<K, String>.plus(value: String) {
        modifications.add(mapModification(Modification.AppendString(value)))
    }

    @JsName("xDataClassPathPlusItemsListOld")
    @Deprecated("Use : Assign instead", ReplaceWith("this.plusAssign(items)"))
    infix operator fun <T> DataClassPath<K, List<T>>.plus(items: List<T>) {
        modifications.add(mapModification(Modification.ListAppend(items)))
    }

    @JsName("xDataClassPathPlusItemsSetOld")
    @Deprecated("Use : Assign instead", ReplaceWith("this.plusAssign(items)"))
    infix operator fun <T> DataClassPath<K, Set<T>>.plus(items: Set<T>) {
        modifications.add(mapModification(Modification.SetAppend(items)))
    }

    @JsName("xDataClassPathPlusItemListOld")
    @JvmName("plusListOld")
    @Deprecated("Use plusAssign instead", ReplaceWith("this.plusAssign(item)"))
    infix operator fun <T> DataClassPath<K, List<T>>.plus(item: T) {
        modifications.add(mapModification(Modification.ListAppend(listOf(item))))
    }

    @JsName("xDataClassPathPlusItemSetOld")
    @JvmName("plusSetOld")
    @Deprecated("Use plusAssign instead", ReplaceWith("this.plusAssign(item)"))
    infix operator fun <T> DataClassPath<K, Set<T>>.plus(item: T) {
        modifications.add(mapModification(Modification.SetAppend(setOf(item))))
    }

    @JsName("xDataClassPathPlusNumber")
    infix operator fun <T : Number> DataClassPath<K, T>.plusAssign(by: T) {
        modifications.add(mapModification(Modification.Increment(by)))
    }

    infix operator fun <T : Number> DataClassPath<K, T>.timesAssign(by: T) {
        modifications.add(mapModification(Modification.Multiply(by)))
    }

    @JsName("xDataClassPathPlusString")
    infix operator fun DataClassPath<K, String>.plusAssign(value: String) {
        modifications.add(mapModification(Modification.AppendString(value)))
    }

    @JsName("xDataClassPathPlusItemsList")
    infix operator fun <T> DataClassPath<K, List<T>>.plusAssign(items: List<T>) {
        modifications.add(mapModification(Modification.ListAppend(items)))
    }

    @JsName("xDataClassPathPlusItemsSet")
    infix operator fun <T> DataClassPath<K, Set<T>>.plusAssign(items: Set<T>) {
        modifications.add(mapModification(Modification.SetAppend(items)))
    }

    @JsName("xDataClassPathPlusItemList")
    @JvmName("plusList")
    infix operator fun <T> DataClassPath<K, List<T>>.plusAssign(item: T) {
        modifications.add(mapModification(Modification.ListAppend(listOf(item))))
    }

    @JsName("xDataClassPathPlusItemSet")
    @JvmName("plusSet")
    infix operator fun <T> DataClassPath<K, Set<T>>.plusAssign(item: T) {
        modifications.add(mapModification(Modification.SetAppend(setOf(item))))
    }

    @JsName("xDataClassPathListAddAll")
    infix fun <T : IsCodableAndHashable> DataClassPath<K, List<T>>.addAll(items: List<T>) {
        modifications.add(mapModification(Modification.ListAppend(items)))
    }

    @JsName("xDataClassPathSetAddAll")
    infix fun <T : IsCodableAndHashable> DataClassPath<K, Set<T>>.addAll(items: Set<T>) {
        modifications.add(mapModification(Modification.SetAppend(items)))
    }

    @JsName("xDataClassPathListRemove")
    @JvmName("listRemoveAll")
    infix fun <T : IsCodableAndHashable> DataClassPath<K, List<T>>.removeAll(condition: (DataClassPath<T, T>) -> Condition<T>) {
        modifications.add(mapModification(Modification.ListRemove(path<T>().let(condition))))
    }

    @JsName("xDataClassPathSetRemove")
    @JvmName("setRemoveAll")
    infix fun <T : IsCodableAndHashable> DataClassPath<K, Set<T>>.removeAll(condition: (DataClassPath<T, T>) -> Condition<T>) {
        modifications.add(mapModification(Modification.SetRemove(path<T>().let(condition))))
    }

    @JsName("xDataClassPathListRemoveAll")
    infix fun <T : IsCodableAndHashable> DataClassPath<K, List<T>>.removeAll(items: List<T>) {
        modifications.add(mapModification(Modification.ListRemoveInstances(items)))
    }

    @JsName("xDataClassPathSetRemoveAll")
    infix fun <T : IsCodableAndHashable> DataClassPath<K, Set<T>>.removeAll(items: Set<T>) {
        modifications.add(mapModification(Modification.SetRemoveInstances(items)))
    }

    @JsName("xDataClassPathListDropLast")
    @JvmName("listDropLast")
    fun <T : IsCodableAndHashable> DataClassPath<K, List<T>>.dropLast() {
        modifications.add(mapModification(Modification.ListDropLast()))
    }

    @JsName("xDataClassPathSetDropLast")
    @JvmName("setDropLast")
    fun <T : IsCodableAndHashable> DataClassPath<K, Set<T>>.dropLast() {
        modifications.add(mapModification(Modification.SetDropLast()))
    }

    @JsName("xDataClassPathListDropFirst")
    @JvmName("listDropFirst")
    fun <T : IsCodableAndHashable> DataClassPath<K, List<T>>.dropFirst() {
        modifications.add(mapModification(Modification.ListDropFirst()))
    }

    @JsName("xDataClassPathSetDropFirst")
    @JvmName("setDropFirst")
    fun <T : IsCodableAndHashable> DataClassPath<K, Set<T>>.dropFirst() {
        modifications.add(mapModification(Modification.SetDropFirst()))
    }

    @JsName("xDataClassPathListMap")
    @JvmName("listMap")
    inline infix fun <T : IsCodableAndHashable> DataClassPath<K, List<T>>.map(modification: ModificationBuilder<T>.(DataClassPath<T, T>) -> Unit) {
        modifications.add(
            mapModification(
                Modification.ListPerElement(
                    condition = Condition.Always<T>(),
                    ModificationBuilder<T>().apply {
                        modification(path())
                    }.build()
                )
            )
        )
    }

    @JsName("xDataClassPathSetMap")
    @JvmName("setMap")
    inline infix fun <T : IsCodableAndHashable> DataClassPath<K, Set<T>>.map(modification: ModificationBuilder<T>.(DataClassPath<T, T>) -> Unit) {
        modifications.add(
            mapModification(
                Modification.SetPerElement(
                    condition = Condition.Always<T>(),
                    ModificationBuilder<T>().apply {
                        modification(path())
                    }.build()
                )
            )
        )
    }

    @JsName("xDataClassPathListMapIf")
    @JvmName("listMapIf")
    inline fun <T : IsCodableAndHashable> DataClassPath<K, List<T>>.mapIf(
        condition: (DataClassPath<T, T>) -> Condition<T>,
        modification: ModificationBuilder<T>.(DataClassPath<T, T>) -> Unit,
    ) {
        modifications.add(
            mapModification(
                Modification.ListPerElement(
                    condition = path<T>().let(condition),
                    ModificationBuilder<T>().apply {
                        modification(path())
                    }.build()
                )
            )
        )
    }

    @JsName("xDataClassPathSetMapIf")
    @JvmName("setMapIf")
    inline fun <T : IsCodableAndHashable> DataClassPath<K, Set<T>>.mapIf(
        condition: (DataClassPath<T, T>) -> Condition<T>,
        modification: ModificationBuilder<T>.(DataClassPath<T, T>) -> Unit,
    ) {
        modifications.add(
            mapModification(
                Modification.SetPerElement(
                    condition = path<T>().let(condition),
                    ModificationBuilder<T>().apply {
                        modification(path())
                    }.build()
                )
            )
        )
    }

    @JsName("xDataClassPathPlusMap")
    infix operator fun <T : IsCodableAndHashable> DataClassPath<K, Map<String, T>>.plus(map: Map<String, T>) {
        modifications.add(mapModification(Modification.Combine(map)))
    }

    infix fun <T : IsCodableAndHashable> DataClassPath<K, Map<String, T>>.modifyByKey(map: Map<String, ModificationBuilder<T>.(DataClassPath<T, T>) -> Unit>) {
        modifications.add(mapModification(Modification.ModifyByKey(map.mapValues { modification(it.value) })))
    }

    infix fun <T : IsCodableAndHashable> DataClassPath<K, Map<String, T>>.removeKeys(fields: Set<String>) {
        modifications.add(mapModification(Modification.RemoveKeys(fields)))
    }

}