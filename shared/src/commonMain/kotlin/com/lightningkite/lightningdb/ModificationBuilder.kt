@file:SharedCode

package com.lightningkite.lightningdb

import com.lightningkite.IsRawString
import com.lightningkite.khrysalis.IsCodableAndHashable
import com.lightningkite.khrysalis.JsName
import com.lightningkite.khrysalis.SharedCode
import kotlin.jvm.JvmName

inline fun <reified T : IsCodableAndHashable> modification(setup: ModificationBuilder<T>.(DataClassPath<T, T>) -> Unit): Modification<T> {
    return ModificationBuilder<T>().apply {
        setup(this, path())
    }.build()
}

inline fun <T : IsCodableAndHashable> modification(
    path: DataClassPath<T, T>,
    setup: ModificationBuilder<T>.(DataClassPath<T, T>) -> Unit
): Modification<T> {
    return ModificationBuilder<T>().apply {
        setup(this, path)
    }.build()
}

inline fun <reified T : IsCodableAndHashable> Modification<T>.and(setup: ModificationBuilder<T>.(DataClassPath<T, T>) -> Unit): Modification<T> {
    return ModificationBuilder<T>().apply {
        this.modifications.add(this@and)
        setup(this, path())
    }.build()
}

class ModificationBuilder<K : IsCodableAndHashable>() {
    val modifications = ArrayList<Modification<K>>()
    fun add(modification: Modification<K>) {
        modifications.add(modification)
    }

    fun build(): Modification<K> {
        if (modifications.size == 1) return modifications[0]
        else return Modification.Chain(modifications)
    }

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

    @JvmName("plusAssignRaw") @JsName("plusAssignRawString")
    infix operator fun <T : IsRawString> DataClassPath<K, T>.plusAssign(value: String) {
        modifications.add(mapModification(Modification.AppendRawString(value)))
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
    inline infix fun <reified T : IsCodableAndHashable> DataClassPath<K, List<T>>.removeAll(condition: (DataClassPath<T, T>) -> Condition<T>) {
        modifications.add(mapModification(Modification.ListRemove(path<T>().let(condition))))
    }

    @JsName("removeAllSet")
    @JvmName("removeAllSet")
    inline infix fun <reified T : IsCodableAndHashable> DataClassPath<K, Set<T>>.removeAll(condition: (DataClassPath<T, T>) -> Condition<T>) {
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

    @JsName("forEachList")
    @JvmName("forEachList")
    inline infix fun <reified T : IsCodableAndHashable> DataClassPath<K, List<T>>.forEach(modification: ModificationBuilder<T>.(DataClassPath<T, T>) -> Unit) {
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

    @JsName("forEachSet")
    @JvmName("forEachSet")
    inline infix fun <reified T : IsCodableAndHashable> DataClassPath<K, Set<T>>.forEach(modification: ModificationBuilder<T>.(DataClassPath<T, T>) -> Unit) {
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

    @JsName("forEachIfList")
    @JvmName("forEachIfList")
    inline fun <reified T : IsCodableAndHashable> DataClassPath<K, List<T>>.forEachIf(
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

    @JsName("forEachIfSet")
    @JvmName("forEachIfSet")
    inline fun <reified T : IsCodableAndHashable> DataClassPath<K, Set<T>>.forEachIf(
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
    inline infix fun <reified T : IsCodableAndHashable> DataClassPath<K, Map<String, T>>.modifyByKey(byKey: Map<String, ModificationBuilder<T>.(DataClassPath<T, T>) -> Unit>) {
        modifications.add(mapModification(Modification.ModifyByKey(byKey.mapValues { modification(it.value) })))
    }

    @JsName("removeKeys")
    infix fun <T : IsCodableAndHashable> DataClassPath<K, Map<String, T>>.removeKeys(fields: Set<String>) {
        modifications.add(mapModification(Modification.RemoveKeys(fields)))
    }
}