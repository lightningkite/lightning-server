package com.lightningkite.ktordb

import kotlin.reflect.KProperty1


fun Condition<*>.referencesField(field: KProperty1<*, *>): Boolean = when (this) {
    is Condition.OnField<*, *> -> this.key.name == field.name || this.condition.referencesField(field)
    is Condition.And -> conditions.any { it.referencesField(field) }
    is Condition.Or -> conditions.any { it.referencesField(field) }
    is Condition.Not -> condition.referencesField(field)
    is Condition.AllElements<*> -> condition.referencesField(field)
    is Condition.AnyElements<*> -> condition.referencesField(field)
    is Condition.OnKey<*> -> condition.referencesField(field)
    is Condition.IfNotNull<*> -> condition.referencesField(field)
    else -> false
}
fun Modification<*>.referencesField(field: KProperty1<*, *>): Boolean = when (this) {
    is Modification.Chain -> modifications.any { it.referencesField(field) }
    is Modification.IfNotNull -> modification.referencesField(field)
    is Modification.PerElement<*> -> condition.referencesField(field)
    is Modification.ModifyByKey<*> -> map.values.any {it.referencesField(field)}
    is Modification.OnField<*, *> -> this.key.name == field.name || modification.referencesField(field)
    else -> false
}

fun Modification<*>.referencesFieldRead(field: KProperty1<*, *>): Boolean = when (this) {
    is Modification.Chain -> modifications.any { it.referencesFieldRead(field) }
    is Modification.Remove<*> -> this.condition.referencesField(field)
    is Modification.PerElement<*> -> condition.referencesField(field)
    is Modification.ModifyByKey<*> -> map.values.any { it.referencesFieldRead(field) }
    is Modification.OnField<*, *> -> modification.referencesFieldRead(field)
    else -> false
}

fun <T, V> Condition<T>.forField(field: KProperty1<T, V>): Condition<V> =
    forFieldOrNull(field) ?: Condition.Always()

fun <T, V> Condition<T>.forFieldOrNull(field: KProperty1<T, V>): Condition<V>? {
    return when (this) {
        is Condition.And -> conditions.mapNotNull { it.forFieldOrNull(field) }.takeUnless { it.isEmpty() }
            ?.let { Condition.And(it) }
        is Condition.Or -> conditions.mapNotNull { it.forFieldOrNull(field) }.takeUnless { it.isEmpty() }
            ?.let { Condition.Or(it) }
        is Condition.Not -> condition.forFieldOrNull(field)?.let { Condition.Not(it) }
        is Condition.OnField<*, *> -> if (this.key == field) this.condition as Condition<V> else null
        else -> null
    }
}

fun <T> Condition<List<T>>.perElement(): Condition<T> = when (this) {
    is Condition.AnyElements<T> -> this.condition
    else -> Condition.Always()
}

fun <T> Condition<T>.probablySingleResult(): Boolean = when (this) {
    is Condition.Always -> false
    is Condition.Never -> true
    is Condition.OnField<*, *> -> this.key.name == "_id" && when (val c = condition) {
        is Condition.Equal -> true
        is Condition.Inside -> c.values.size <= 1
        else -> false
    }
    is Condition.Or -> conditions.all { it.probablySingleResult() }
    is Condition.And -> conditions.any { it.probablySingleResult() }
    else -> false
}