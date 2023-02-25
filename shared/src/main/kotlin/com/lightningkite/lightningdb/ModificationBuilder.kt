@file:SharedCode
package com.lightningkite.lightningdb

import com.lightningkite.khrysalis.IsCodableAndHashable
import com.lightningkite.khrysalis.JsName
import com.lightningkite.khrysalis.SharedCode

inline fun <T : IsCodableAndHashable> modification(setup: ModificationBuilder<T>.(KeyPath<T, T>) -> Unit): Modification<T> {
    return ModificationBuilder<T>().apply {
        setup(startChain())
    }.build()
}

inline fun <T : IsCodableAndHashable> Modification<T>.and(setup: ModificationBuilder<T>.(KeyPath<T, T>) -> Unit): Modification<T> {
    return ModificationBuilder<T>().apply {
        modifications.add(this@and)
        setup(startChain())
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
    infix fun <T : IsCodableAndHashable> CMBuilder<K, List<T>>.removeAll(condition: (KeyPath<T, T>) -> Condition<T>) {
        modifications.add(mapModification(Modification.ListRemove(startChain<T>().let(condition))))
    }

    @JsName("xCMBuilderSetRemove")
    @JvmName("setRemoveAll")
    infix fun <T : IsCodableAndHashable> CMBuilder<K, Set<T>>.removeAll(condition: (KeyPath<T, T>) -> Condition<T>) {
        modifications.add(mapModification(Modification.SetRemove(startChain<T>().let(condition))))
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
    inline infix fun <T : IsCodableAndHashable> CMBuilder<K, List<T>>.map(modification: ModificationBuilder<T>.(KeyPath<T, T>) -> Unit) {
        modifications.add(
            mapModification(
                Modification.ListPerElement(
                    condition = Condition.Always<T>(),
                    ModificationBuilder<T>().apply {
                        modification(startChain())
                    }.build()
                )
            )
        )
    }

    @JsName("xCMBuilderSetMap")
    @JvmName("setMap")
    inline infix fun <T : IsCodableAndHashable> CMBuilder<K, Set<T>>.map(modification: ModificationBuilder<T>.(KeyPath<T, T>) -> Unit) {
        modifications.add(
            mapModification(
                Modification.SetPerElement(
                    condition = Condition.Always<T>(),
                    ModificationBuilder<T>().apply {
                        modification(startChain())
                    }.build()
                )
            )
        )
    }

    @JsName("xCMBuilderListMapIf")
    @JvmName("listMapIf")
    inline fun <T : IsCodableAndHashable> CMBuilder<K, List<T>>.mapIf(
        condition: (KeyPath<T, T>) -> Condition<T>,
        modification: ModificationBuilder<T>.(KeyPath<T, T>) -> Unit,
    ) {
        modifications.add(
            mapModification(
                Modification.ListPerElement(
                    condition = startChain<T>().let(condition),
                    ModificationBuilder<T>().apply {
                        modification(startChain())
                    }.build()
                )
            )
        )
    }

    @JsName("xCMBuilderSetMapIf")
    @JvmName("setMapIf")
    inline fun <T : IsCodableAndHashable> CMBuilder<K, Set<T>>.mapIf(
        condition: (KeyPath<T, T>) -> Condition<T>,
        modification: ModificationBuilder<T>.(KeyPath<T, T>) -> Unit,
    ) {
        modifications.add(
            mapModification(
                Modification.SetPerElement(
                    condition = startChain<T>().let(condition),
                    ModificationBuilder<T>().apply {
                        modification(startChain())
                    }.build()
                )
            )
        )
    }

    @JsName("xCMBuilderPlusMap")
    infix operator fun <T : IsCodableAndHashable> CMBuilder<K, Map<String, T>>.plus(map: Map<String, T>) {
        modifications.add(mapModification(Modification.Combine(map)))
    }

    infix fun <T : IsCodableAndHashable> CMBuilder<K, Map<String, T>>.modifyByKey(map: Map<String, ModificationBuilder<T>.(KeyPath<T, T>) -> Unit>) {
        modifications.add(mapModification(Modification.ModifyByKey(map.mapValues { modification(it.value) })))
    }

    infix fun <T : IsCodableAndHashable> CMBuilder<K, Map<String, T>>.removeKeys(fields: Set<String>) {
        modifications.add(mapModification(Modification.RemoveKeys(fields)))
    }

    infix fun <T : IsCodableAndHashable> KeyPath<K, T>.assign(value: T) {
        modifications.add(mapModification(Modification.Assign(value)))
    }

    infix fun <T : Comparable<T>> KeyPath<K, T>.coerceAtMost(value: T) {
        modifications.add(mapModification(Modification.CoerceAtMost(value)))
    }

    infix fun <T : Comparable<T>> KeyPath<K, T>.coerceAtLeast(value: T) {
        modifications.add(mapModification(Modification.CoerceAtLeast(value)))
    }

    @JsName("xKeyPathPlusNumberOld")
    @Deprecated("Use plusAssign instead", ReplaceWith("this.plusAssign(by)"))
    infix operator fun <T : Number> KeyPath<K, T>.plus(by: T) {
        modifications.add(mapModification(Modification.Increment(by)))
    }

    @Deprecated("Use timesAssign instead", ReplaceWith("this.timesAssign(by)"))
    infix operator fun <T : Number> KeyPath<K, T>.times(by: T) {
        modifications.add(mapModification(Modification.Multiply(by)))
    }

    @JsName("xKeyPathPlusStringOld")
    @Deprecated("Use plusAssign instead", ReplaceWith("this.plusAssign(value)"))
    infix operator fun KeyPath<K, String>.plus(value: String) {
        modifications.add(mapModification(Modification.AppendString(value)))
    }

    @JsName("xKeyPathPlusItemsListOld")
    @Deprecated("Use : Assign instead", ReplaceWith("this.plusAssign(items)"))
    infix operator fun <T> KeyPath<K, List<T>>.plus(items: List<T>) {
        modifications.add(mapModification(Modification.ListAppend(items)))
    }

    @JsName("xKeyPathPlusItemsSetOld")
    @Deprecated("Use : Assign instead", ReplaceWith("this.plusAssign(items)"))
    infix operator fun <T> KeyPath<K, Set<T>>.plus(items: Set<T>) {
        modifications.add(mapModification(Modification.SetAppend(items)))
    }

    @JsName("xKeyPathPlusItemListOld")
    @JvmName("plusListOld")
    @Deprecated("Use plusAssign instead", ReplaceWith("this.plusAssign(item)"))
    infix operator fun <T> KeyPath<K, List<T>>.plus(item: T) {
        modifications.add(mapModification(Modification.ListAppend(listOf(item))))
    }

    @JsName("xKeyPathPlusItemSetOld")
    @JvmName("plusSetOld")
    @Deprecated("Use plusAssign instead", ReplaceWith("this.plusAssign(item)"))
    infix operator fun <T> KeyPath<K, Set<T>>.plus(item: T) {
        modifications.add(mapModification(Modification.SetAppend(setOf(item))))
    }

    @JsName("xKeyPathPlusNumber")
    infix operator fun <T : Number> KeyPath<K, T>.plusAssign(by: T) {
        modifications.add(mapModification(Modification.Increment(by)))
    }

    infix operator fun <T : Number> KeyPath<K, T>.timesAssign(by: T) {
        modifications.add(mapModification(Modification.Multiply(by)))
    }

    @JsName("xKeyPathPlusString")
    infix operator fun KeyPath<K, String>.plusAssign(value: String) {
        modifications.add(mapModification(Modification.AppendString(value)))
    }

    @JsName("xKeyPathPlusItemsList")
    infix operator fun <T> KeyPath<K, List<T>>.plusAssign(items: List<T>) {
        modifications.add(mapModification(Modification.ListAppend(items)))
    }

    @JsName("xKeyPathPlusItemsSet")
    infix operator fun <T> KeyPath<K, Set<T>>.plusAssign(items: Set<T>) {
        modifications.add(mapModification(Modification.SetAppend(items)))
    }

    @JsName("xKeyPathPlusItemList")
    @JvmName("plusList")
    infix operator fun <T> KeyPath<K, List<T>>.plusAssign(item: T) {
        modifications.add(mapModification(Modification.ListAppend(listOf(item))))
    }

    @JsName("xKeyPathPlusItemSet")
    @JvmName("plusSet")
    infix operator fun <T> KeyPath<K, Set<T>>.plusAssign(item: T) {
        modifications.add(mapModification(Modification.SetAppend(setOf(item))))
    }

    @JsName("xKeyPathListAddAll")
    infix fun <T : IsCodableAndHashable> KeyPath<K, List<T>>.addAll(items: List<T>) {
        modifications.add(mapModification(Modification.ListAppend(items)))
    }

    @JsName("xKeyPathSetAddAll")
    infix fun <T : IsCodableAndHashable> KeyPath<K, Set<T>>.addAll(items: Set<T>) {
        modifications.add(mapModification(Modification.SetAppend(items)))
    }

    @JsName("xKeyPathListRemove")
    @JvmName("listRemoveAll")
    infix fun <T : IsCodableAndHashable> KeyPath<K, List<T>>.removeAll(condition: (KeyPath<T, T>) -> Condition<T>) {
        modifications.add(mapModification(Modification.ListRemove(startChain<T>().let(condition))))
    }

    @JsName("xKeyPathSetRemove")
    @JvmName("setRemoveAll")
    infix fun <T : IsCodableAndHashable> KeyPath<K, Set<T>>.removeAll(condition: (KeyPath<T, T>) -> Condition<T>) {
        modifications.add(mapModification(Modification.SetRemove(startChain<T>().let(condition))))
    }

    @JsName("xKeyPathListRemoveAll")
    infix fun <T : IsCodableAndHashable> KeyPath<K, List<T>>.removeAll(items: List<T>) {
        modifications.add(mapModification(Modification.ListRemoveInstances(items)))
    }

    @JsName("xKeyPathSetRemoveAll")
    infix fun <T : IsCodableAndHashable> KeyPath<K, Set<T>>.removeAll(items: Set<T>) {
        modifications.add(mapModification(Modification.SetRemoveInstances(items)))
    }

    @JsName("xKeyPathListDropLast")
    @JvmName("listDropLast")
    fun <T : IsCodableAndHashable> KeyPath<K, List<T>>.dropLast() {
        modifications.add(mapModification(Modification.ListDropLast()))
    }

    @JsName("xKeyPathSetDropLast")
    @JvmName("setDropLast")
    fun <T : IsCodableAndHashable> KeyPath<K, Set<T>>.dropLast() {
        modifications.add(mapModification(Modification.SetDropLast()))
    }

    @JsName("xKeyPathListDropFirst")
    @JvmName("listDropFirst")
    fun <T : IsCodableAndHashable> KeyPath<K, List<T>>.dropFirst() {
        modifications.add(mapModification(Modification.ListDropFirst()))
    }

    @JsName("xKeyPathSetDropFirst")
    @JvmName("setDropFirst")
    fun <T : IsCodableAndHashable> KeyPath<K, Set<T>>.dropFirst() {
        modifications.add(mapModification(Modification.SetDropFirst()))
    }

    @JsName("xKeyPathListMap")
    @JvmName("listMap")
    inline infix fun <T : IsCodableAndHashable> KeyPath<K, List<T>>.map(modification: ModificationBuilder<T>.(KeyPath<T, T>) -> Unit) {
        modifications.add(
            mapModification(
                Modification.ListPerElement(
                    condition = Condition.Always<T>(),
                    ModificationBuilder<T>().apply {
                        modification(startChain())
                    }.build()
                )
            )
        )
    }

    @JsName("xKeyPathSetMap")
    @JvmName("setMap")
    inline infix fun <T : IsCodableAndHashable> KeyPath<K, Set<T>>.map(modification: ModificationBuilder<T>.(KeyPath<T, T>) -> Unit) {
        modifications.add(
            mapModification(
                Modification.SetPerElement(
                    condition = Condition.Always<T>(),
                    ModificationBuilder<T>().apply {
                        modification(startChain())
                    }.build()
                )
            )
        )
    }

    @JsName("xKeyPathListMapIf")
    @JvmName("listMapIf")
    inline fun <T : IsCodableAndHashable> KeyPath<K, List<T>>.mapIf(
        condition: (KeyPath<T, T>) -> Condition<T>,
        modification: ModificationBuilder<T>.(KeyPath<T, T>) -> Unit,
    ) {
        modifications.add(
            mapModification(
                Modification.ListPerElement(
                    condition = startChain<T>().let(condition),
                    ModificationBuilder<T>().apply {
                        modification(startChain())
                    }.build()
                )
            )
        )
    }

    @JsName("xKeyPathSetMapIf")
    @JvmName("setMapIf")
    inline fun <T : IsCodableAndHashable> KeyPath<K, Set<T>>.mapIf(
        condition: (KeyPath<T, T>) -> Condition<T>,
        modification: ModificationBuilder<T>.(KeyPath<T, T>) -> Unit,
    ) {
        modifications.add(
            mapModification(
                Modification.SetPerElement(
                    condition = startChain<T>().let(condition),
                    ModificationBuilder<T>().apply {
                        modification(startChain())
                    }.build()
                )
            )
        )
    }

    @JsName("xKeyPathPlusMap")
    infix operator fun <T : IsCodableAndHashable> KeyPath<K, Map<String, T>>.plus(map: Map<String, T>) {
        modifications.add(mapModification(Modification.Combine(map)))
    }

    infix fun <T : IsCodableAndHashable> KeyPath<K, Map<String, T>>.modifyByKey(map: Map<String, ModificationBuilder<T>.(KeyPath<T, T>) -> Unit>) {
        modifications.add(mapModification(Modification.ModifyByKey(map.mapValues { modification(it.value) })))
    }

    infix fun <T : IsCodableAndHashable> KeyPath<K, Map<String, T>>.removeKeys(fields: Set<String>) {
        modifications.add(mapModification(Modification.RemoveKeys(fields)))
    }

}