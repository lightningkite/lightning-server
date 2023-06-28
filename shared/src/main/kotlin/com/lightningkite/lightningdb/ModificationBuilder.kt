@file:SharedCode
package com.lightningkite.lightningdb

import com.lightningkite.khrysalis.IsCodableAndHashable
import com.lightningkite.khrysalis.JsName
import com.lightningkite.khrysalis.SharedCode

inline fun <T : IsCodableAndHashable> modification(setup: ModificationBuilder<T>.(DataClassPath<T, T>) -> Unit): Modification<T> {
    return ModificationBuilder<T>().apply {
        setup(this, path())
    }.build()
}

inline fun <T : IsCodableAndHashable> Modification<T>.and(setup: ModificationBuilder<T>.(DataClassPath<T, T>) -> Unit): Modification<T> {
    return ModificationBuilder<T>().apply {
        this.modifications.add(this@and)
        setup(this, path())
    }.build()
}

class ModificationBuilder<K : IsCodableAndHashable>() {
    val modifications = ArrayList<Modification<K>>()
    fun add(modification: Modification<K>) { modifications.add(modification) }
    fun build(): Modification<K> {
        if(modifications.size == 1) return modifications[0]
        else return Modification.Chain(modifications)
    }

    @JsName("assignCm")
    infix fun <T : IsCodableAndHashable> CMBuilder<K, T>.assign(value: T) {
        modifications.add(mapModification(Modification.Assign(value)))
    }

    @JsName("coerceAtMostCm")
    infix fun <T : Comparable<T>> CMBuilder<K, T>.coerceAtMost(value: T) {
        modifications.add(mapModification(Modification.CoerceAtMost(value)))
    }

    @JsName("coerceAtLeastCm")
    infix fun <T : Comparable<T>> CMBuilder<K, T>.coerceAtLeast(value: T) {
        modifications.add(mapModification(Modification.CoerceAtLeast(value)))
    }

    @JsName("plusAssignNumberCm")
    infix operator fun <T : Number> CMBuilder<K, T>.plusAssign(by: T) {
        modifications.add(mapModification(Modification.Increment(by)))
    }

    @JsName("timesAssignCm")
    infix operator fun <T : Number> CMBuilder<K, T>.timesAssign(by: T) {
        modifications.add(mapModification(Modification.Multiply(by)))
    }

    @JsName("plusAssignStringCm")
    infix operator fun CMBuilder<K, String>.plusAssign(value: String) {
        modifications.add(mapModification(Modification.AppendString(value)))
    }

    @JsName("plusAssignListCm")
    infix operator fun <T> CMBuilder<K, List<T>>.plusAssign(items: List<T>) {
        modifications.add(mapModification(Modification.ListAppend(items)))
    }

    @JsName("plusAssignSetCm")
    infix operator fun <T> CMBuilder<K, Set<T>>.plusAssign(items: Set<T>) {
        modifications.add(mapModification(Modification.SetAppend(items)))
    }

    @JsName("plusAssignItemListCm")
    @JvmName("plusList")
    infix operator fun <T> CMBuilder<K, List<T>>.plusAssign(item: T) {
        modifications.add(mapModification(Modification.ListAppend(listOf(item))))
    }

    @JsName("plusAssignItemSetCm")
    @JvmName("plusSet")
    infix operator fun <T> CMBuilder<K, Set<T>>.plusAssign(item: T) {
        modifications.add(mapModification(Modification.SetAppend(setOf(item))))
    }

    @JsName("plusAssignListAddAllCm")
    infix fun <T : IsCodableAndHashable> CMBuilder<K, List<T>>.addAll(items: List<T>) {
        modifications.add(mapModification(Modification.ListAppend(items)))
    }

    @JsName("plusAssignSetAddAllCm")
    infix fun <T : IsCodableAndHashable> CMBuilder<K, Set<T>>.addAll(items: Set<T>) {
        modifications.add(mapModification(Modification.SetAppend(items)))
    }

    @JsName("removeAllListCm")
    @JvmName("removeAllList")
    infix fun <T : IsCodableAndHashable> CMBuilder<K, List<T>>.removeAll(condition: (DataClassPath<T, T>) -> Condition<T>) {
        modifications.add(mapModification(Modification.ListRemove(path<T>().let(condition))))
    }

