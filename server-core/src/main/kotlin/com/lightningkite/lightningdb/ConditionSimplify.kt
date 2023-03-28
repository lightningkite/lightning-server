package com.lightningkite.lightningdb

import kotlin.reflect.KProperty1


private fun <T> Condition<T>.unpackAnd(): Sequence<Pair<List<KProperty1<*, *>>, Condition<*>>> {
    return when(this) {
        is Condition.And -> conditions.asSequence().flatMap { it.unpackAnd() }
        is Condition.OnField<*, *> -> condition.unpackAnd().map {
            (listOf(key) + it.first) to it.second
        }
        else -> sequenceOf(listOf<KProperty1<*, *>>() to this)
    }
}
private fun <T> Condition<T>.unpackOr(): Sequence<Pair<List<KProperty1<*, *>>, Condition<*>>> {
    return when(this) {
        is Condition.Or -> conditions.asSequence().flatMap { it.unpackAnd() }
        is Condition.OnField<*, *> -> condition.unpackOr().map {
            (listOf(key) + it.first) to it.second
        }
        else -> sequenceOf(listOf<KProperty1<*, *>>() to this)
    }
}

private fun make(prop: List<KProperty1<*, *>>, cond: Condition<*>): Condition<*> {
    @Suppress("UNCHECKED_CAST")
    (return if(prop.isEmpty()) cond
    else Condition.OnField(prop.first() as KProperty1<Any?, Any?>, make(prop.subList(1, prop.size), cond) as Condition<Any?>))
}

private fun <T> reduce(a: Condition<T>, b: Condition<T>): Condition<T> {
    return when(a) {
        is Condition.Always -> b
        is Condition.Never -> a
        is Condition.And -> when(b) {
            is Condition.And -> Condition.And(a.conditions + b.conditions)
            else -> Condition.And(a.conditions + b)
        }
        is Condition.Equal -> when(b) {
            is Condition.Equal -> if(a.value == b.value) a else Condition.Never()
            is Condition.GreaterThan -> if(a.value.let { it as Comparable<Any?> } > b.value.let { it as Comparable<Any?> }) a else Condition.Never()
            is Condition.LessThan -> if(a.value.let { it as Comparable<Any?> } < b.value.let { it as Comparable<Any?> }) a else Condition.Never()
            is Condition.GreaterThanOrEqual -> if(a.value.let { it as Comparable<Any?> } >= b.value.let { it as Comparable<Any?> }) a else Condition.Never()
            is Condition.LessThanOrEqual -> if(a.value.let { it as Comparable<Any?> } <= b.value.let { it as Comparable<Any?> }) a else Condition.Never()
            is Condition.NotEqual -> if(a.value != b.value) a else Condition.Never()
            is Condition.Inside -> if(a.value in b.values) a else Condition.Never()
            is Condition.NotInside -> if(a.value !in b.values) a else Condition.Never()
            is Condition.And -> Condition.And(b.conditions + a)
            else -> Condition.And(listOf(a, b))
        }
        is Condition.GreaterThan -> when(b) {
            is Condition.GreaterThan -> if(a.value.let { it as Comparable<Any?> } > b.value.let { it as Comparable<Any?> }) a else b
            is Condition.GreaterThanOrEqual -> if(a.value.let { it as Comparable<Any?> } >= b.value.let { it as Comparable<Any?> }) a else b
            is Condition.Always,
            is Condition.Never,
            is Condition.And ,
            is Condition.Equal -> reduce(b, a)
            else -> Condition.And(listOf(a, b))
        }
        is Condition.LessThan -> when(b) {
            is Condition.LessThan -> if(a.value.let { it as Comparable<Any?> } < b.value.let { it as Comparable<Any?> }) a else b
            is Condition.LessThanOrEqual -> if(a.value.let { it as Comparable<Any?> } <= b.value.let { it as Comparable<Any?> }) a else b
            is Condition.Always,
            is Condition.Never,
            is Condition.And ,
            is Condition.Equal -> reduce(b, a)
            else -> Condition.And(listOf(a, b))
        }
        is Condition.GreaterThanOrEqual -> when(b) {
            is Condition.GreaterThanOrEqual -> if(a.value.let { it as Comparable<Any?> } >= b.value.let { it as Comparable<Any?> }) a else b
            is Condition.GreaterThan,
            is Condition.Always,
            is Condition.Never,
            is Condition.And ,
            is Condition.Equal -> reduce(b, a)
            else -> Condition.And(listOf(a, b))
        }
        is Condition.LessThanOrEqual -> when(b) {
            is Condition.LessThanOrEqual -> if(a.value.let { it as Comparable<Any?> } <= b.value.let { it as Comparable<Any?> }) a else b
            is Condition.LessThan,
            is Condition.Always,
            is Condition.Never,
            is Condition.And,
            is Condition.Equal -> reduce(b, a)
            else -> Condition.And(listOf(a, b))
        }
        is Condition.Inside -> when(b) {
            is Condition.Inside -> Condition.Inside(a.values.toSet().intersect(b.values.toSet()).toList())
            is Condition.NotInside -> Condition.Inside(a.values.toSet().minus(b.values.toSet()).toList())
            is Condition.GreaterThan,
            is Condition.LessThan,
            is Condition.Always,
            is Condition.Never,
            is Condition.And,
            is Condition.Equal -> reduce(b, a)
            else -> Condition.And(listOf(a, b))
        }
        is Condition.NotInside -> when(b) {
            is Condition.NotInside -> Condition.NotInside(a.values.toSet().union(b.values.toSet()).toList())
            is Condition.Inside,
            is Condition.GreaterThan,
            is Condition.LessThan,
            is Condition.Always,
            is Condition.Never,
            is Condition.And,
            is Condition.Equal -> reduce(b, a)
            else -> Condition.And(listOf(a, b))
        }
        else -> Condition.And(listOf(a, b))
    }
}

