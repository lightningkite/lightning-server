@file:SharedCode
package com.lightningkite.lightningdb

import com.lightningkite.khrysalis.IsCodableAndHashable
import com.lightningkite.khrysalis.JsName
import com.lightningkite.khrysalis.SharedCode

inline fun <T : IsCodableAndHashable> modification(setup: ModificationBuilder<T>.(PropChain<T, T>) -> Unit): Modification<T> {
    return ModificationBuilder<T>().apply {
        setup(this, startChain())
    }.build()
}
inline fun <T : IsCodableAndHashable> Modification<T>.and(setup: ModificationBuilder<T>.(PropChain<T, T>) -> Unit): Modification<T> {
    return ModificationBuilder<T>().apply {
        modifications.add(this@and)
        setup(startChain())
    }.build()
}
class ModificationBuilder<K : IsCodableAndHashable>() {
    val modifications = ArrayList<Modification<K>>()
    fun add(modification: Modification<K>) = modifications.add(modification)
    fun build(): Modification<K> {
        if(modifications.size == 1)
            return modifications[0]
        else
            return Modification.Chain(modifications)
    }

    infix fun <T : IsCodableAndHashable> PropChain<K, T>.assign(value: T) {
        modifications.add(mapModification(Modification.Assign(value)))
    }

    infix fun <T : Comparable<T>> PropChain<K, T>.coerceAtMost(value: T) {
        modifications.add(mapModification(Modification.CoerceAtMost(value)))
    }

    infix fun <T : Comparable<T>> PropChain<K, T>.coerceAtLeast(value: T) {
        modifications.add(mapModification(Modification.CoerceAtLeast(value)))
    }

    @JsName("xPropChainPlusNumberOld")
    @Deprecated("Use plusAssign instead", ReplaceWith("this.plusAssign(by)"))
    infix operator fun <T : Number> PropChain<K, T>.plus(by: T) {
        modifications.add(mapModification(Modification.Increment(by)))
    }

    @Deprecated("Use timesAssign instead", ReplaceWith("this.timesAssign(by)"))
    infix operator fun <T : Number> PropChain<K, T>.times(by: T) {
        modifications.add(mapModification(Modification.Multiply(by)))
    }

    @JsName("xPropChainPlusStringOld")
    @Deprecated("Use plusAssign instead", ReplaceWith("this.plusAssign(value)"))
    infix operator fun PropChain<K, String>.plus(value: String) {
        modifications.add(mapModification(Modification.AppendString(value)))
    }

    @JsName("xPropChainPlusItemsListOld")
    @Deprecated("Use : Assign instead", ReplaceWith("this.plusAssign(items)"))
    infix operator fun <T> PropChain<K, List<T>>.plus(items: List<T>) {
        modifications.add(mapModification(Modification.ListAppend(items)))
    }

    @JsName("xPropChainPlusItemsSetOld")
    @Deprecated("Use : Assign instead", ReplaceWith("this.plusAssign(items)"))
    infix operator fun <T> PropChain<K, Set<T>>.plus(items: Set<T>) {
        modifications.add(mapModification(Modification.SetAppend(items)))
    }

    @JsName("xPropChainPlusItemListOld")
    @JvmName("plusListOld")
    @Deprecated("Use plusAssign instead", ReplaceWith("this.plusAssign(item)"))
    infix operator fun <T> PropChain<K, List<T>>.plus(item: T) {
        modifications.add(mapModification(Modification.ListAppend(listOf(item))))
    }

    @JsName("xPropChainPlusItemSetOld")
    @JvmName("plusSetOld")
    @Deprecated("Use plusAssign instead", ReplaceWith("this.plusAssign(item)"))
    infix operator fun <T> PropChain<K, Set<T>>.plus(item: T) {
        modifications.add(mapModification(Modification.SetAppend(setOf(item))))
    }

    @JsName("xPropChainPlusNumber")
    infix operator fun <T : Number> PropChain<K, T>.plusAssign(by: T) {
        modifications.add(mapModification(Modification.Increment(by)))
    }

    infix operator fun <T : Number> PropChain<K, T>.timesAssign(by: T) {
        modifications.add(mapModification(Modification.Multiply(by)))
    }

    @JsName("xPropChainPlusString")
    infix operator fun PropChain<K, String>.plusAssign(value: String) {
        modifications.add(mapModification(Modification.AppendString(value)))
    }

    @JsName("xPropChainPlusItemsList")
    infix operator fun <T> PropChain<K, List<T>>.plusAssign(items: List<T>) {
        modifications.add(mapModification(Modification.ListAppend(items)))
    }

    @JsName("xPropChainPlusItemsSet")
    infix operator fun <T> PropChain<K, Set<T>>.plusAssign(items: Set<T>) {
        modifications.add(mapModification(Modification.SetAppend(items)))
    }

    @JsName("xPropChainPlusItemList")
    @JvmName("plusList")
    infix operator fun <T> PropChain<K, List<T>>.plusAssign(item: T) {
        modifications.add(mapModification(Modification.ListAppend(listOf(item))))
    }

    @JsName("xPropChainPlusItemSet")
    @JvmName("plusSet")
    infix operator fun <T> PropChain<K, Set<T>>.plusAssign(item: T) {
        modifications.add(mapModification(Modification.SetAppend(setOf(item))))
    }

    @JsName("xPropChainListAddAll")
    infix fun <T : IsCodableAndHashable> PropChain<K, List<T>>.addAll(items: List<T>) {
        modifications.add(mapModification(Modification.ListAppend(items)))
    }