    @JsName("removeAllSetCm")
    @JvmName("removeAllSet")
    infix fun <T : IsCodableAndHashable> CMBuilder<K, Set<T>>.removeAll(condition: (DataClassPath<T, T>) -> Condition<T>) {
        modifications.add(mapModification(Modification.SetRemove(path<T>().let(condition))))
    }

    @JsName("removeAllItemsListCm")
    infix fun <T : IsCodableAndHashable> CMBuilder<K, List<T>>.removeAll(items: List<T>) {
        modifications.add(mapModification(Modification.ListRemoveInstances(items)))
    }

    @JsName("removeAllItemsSetCm")
    infix fun <T : IsCodableAndHashable> CMBuilder<K, Set<T>>.removeAll(items: Set<T>) {
        modifications.add(mapModification(Modification.SetRemoveInstances(items)))
    }

    @JsName("dropLastListCm")
    @JvmName("dropLastList")
    fun <T : IsCodableAndHashable> CMBuilder<K, List<T>>.dropLast() {
        modifications.add(mapModification(Modification.ListDropLast()))
    }

    @JsName("dropLastSetCm")
    @JvmName("dropLastSet")
    fun <T : IsCodableAndHashable> CMBuilder<K, Set<T>>.dropLast() {
        modifications.add(mapModification(Modification.SetDropLast()))
    }

    @JsName("dropFirstListCm")
    @JvmName("dropFirstList")
    fun <T : IsCodableAndHashable> CMBuilder<K, List<T>>.dropFirst() {
        modifications.add(mapModification(Modification.ListDropFirst()))
    }

    @JsName("dropFirstSetCm")
    @JvmName("dropFirstSet")
    fun <T : IsCodableAndHashable> CMBuilder<K, Set<T>>.dropFirst() {
        modifications.add(mapModification(Modification.SetDropFirst()))
    }

    @JsName("mapListCm")
    @JvmName("mapList")
    inline infix fun <T : IsCodableAndHashable> CMBuilder<K, List<T>>.map(modification: ModificationBuilder<T>.(DataClassPath<T, T>) -> Unit) {
        val builder = ModificationBuilder<T>()
        modification(builder, path())
        modifications.add(
            mapModification(
                Modification.ListPerElement(
                    condition = Condition.Always<T>(),
                    modification = builder.build()
                )
            )
        )
    }

    @JsName("mapSetCm")
    @JvmName("mapSet")
    inline infix fun <T : IsCodableAndHashable> CMBuilder<K, Set<T>>.map(modification: ModificationBuilder<T>.(DataClassPath<T, T>) -> Unit) {
        val builder = ModificationBuilder<T>()
        modification(builder, path())
        modifications.add(
            mapModification(
                Modification.SetPerElement(
                    condition = Condition.Always<T>(),
                    modification = builder.build()
                )
            )
        )
    }

    @JsName("mapIfListCm")
    @JvmName("mapIfList")
    inline fun <T : IsCodableAndHashable> CMBuilder<K, List<T>>.mapIf(
        condition: (DataClassPath<T, T>) -> Condition<T>,
        modification: ModificationBuilder<T>.(DataClassPath<T, T>) -> Unit,
    ) {
        val builder = ModificationBuilder<T>()
        modification(builder, path())
        modifications.add(
            mapModification(
                Modification.ListPerElement(
                    condition = path<T>().let(condition),
                    modification = builder.build()
                )
            )
        )
    }

    @JsName("mapIfSetCm")
    @JvmName("mapIfSet")
    inline fun <T : IsCodableAndHashable> CMBuilder<K, Set<T>>.mapIf(
        condition: (DataClassPath<T, T>) -> Condition<T>,
        modification: ModificationBuilder<T>.(DataClassPath<T, T>) -> Unit,
    ) {
        val builder = ModificationBuilder<T>()
        modification(builder, path())
        modifications.add(
            mapModification(
                Modification.SetPerElement(
                    condition = path<T>().let(condition),
                    modification = builder.build()
                )
            )
        )
    }

    @JsName("plusAssignMapCm")
    infix operator fun <T : IsCodableAndHashable> CMBuilder<K, Map<String, T>>.plusAssign(map: Map<String, T>) {
        modifications.add(mapModification(Modification.Combine(map)))
    }

