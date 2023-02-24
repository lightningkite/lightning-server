package com.lightningkite.lightningdb

import com.lightningkite.khrysalis.IsCodableAndHashable
import kotlin.reflect.KProperty1

fun <V: IsCodableAndHashable> Modification<*>.partial(path: KeyPathPartial<V>): Modification<V>? = partial<V>(path.properties)
fun <V: IsCodableAndHashable> Modification<*>.partial(list: List<KProperty1<*, *>>): Modification<V>? {
    return when(this) {
        is Modification.OnField<*, *> -> if(list.first() == this.key) {
            if(list.size == 1) modification as Modification<V>
            else this.modification.partial(list.drop(1))
        } else null
        is Modification.SetPerElement<*> -> this.modification.partial(list)
        is Modification.ListPerElement<*> -> this.modification.partial(list)
        is Modification.Chain -> this.modifications.mapNotNull { it.partial<V>(list) }.let { Modification.Chain(it) }
        is Modification.IfNotNull -> this.modification.partial(list)
        is Modification.Assign -> Modification.Assign(list.fold(value) { value, prop -> (prop as KProperty1<Any?, Any?>).get(value) } as V)
        else -> throw Exception("We have no idea what the partial effect is!")
    }
}

fun Modification<*>.affects(path: KeyPathPartial<*>): Boolean = affects(path.properties)
fun Modification<*>.affects(list: List<KProperty1<*, *>>): Boolean {
    return when(this) {
        is Modification.OnField<*, *> -> if(list.first() == this.key) {
            if(list.size == 1) true
            else this.modification.affects(list.drop(1))
        } else false
        is Modification.SetPerElement<*> -> this.modification.affects(list)
        is Modification.ListPerElement<*> -> this.modification.affects(list)
        is Modification.Chain -> this.modifications.any { it.affects(list) }
        is Modification.IfNotNull -> this.modification.affects(list)
        else -> true
    }
}

fun Condition<*>.reads(path: KeyPathPartial<*>): Boolean = reads(path.properties)
fun Condition<*>.reads(list: List<KProperty1<*, *>>): Boolean {
    return when(this) {
        is Condition.OnField<*, *> -> if(list.first() == this.key) {
            if(list.size == 1) true
            else this.condition.reads(list.drop(1))
        } else false
        is Condition.ListAllElements<*> -> this.condition.reads(list)
        is Condition.ListAnyElements<*> -> this.condition.reads(list)
        is Condition.SetAllElements<*> -> this.condition.reads(list)
        is Condition.SetAnyElements<*> -> this.condition.reads(list)
        is Condition.And -> this.conditions.any { it.reads(list) }
        is Condition.Or -> this.conditions.any { it.reads(list) }
        is Condition.IfNotNull -> this.condition.reads(list)
        else -> true
    }
}