fun <T> Condition.And<T>.unpackAndMerge(): List<Condition<T>> {
    return unpackAnd().groupBy { it.first }.map {
        (if(it.value.size == 1)
            make(it.key, it.value[0].second) as Condition<T>
        else make(it.key, it.value.asSequence().map { it.second }.reduce { a, b ->
            reduce(a as Condition<Any>, b as Condition<Any>)
        }) as Condition<T>)
    }
}
private fun <T> Condition<T>.simplifyWithoutMerge(): Condition<T> {
    return when(this) {
        is Condition.And -> {
            conditions.filter { it !is Condition.Always }.let {
                when(it.size) {
                    0 -> Condition.Never()
                    1 -> it.first().simplifyWithoutMerge()
                    else -> Condition.And(it.map { it.simplifyWithoutMerge() })
                }
            }
        }
        is Condition.Not -> (this.condition as? Condition.Not)?.condition?.simplify() ?: Condition.Not(simplify())
        is Condition.Or -> this.conditions.distinct().map { it.simplify() }.filter { it !is Condition.Never }.let {
            when(it.size) {
                0 -> Condition.Always()
                1 -> it.first().simplify()
                else -> Condition.Or(it)
            }
        }
        is Condition.Inside -> this.values.distinct().let {
            when(it.size) {
                0 -> Condition.Never()
                1 -> Condition.Equal(it.first())
                else -> Condition.Inside(it)
            }
        }
        is Condition.NotInside -> this.values.distinct().let {
            when(it.size) {
                0 -> Condition.Always()
                1 -> Condition.NotEqual(it.first())
                else -> Condition.NotInside(it)
            }
        }
        is Condition.OnField<*, *> -> Condition.OnField(key as KProperty1<T, Any?>, condition.simplify() as Condition<Any?>)
        else -> this
    }
}

/**
 * Will reduce a condition into it's smallest relevant parts.
 * Ex: Or[Never, Always] -> Always
 * Ex: And[Never, Always] -> Never
 * Ex: And[Equal, Always] -> Equal
 */
fun <T> Condition<T>.simplify(): Condition<T> {
    return when(this) {
        is Condition.And -> {
            var foundAlways = false
            this.unpackAndMerge().filter {
                if(it is Condition.Always) {
                    foundAlways = true
                    false
                } else true
            }.let {
                if(it.any { it is Condition.Never }) Condition.Never()
                else when(it.size) {
                    0 -> if(foundAlways) Condition.Always() else Condition.Never()
                    1 -> it.first().simplifyWithoutMerge()
                    else -> Condition.And(it.map { it.simplifyWithoutMerge() })
                }
            }
        }
        is Condition.Not -> (this.condition as? Condition.Not)?.condition?.simplify() ?: Condition.Not(simplify())
        is Condition.Or -> {
            this.conditions.distinct().map { it.simplify() }.filter {
                it !is Condition.Never
            }.let {
                if(it.any { it is Condition.Always }) Condition.Always()
                else when(it.size) {
                    0 -> Condition.Never()
                    1 -> it.first().simplify()
                    else -> Condition.Or(it)
                }
            }
        }
        is Condition.Inside -> this.values.distinct().let {
            when(it.size) {
                0 -> Condition.Never()
                1 -> Condition.Equal(it.first())
                else -> Condition.Inside(it)
            }
        }
        is Condition.NotInside -> this.values.distinct().let {
            when(it.size) {
                0 -> Condition.Always()
                1 -> Condition.NotEqual(it.first())
                else -> Condition.NotInside(it)
            }
        }
        is Condition.OnField<*, *> -> Condition.OnField(key as KProperty1<T, Any?>, condition.simplify() as Condition<Any?>)
        else -> this
    }
}