    @JsName("modifyByKeyCm")
    infix fun <T : IsCodableAndHashable> CMBuilder<K, Map<String, T>>.modifyByKey(byKey: Map<String, ModificationBuilder<T>.(DataClassPath<T, T>) -> Unit>) {
        modifications.add(mapModification(Modification.ModifyByKey(byKey.mapValues { modification(it.value) })))
    }

    @JsName("removeKeysCm")
    infix fun <T : IsCodableAndHashable> CMBuilder<K, Map<String, T>>.removeKeys(fields: Set<String>) {
        modifications.add(mapModification(Modification.RemoveKeys(fields)))
    }

    // ---

    @JsName("assign")
    infix fun <T : IsCodableAndHashable> DataClassPath<K, T>.assign(value: T) {
        modifications.add(mapModification(Modification.Assign(value)))
    }

    @JsName("coerceAtMost")
    infix fun <T : Comparable<T>> DataClassPath<K, T>.coerceAtMost(value: T) {
        modifications.add(mapModification(Modification.CoerceAtMost(value)))
    }

    @JsName("coerceAtLeast")
    infix fun <T : Comparable<T>> DataClassPath<K, T>.coerceAtLeast(value: T) {
        modifications.add(mapModification(Modification.CoerceAtLeast(value)))
    }

    @JsName("plusAssignNumber")
    infix operator fun <T : Number> DataClassPath<K, T>.plusAssign(by: T) {
        modifications.add(mapModification(Modification.Increment(by)))
    }

    @JsName("timesAssign")
    infix operator fun <T : Number> DataClassPath<K, T>.timesAssign(by: T) {
        modifications.add(mapModification(Modification.Multiply(by)))
    }

    @JsName("plusAssignString")
    infix operator fun DataClassPath<K, String>.plusAssign(value: String) {
        modifications.add(mapModification(Modification.AppendString(value)))
    }

    @JsName("plusAssignList")
    infix operator fun <T> DataClassPath<K, List<T>>.plusAssign(items: List<T>) {
        modifications.add(mapModification(Modification.ListAppend(items)))
    }

    @JsName("plusAssignSet")
    infix operator fun <T> DataClassPath<K, Set<T>>.plusAssign(items: Set<T>) {
        modifications.add(mapModification(Modification.SetAppend(items)))
    }

    @JsName("plusAssignItemList")
    @JvmName("plusList")
    infix operator fun <T> DataClassPath<K, List<T>>.plusAssign(item: T) {
        modifications.add(mapModification(Modification.ListAppend(listOf(item))))
    }

    @JsName("plusAssignItemSet")
    @JvmName("plusSet")
    infix operator fun <T> DataClassPath<K, Set<T>>.plusAssign(item: T) {
        modifications.add(mapModification(Modification.SetAppend(setOf(item))))
    }

    @JsName("plusAssignListAddAll")
    infix fun <T : IsCodableAndHashable> DataClassPath<K, List<T>>.addAll(items: List<T>) {
        modifications.add(mapModification(Modification.ListAppend(items)))
    }

    @JsName("plusAssignSetAddAll")
    infix fun <T : IsCodableAndHashable> DataClassPath<K, Set<T>>.addAll(items: Set<T>) {
        modifications.add(mapModification(Modification.SetAppend(items)))
    }

    @JsName("removeAllList")
    @JvmName("removeAllList")
    infix fun <T : IsCodableAndHashable> DataClassPath<K, List<T>>.removeAll(condition: (DataClassPath<T, T>) -> Condition<T>) {
        modifications.add(mapModification(Modification.ListRemove(path<T>().let(condition))))
    }

    @JsName("removeAllSet")
    @JvmName("removeAllSet")
    infix fun <T : IsCodableAndHashable> DataClassPath<K, Set<T>>.removeAll(condition: (DataClassPath<T, T>) -> Condition<T>) {
        modifications.add(mapModification(Modification.SetRemove(path<T>().let(condition))))
    }

    @JsName("removeAllItemsList")
    infix fun <T : IsCodableAndHashable> DataClassPath<K, List<T>>.removeAll(items: List<T>) {
        modifications.add(mapModification(Modification.ListRemoveInstances(items)))
    }