//internal fun Modification<*>.path(): List<KProperty1<*, *>> {
//    return when(this) {
//        is Modification.OnField<*, *> -> listOf(this.key) + path()
//        is Modification.SetPerElement<*> -> path()
//        is Modification.ListPerElement<*> -> path()
//        is Modification.IfNotNull -> path()
//        else -> listOf()
//    }
//}
//fun Modification<*>.matchesPath(list: List<KProperty1<*, *>>): Boolean {
//    if(list.isEmpty()) return true
//    return when(this) {
//        is Modification.OnField<*, *> -> list.firstOrNull() == this.key && this.modification.matchesPath(list.drop(1))
//        is Modification.SetPerElement<*> -> this.modification.matchesPath(list)
//        is Modification.ListPerElement<*> -> this.modification.matchesPath(list)
//        is Modification.Chain -> this.modifications.any { it.matchesPath(list) }
//        is Modification.IfNotNull -> this.modification.matchesPath(list)
//        else -> false
//    }
//}
fun Condition<*>.reads(field: KProperty1<*, *>): Boolean {
    return when(this) {
        is Condition.OnField<*, *> -> field == this.key
        is Condition.ListAllElements<*> -> this.condition.reads(field)
        is Condition.ListAnyElements<*> -> this.condition.reads(field)
        is Condition.SetAllElements<*> -> this.condition.reads(field)
        is Condition.SetAnyElements<*> -> this.condition.reads(field)
        is Condition.And -> this.conditions.any { it.reads(field) }
        is Condition.Or -> this.conditions.any { it.reads(field) }
        is Condition.IfNotNull -> this.condition.reads(field)
        else -> true
    }
}
fun <T> Modification<T>.reads(modification: Modification<T>): Boolean {
    return when(this) {
        is Modification.OnField<*, *> -> {
            val field = modification as? Modification.OnField<*, *> ?: return true
            @Suppress("UNCHECKED_CAST")
            field.key == this.key && (this.modification as Modification<Any?>).reads(field.modification as Modification<Any?>)
        }
        is Modification.SetPerElement<*> -> {
            @Suppress("UNCHECKED_CAST")
            ((modification as? Modification.SetPerElement<*>)?.modification)?.let {
                (this.modification as Modification<Any?>).reads(it as Modification<Any?>)
            } ?: false
        }
        is Modification.ListPerElement<*> -> {
            @Suppress("UNCHECKED_CAST")
            ((modification as? Modification.ListPerElement<*>)?.modification)?.let {
                (this.modification as Modification<Any?>).reads(it as Modification<Any?>)
            } ?: false
        }
        is Modification.Chain -> this.modifications.any { it.reads(modification) }
        is Modification.IfNotNull<*> -> {
            @Suppress("UNCHECKED_CAST")
            (this.modification as Modification<Any?>).reads((modification as? Modification.IfNotNull<Any?>)?.modification ?: modification as Modification<Any?>)
        }
        else -> true
    }
}
fun <T> Condition<T>.readsResultOf(modification: Modification<T>): Boolean {
    return when(this) {
        is Condition.Always -> false
        is Condition.Never -> false
        is Condition.OnField<*, *> -> {
            val field = modification as? Modification.OnField<*, *> ?: return false
            @Suppress("UNCHECKED_CAST")
            field.key == this.key && (this.condition as Condition<Any?>).readsResultOf(field.modification as Modification<Any?>)
        }
        is Condition.ListAllElements<*> -> @Suppress("UNCHECKED_CAST") (this.condition as Condition<Any?>).readsResultOf((modification as? Modification.ListPerElement<*>)?.modification as? Modification<Any?> ?: return false)
        is Condition.ListAnyElements<*> -> @Suppress("UNCHECKED_CAST") (this.condition as Condition<Any?>).readsResultOf((modification as? Modification.ListPerElement<*>)?.modification as? Modification<Any?> ?: return false)
        is Condition.SetAllElements<*> -> @Suppress("UNCHECKED_CAST") (this.condition as Condition<Any?>).readsResultOf((modification as? Modification.ListPerElement<*>)?.modification as? Modification<Any?> ?: return false)
        is Condition.SetAnyElements<*> -> @Suppress("UNCHECKED_CAST") (this.condition as Condition<Any?>).readsResultOf((modification as? Modification.ListPerElement<*>)?.modification as? Modification<Any?> ?: return false)
        is Condition.And -> this.conditions.any { it.readsResultOf(modification) }
        is Condition.Or -> this.conditions.any { it.readsResultOf(modification) }
        is Condition.IfNotNull<*> -> {
            @Suppress("UNCHECKED_CAST")
            (this.condition as Condition<Any?>).readsResultOf((modification as? Modification.IfNotNull<Any?>)?.modification ?: modification as Modification<Any?>)
        }
        else -> true
    }
}
fun <T> Condition<T>.guaranteedAfter(modification: Modification<T>): Boolean {
    return when(modification) {
        is Modification.Assign -> this(modification.value)
        is Modification.OnField<*, *> -> {
            val field = this as? Condition.OnField<*, *> ?: return true
            @Suppress("UNCHECKED_CAST")
            field.key != modification.key || (field.condition as Condition<Any?>).guaranteedAfter<Any?>(modification.modification as Modification<Any?>)
        }
        is Modification.SetPerElement<*> -> {
            @Suppress("UNCHECKED_CAST")
            ((this as? Condition.SetAllElements<Any?>)?.condition ?: (this as? Condition.SetAnyElements<Any?>)?.condition)?.let {
                (it as Condition<Any?>).guaranteedAfter<Any?>(modification.modification as Modification<Any?>)
            } ?: false
        }
        is Modification.ListPerElement<*> -> {
            @Suppress("UNCHECKED_CAST")
            ((this as? Condition.ListAllElements<Any?>)?.condition ?: (this as? Condition.ListAnyElements<Any?>)?.condition)?.let {
                (it as Condition<Any?>).guaranteedAfter<Any?>(modification.modification as Modification<Any?>)
            } ?: false
        }
        is Modification.Chain -> modification.modifications.all { guaranteedAfter(it) }
        is Modification.IfNotNull<*> -> {
            @Suppress("UNCHECKED_CAST")
            (this as Condition<Any?>).guaranteedAfter<Any?>(modification.modification as Modification<Any?>)
        }
        else -> false
    }
}

fun <T, V> Modification<T>.map(
    path: KeyPath<T, V>,
    onModification: (Modification<V>) -> Modification<V>,
): Modification<T> = (this as Modification<Any?>).map<V>(path.properties, onModification) as Modification<T>
private fun <V> Modification<*>.map(
    list: List<KProperty1<*, *>>,
    onModification: (Modification<V>) -> Modification<V>,
): Modification<*> {
    return when (this) {
        is Modification.Chain -> modifications.map { it.map(list, onModification) as Modification<Any?> }.let { Modification.Chain(it) }
        is Modification.OnField<*, *> -> if(list.first() == this.key) {
            if(list.size == 1) Modification.OnField(key = this.key as KProperty1<Any?, Any?>, modification = onModification(modification as Modification<V>) as Modification<Any?>) as Modification<Any?>
            else this.modification.map(list.drop(1), onModification)
        } else this
        is Modification.SetPerElement<*> -> (this.modification as Modification<Any?>).map(list, onModification)
        is Modification.ListPerElement<*> -> (this.modification as Modification<Any?>).map(list, onModification)
        is Modification.IfNotNull -> (this.modification as Modification<Any?>).map(list, onModification)
        is Modification.Assign -> {
            fun mapValue(
                value: Any?,
                list: List<KProperty1<Any?, Any?>>,
                onValue: (V) -> V,
            ): Any? {
                if(value == null) return null
                if(list.isEmpty()) return onValue(value as V)
                return list.first().setCopy(value, mapValue(list.first().get(value), list.drop(1), onValue))
            }
            Modification.Assign(mapValue(value, list as List<KProperty1<Any?, Any?>>) {
                (onModification(Modification.Assign(it)) as Modification.Assign).value
            })
        }
        else -> throw Exception("We have no idea what the partial effect is!")
    }
}
