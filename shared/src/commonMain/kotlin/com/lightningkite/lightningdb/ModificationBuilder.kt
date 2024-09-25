package com.lightningkite.lightningdb

import com.lightningkite.serialization.DataClassPath
import com.lightningkite.IsRawString
import kotlin.jvm.JvmName

inline fun <reified T> modification(setup: ModificationBuilder<T>.(DataClassPath<T, T>) -> Unit): Modification<T> {
    return ModificationBuilder<T>().apply {
        setup(this, path())
    }.build()
}

inline fun <T> modification(path: DataClassPath<T, T>, setup: ModificationBuilder<T>.(DataClassPath<T, T>) -> Unit): Modification<T> {
    return ModificationBuilder<T>().apply {
        setup(this, path)
    }.build()
}

inline fun <reified T> Modification<T>.and(setup: ModificationBuilder<T>.(DataClassPath<T, T>) -> Unit): Modification<T> {
    return ModificationBuilder<T>().apply {
        this.modifications.add(this@and)
        setup(this, path())
    }.build()
}

class ModificationBuilder<K>() {
    val modifications = ArrayList<Modification<K>>()
    fun add(modification: Modification<K>) {
        modifications.add(modification)
    }

    fun build(): Modification<K> {
        if (modifications.size == 1) return modifications[0]
        else return Modification.Chain(modifications)
    }

    infix fun <T> DataClassPath<K, T>.assign(value: T) {
        modifications.add(mapModification(Modification.Assign(value)))
    }

    infix fun <T : Comparable<T>> DataClassPath<K, T>.coerceAtMost(value: T) {
        modifications.add(mapModification(Modification.CoerceAtMost(value)))
    }

    infix fun <T : Comparable<T>> DataClassPath<K, T>.coerceAtLeast(value: T) {
        modifications.add(mapModification(Modification.CoerceAtLeast(value)))
    }

    infix operator fun <T : Number> DataClassPath<K, T>.plusAssign(by: T) {
        modifications.add(mapModification(Modification.Increment(by)))
    }

    infix operator fun <T : Number> DataClassPath<K, T>.timesAssign(by: T) {
        modifications.add(mapModification(Modification.Multiply(by)))
    }

    infix operator fun DataClassPath<K, String>.plusAssign(value: String) {
        modifications.add(mapModification(Modification.AppendString(value)))
    }

    @JvmName("plusAssignRaw")
    infix operator fun <T : IsRawString> DataClassPath<K, T>.plusAssign(value: String) {
        modifications.add(mapModification(Modification.AppendRawString(value)))
    }

    infix operator fun <T> DataClassPath<K, List<T>>.plusAssign(items: List<T>) {
        modifications.add(mapModification(Modification.ListAppend(items)))
    }

    infix operator fun <T> DataClassPath<K, Set<T>>.plusAssign(items: Set<T>) {
        modifications.add(mapModification(Modification.SetAppend(items)))
    }

    @JvmName("plusList")
    infix operator fun <T> DataClassPath<K, List<T>>.plusAssign(item: T) {
        modifications.add(mapModification(Modification.ListAppend(listOf(item))))
    }

    @JvmName("plusSet")
    infix operator fun <T> DataClassPath<K, Set<T>>.plusAssign(item: T) {
        modifications.add(mapModification(Modification.SetAppend(setOf(item))))
    }

    infix fun <T> DataClassPath<K, List<T>>.addAll(items: List<T>) {
        modifications.add(mapModification(Modification.ListAppend(items)))
    }

    infix fun <T> DataClassPath<K, Set<T>>.addAll(items: Set<T>) {
        modifications.add(mapModification(Modification.SetAppend(items)))
    }

    @JvmName("removeAllList")
    inline infix fun <reified T> DataClassPath<K, List<T>>.removeAll(condition: (DataClassPath<T, T>) -> Condition<T>) {
        modifications.add(mapModification(Modification.ListRemove(path<T>().let(condition))))
    }

    @JvmName("removeAllSet")
    inline infix fun <reified T> DataClassPath<K, Set<T>>.removeAll(condition: (DataClassPath<T, T>) -> Condition<T>) {
        modifications.add(mapModification(Modification.SetRemove(path<T>().let(condition))))
    }

    infix fun <T> DataClassPath<K, List<T>>.removeAll(items: List<T>) {
        modifications.add(mapModification(Modification.ListRemoveInstances(items)))
    }

    infix fun <T> DataClassPath<K, Set<T>>.removeAll(items: Set<T>) {
        modifications.add(mapModification(Modification.SetRemoveInstances(items)))
    }

    @JvmName("dropLastList")
    fun <T> DataClassPath<K, List<T>>.dropLast() {
        modifications.add(mapModification(Modification.ListDropLast()))
    }

    @JvmName("dropLastSet")
    fun <T> DataClassPath<K, Set<T>>.dropLast() {
        modifications.add(mapModification(Modification.SetDropLast()))
    }

    @JvmName("dropFirstList")
    fun <T> DataClassPath<K, List<T>>.dropFirst() {
        modifications.add(mapModification(Modification.ListDropFirst()))
    }

    @JvmName("dropFirstSet")
    fun <T> DataClassPath<K, Set<T>>.dropFirst() {
        modifications.add(mapModification(Modification.SetDropFirst()))
    }

    @JvmName("forEachList")
    inline infix fun <reified T> DataClassPath<K, List<T>>.forEach(modification: ModificationBuilder<T>.(DataClassPath<T, T>) -> Unit) {
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

    @JvmName("forEachSet")
    inline infix fun <reified T> DataClassPath<K, Set<T>>.forEach(modification: ModificationBuilder<T>.(DataClassPath<T, T>) -> Unit) {
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

    @JvmName("forEachIfList")
    inline fun <reified T> DataClassPath<K, List<T>>.forEachIf(
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

    @JvmName("forEachIfSet")
    inline fun <reified T> DataClassPath<K, Set<T>>.forEachIf(
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

    infix operator fun <T> DataClassPath<K, Map<String, T>>.plusAssign(map: Map<String, T>) {
        modifications.add(mapModification(Modification.Combine(map)))
    }

    inline infix fun <reified T> DataClassPath<K, Map<String, T>>.modifyByKey(byKey: Map<String, ModificationBuilder<T>.(DataClassPath<T, T>) -> Unit>) {
        modifications.add(mapModification(Modification.ModifyByKey(byKey.mapValues { modification(it.value) })))
    }

    infix fun <T> DataClassPath<K, Map<String, T>>.removeKeys(fields: Set<String>) {
        modifications.add(mapModification(Modification.RemoveKeys(fields)))
    }
}