    @JsName("removeAllItemsSet")
    infix fun <T : IsCodableAndHashable> DataClassPath<K, Set<T>>.removeAll(items: Set<T>) {
        modifications.add(mapModification(Modification.SetRemoveInstances(items)))
    }

    @JsName("dropLastList")
    @JvmName("dropLastList")
    fun <T : IsCodableAndHashable> DataClassPath<K, List<T>>.dropLast() {
        modifications.add(mapModification(Modification.ListDropLast()))
    }

    @JsName("dropLastSet")
    @JvmName("dropLastSet")
    fun <T : IsCodableAndHashable> DataClassPath<K, Set<T>>.dropLast() {
        modifications.add(mapModification(Modification.SetDropLast()))
    }

    @JsName("dropFirstList")
    @JvmName("dropFirstList")
    fun <T : IsCodableAndHashable> DataClassPath<K, List<T>>.dropFirst() {
        modifications.add(mapModification(Modification.ListDropFirst()))
    }

    @JsName("dropFirstSet")
    @JvmName("dropFirstSet")
    fun <T : IsCodableAndHashable> DataClassPath<K, Set<T>>.dropFirst() {
        modifications.add(mapModification(Modification.SetDropFirst()))
    }

    @JsName("mapList")
    @JvmName("mapList")
    inline infix fun <T : IsCodableAndHashable> DataClassPath<K, List<T>>.map(modification: ModificationBuilder<T>.(DataClassPath<T, T>) -> Unit) {
        val builder = ModificationBuilder<T>()
        modification(builder, path())
        modifications.add(
            mapModification(
                Modification.ListPerElement(
                    condition = Condition.Always<T>(),
                    modification = builder.build()
                )
            )
        )
    }

    @JsName("mapSet")
    @JvmName("mapSet")
    inline infix fun <T : IsCodableAndHashable> DataClassPath<K, Set<T>>.map(modification: ModificationBuilder<T>.(DataClassPath<T, T>) -> Unit) {
        val builder = ModificationBuilder<T>()
        modification(builder, path())
        modifications.add(
            mapModification(
                Modification.SetPerElement(
                    condition = Condition.Always<T>(),
                    modification = builder.build()
                )
            )
        )
    }

    @JsName("mapIfList")
    @JvmName("mapIfList")
    inline fun <T : IsCodableAndHashable> DataClassPath<K, List<T>>.mapIf(
        condition: (DataClassPath<T, T>) -> Condition<T>,
        modification: ModificationBuilder<T>.(DataClassPath<T, T>) -> Unit,
    ) {
        val builder = ModificationBuilder<T>()
        modification(builder, path())
        modifications.add(
            mapModification(
                Modification.ListPerElement(
                    condition = path<T>().let(condition),
                    modification = builder.build()
                )
            )
        )
    }

    @JsName("mapIfSet")
    @JvmName("mapIfSet")
    inline fun <T : IsCodableAndHashable> DataClassPath<K, Set<T>>.mapIf(
        condition: (DataClassPath<T, T>) -> Condition<T>,
        modification: ModificationBuilder<T>.(DataClassPath<T, T>) -> Unit,
    ) {
        val builder = ModificationBuilder<T>()
        modification(builder, path())
        modifications.add(
            mapModification(
                Modification.SetPerElement(
                    condition = path<T>().let(condition),
                    modification = builder.build()
                )
            )
        )
    }

    @JsName("plusAssignMap")
    infix operator fun <T : IsCodableAndHashable> DataClassPath<K, Map<String, T>>.plusAssign(map: Map<String, T>) {
        modifications.add(mapModification(Modification.Combine(map)))
    }

    @JsName("modifyByKey")
    infix fun <T : IsCodableAndHashable> DataClassPath<K, Map<String, T>>.modifyByKey(byKey: Map<String, ModificationBuilder<T>.(DataClassPath<T, T>) -> Unit>) {
        modifications.add(mapModification(Modification.ModifyByKey(byKey.mapValues { modification(it.value) })))
    }

    @JsName("removeKeys")
    infix fun <T : IsCodableAndHashable> DataClassPath<K, Map<String, T>>.removeKeys(fields: Set<String>) {
        modifications.add(mapModification(Modification.RemoveKeys(fields)))
    }
}