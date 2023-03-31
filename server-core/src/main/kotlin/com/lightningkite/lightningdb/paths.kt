package com.lightningkite.lightningdb

import kotlin.reflect.KProperty1


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
fun Modification<*>.matchesPath(field: KProperty1<*, *>): Boolean {
    return when (this) {
        is Modification.OnField<*, *> -> field == this.key
        is Modification.SetPerElement<*> -> this.modification.matchesPath(field)
        is Modification.ListPerElement<*> -> this.modification.matchesPath(field)
        is Modification.Chain -> this.modifications.any { it.matchesPath(field) }
        is Modification.IfNotNull -> this.modification.matchesPath(field)
        else -> true
    }
}

fun Condition<*>.matchesPath(field: KProperty1<*, *>): Boolean {
    return when (this) {
        is Condition.OnField<*, *> -> field == this.key
        is Condition.ListAllElements<*> -> this.condition.matchesPath(field)
        is Condition.ListAnyElements<*> -> this.condition.matchesPath(field)
        is Condition.SetAllElements<*> -> this.condition.matchesPath(field)
        is Condition.SetAnyElements<*> -> this.condition.matchesPath(field)
        is Condition.And -> this.conditions.any { it.matchesPath(field) }
        is Condition.Or -> this.conditions.any { it.matchesPath(field) }
        is Condition.IfNotNull -> this.condition.matchesPath(field)
        else -> true
    }
}

fun <T> Modification<T>.matchesPath(modification: Modification<T>): Boolean {
    return when (this) {
        is Modification.OnField<*, *> -> {
            val field = modification as? Modification.OnField<*, *> ?: return true
            @Suppress("UNCHECKED_CAST")
            field.key == this.key && (this.modification as Modification<Any?>).matchesPath(field.modification as Modification<Any?>)
        }

        is Modification.SetPerElement<*> -> {
            @Suppress("UNCHECKED_CAST")
            ((modification as? Modification.SetPerElement<*>)?.modification)?.let {
                (this.modification as Modification<Any?>).matchesPath(it as Modification<Any?>)
            } ?: false
        }

        is Modification.ListPerElement<*> -> {
            @Suppress("UNCHECKED_CAST")
            ((modification as? Modification.ListPerElement<*>)?.modification)?.let {
                (this.modification as Modification<Any?>).matchesPath(it as Modification<Any?>)
            } ?: false
        }

        is Modification.Chain -> this.modifications.any { it.matchesPath(modification) }
        is Modification.IfNotNull<*> -> {
            @Suppress("UNCHECKED_CAST")
            (this.modification as Modification<Any?>).matchesPath(
                (modification as? Modification.IfNotNull<Any?>)?.modification ?: modification as Modification<Any?>
            )
        }

        else -> true
    }
}

fun <T> Condition<T>.matchesPath(modification: Modification<T>): Boolean {
    return when (this) {
        is Condition.Always -> false
        is Condition.Never -> false
        is Condition.OnField<*, *> -> {
            val field = modification as? Modification.OnField<*, *> ?: return false
            @Suppress("UNCHECKED_CAST")
            field.key == this.key && (this.condition as Condition<Any?>).matchesPath(field.modification as Modification<Any?>)
        }

        is Condition.ListAllElements<*> -> @Suppress("UNCHECKED_CAST") (this.condition as Condition<Any?>).matchesPath(
            (modification as? Modification.ListPerElement<*>)?.modification as? Modification<Any?> ?: return false
        )

        is Condition.ListAnyElements<*> -> @Suppress("UNCHECKED_CAST") (this.condition as Condition<Any?>).matchesPath(
            (modification as? Modification.ListPerElement<*>)?.modification as? Modification<Any?> ?: return false
        )

        is Condition.SetAllElements<*> -> @Suppress("UNCHECKED_CAST") (this.condition as Condition<Any?>).matchesPath(
            (modification as? Modification.ListPerElement<*>)?.modification as? Modification<Any?> ?: return false
        )

        is Condition.SetAnyElements<*> -> @Suppress("UNCHECKED_CAST") (this.condition as Condition<Any?>).matchesPath(
            (modification as? Modification.ListPerElement<*>)?.modification as? Modification<Any?> ?: return false
        )

        is Condition.And -> this.conditions.any { it.matchesPath(modification) }
        is Condition.Or -> this.conditions.any { it.matchesPath(modification) }
        is Condition.IfNotNull<*> -> {
            @Suppress("UNCHECKED_CAST")
            (this.condition as Condition<Any?>).matchesPath(
                (modification as? Modification.IfNotNull<Any?>)?.modification ?: modification as Modification<Any?>
            )
        }

        else -> true
    }
}

fun <T> Condition<T>.invoke(modification: Modification<T>): Boolean {
    return when (modification) {
        is Modification.Assign -> this(modification.value)
        is Modification.OnField<*, *> -> {
            val field = this as? Condition.OnField<*, *> ?: return true
            @Suppress("UNCHECKED_CAST")
            field.key != modification.key || (field.condition as Condition<Any?>).invoke<Any?>(modification.modification as Modification<Any?>)
        }

        is Modification.SetPerElement<*> -> {
            @Suppress("UNCHECKED_CAST")
            ((this as? Condition.SetAllElements<Any?>)?.condition
                ?: (this as? Condition.SetAnyElements<Any?>)?.condition)?.let {
                (it as Condition<Any?>).invoke<Any?>(modification.modification as Modification<Any?>)
            } ?: false
        }

        is Modification.ListPerElement<*> -> {
            @Suppress("UNCHECKED_CAST")
            ((this as? Condition.ListAllElements<Any?>)?.condition
                ?: (this as? Condition.ListAnyElements<Any?>)?.condition)?.let {
                (it as Condition<Any?>).invoke<Any?>(modification.modification as Modification<Any?>)
            } ?: false
        }

        is Modification.Chain -> modification.modifications.all { invoke(it) }
        is Modification.IfNotNull<*> -> {
            @Suppress("UNCHECKED_CAST")
            (this as Condition<Any?>).invoke<Any?>(modification.modification as Modification<Any?>)
        }

        else -> false
    }
}