    @JsName("xPropChainSetAddAll")
    infix fun <T : IsCodableAndHashable> PropChain<K, Set<T>>.addAll(items: Set<T>) {
        modifications.add(mapModification(Modification.SetAppend(items)))
    }

    @JsName("xPropChainListRemove")
    @JvmName("listRemoveAll")
    infix fun <T : IsCodableAndHashable> PropChain<K, List<T>>.removeAll(condition: (PropChain<T, T>) -> Condition<T>) {
        modifications.add(mapModification(Modification.ListRemove(startChain<T>().let(condition))))
    }

    @JsName("xPropChainSetRemove")
    @JvmName("setRemoveAll")
    infix fun <T : IsCodableAndHashable> PropChain<K, Set<T>>.removeAll(condition: (PropChain<T, T>) -> Condition<T>) {
        modifications.add(mapModification(Modification.SetRemove(startChain<T>().let(condition))))
    }

    @JsName("xPropChainListRemoveAll")
    infix fun <T : IsCodableAndHashable> PropChain<K, List<T>>.removeAll(items: List<T>) {
        modifications.add(mapModification(Modification.ListRemoveInstances(items)))
    }

    @JsName("xPropChainSetRemoveAll")
    infix fun <T : IsCodableAndHashable> PropChain<K, Set<T>>.removeAll(items: Set<T>) {
        modifications.add(mapModification(Modification.SetRemoveInstances(items)))
    }

    @JsName("xPropChainListDropLast")
    @JvmName("listDropLast")
    fun <T : IsCodableAndHashable> PropChain<K, List<T>>.dropLast() {
        modifications.add(mapModification(Modification.ListDropLast()))
    }

    @JsName("xPropChainSetDropLast")
    @JvmName("setDropLast")
    fun <T : IsCodableAndHashable> PropChain<K, Set<T>>.dropLast() {
        modifications.add(mapModification(Modification.SetDropLast()))
    }

    @JsName("xPropChainListDropFirst")
    @JvmName("listDropFirst")
    fun <T : IsCodableAndHashable> PropChain<K, List<T>>.dropFirst() {
        modifications.add(mapModification(Modification.ListDropFirst()))
    }

    @JsName("xPropChainSetDropFirst")
    @JvmName("setDropFirst")
    fun <T : IsCodableAndHashable> PropChain<K, Set<T>>.dropFirst() {
        modifications.add(mapModification(Modification.SetDropFirst()))
    }

    @JsName("xPropChainListMap")
    @JvmName("listMap")
    inline infix fun <T : IsCodableAndHashable> PropChain<K, List<T>>.map(modification: ModificationBuilder<T>.(PropChain<T, T>) -> Unit) {
        modifications.add(
            mapModification(
                Modification.ListPerElement(
                    condition = Condition.Always<T>(),
                    ModificationBuilder<T>().apply {
                        modification(this, startChain())
                    }.build()
                )
            )
        )
    }

    @JsName("xPropChainSetMap")
    @JvmName("setMap")
    inline infix fun <T : IsCodableAndHashable> PropChain<K, Set<T>>.map(modification: ModificationBuilder<T>.(PropChain<T, T>) -> Unit) {
        modifications.add(
            mapModification(
                Modification.SetPerElement(
                    condition = Condition.Always<T>(),
                    ModificationBuilder<T>().apply {
                        modification(this, startChain())
                    }.build()
                )
            )
        )
    }

    @JsName("xPropChainListMapIf")
    @JvmName("listMapIf")
    inline fun <T : IsCodableAndHashable> PropChain<K, List<T>>.mapIf(
        condition: (PropChain<T, T>) -> Condition<T>,
        modification: ModificationBuilder<T>.(PropChain<T, T>) -> Unit,
    ) {
        modifications.add(
            mapModification(
                Modification.ListPerElement(
                    condition = startChain<T>().let(condition),
                    ModificationBuilder<T>().apply {
                        modification(this, startChain())
                    }.build()
                )
            )
        )
    }

    @JsName("xPropChainSetMapIf")
    @JvmName("setMapIf")
    inline fun <T : IsCodableAndHashable> PropChain<K, Set<T>>.mapIf(
        condition: (PropChain<T, T>) -> Condition<T>,
        modification: ModificationBuilder<T>.(PropChain<T, T>) -> Unit,
    ) {
        modifications.add(
            mapModification(
                Modification.SetPerElement(
                    condition = startChain<T>().let(condition),
                    ModificationBuilder<T>().apply {
                        modification(this, startChain())
                    }.build()
                )
            )
        )
    }

    @JsName("xPropChainPlusMap")
    infix operator fun <T : IsCodableAndHashable> PropChain<K, Map<String, T>>.plus(map: Map<String, T>) {
        modifications.add(mapModification(Modification.Combine(map)))
    }

    infix fun <T : IsCodableAndHashable> PropChain<K, Map<String, T>>.modifyByKey(modifications: Map<String, ModificationBuilder<T>.(PropChain<T, T>) -> Unit>) {
        this@ModificationBuilder.modifications.add(mapModification(Modification.ModifyByKey(modifications.mapValues { modification(it.value) })))
    }

    infix fun <T : IsCodableAndHashable> PropChain<K, Map<String, T>>.removeKeys(fields: Set<String>) {
        modifications.add(mapModification(Modification.RemoveKeys(fields)))
    }

}