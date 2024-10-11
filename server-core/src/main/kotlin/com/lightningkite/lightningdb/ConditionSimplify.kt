package com.lightningkite.lightningdb

import com.lightningkite.serialization.*
import com.lightningkite.serialization.SerializableProperty

// Sink properties, reduce within properties, reduce all

fun <T> Condition<T>.simplify(): Condition<T> {
    return when(this) {
        is Condition.And -> {
//            println("AND simplifying $this")
            conditions.asSequence().flatMap { it.andByField() }
                .groupBy { it.first }
                .mapNotNull {
//                    println("AND key ${it.key.map { it.name }}: merging parts ${it.value.map { it.second }}")
                    @Suppress("UNCHECKED_CAST")
                    val keyCond = it.value.map { it.second as Condition<Any?> }.reduce(::reduceAnd).finalSimplify()
//                    println("AND reduced to $keyCond")
                    when (keyCond) {
                        Condition.Always -> return@mapNotNull null
                        Condition.Never -> return Condition.Never
                        else -> {
                            @Suppress("UNCHECKED_CAST")
                            make(it.key, keyCond) as Condition<T>
                        }
                    }
                }
                .let {
//                    println("AND total simplification list: $it")
                    if (it.isEmpty()) Condition.Always<T>()
                    else if (it.size == 1) it.first()
                    else Condition.And<T>(it)
                }
//                .also { println("AND final is $it") }
        }
        is Condition.Or -> {
//            println("OR simplifying $this")
            conditions.asSequence().flatMap { it.orByField() }
                .groupBy { it.first }
                .mapNotNull {
//                    println("OR key ${it.key.map { it.name }}: merging parts ${it.value.map { it.second }}")
                    val keyCond = it.value.map {
                        @Suppress("UNCHECKED_CAST")
                        it.second as Condition<Any?>
                    }.reduce(::reduceOr).finalSimplify()
//                    println("OR reduced to $keyCond")
                    when (keyCond) {
                        Condition.Always -> return Condition.Always
                        Condition.Never -> return@mapNotNull null
                        else -> {
                            @Suppress("UNCHECKED_CAST")
                            make(it.key, keyCond) as Condition<T>
                        }
                    }
                }
                .let {
//                    println("OR total simplification list: $it")
                    if (it.isEmpty()) Condition.Never<T>()
                    else if (it.size == 1) it.first()
                    else Condition.Or<T>(it)
                }
//                .also { println("OR final is $it") }
        }
        else -> finalSimplify()
    }
}

private fun <T> Condition<T>.finalSimplify(): Condition<T> = when(this) {
    is Condition.And -> if(conditions.any { it == Condition.Never }) Condition.Never else this
    is Condition.Or -> if(conditions.any { it == Condition.Always }) Condition.Always else this
    is Condition.Inside -> if(values.isEmpty()) Condition.Never() else this
    is Condition.NotInside -> if(values.isEmpty()) Condition.Always() else this
    else -> this
}

private fun Condition<*>.andByField(): Sequence<Pair<List<SerializableProperty<*, *>>, Condition<*>>> {
    return when (this) {
        is Condition.And -> conditions.asSequence().flatMap { it.andByField() }
        is Condition.OnField<*, *> -> condition.andByField().map {
            (listOf(key) + it.first) to it.second
        }
        else -> {
            val s = this.simplify()
            if(s is Condition.OnField<*, *>) s.condition.andByField().map {
                (listOf(s.key) + it.first) to it.second
            } else sequenceOf(listOf<SerializableProperty<*, *>>() to s)
        }
    }
}

private fun Condition<*>.orByField(): Sequence<Pair<List<SerializableProperty<*, *>>, Condition<*>>> {
    return when (this) {
        is Condition.Or -> conditions.asSequence().flatMap { it.orByField() }
        is Condition.OnField<*, *> -> condition.orByField().map {
            (listOf(key) + it.first) to it.second
        }
        else -> {
            val s = this.simplify()
            if(s is Condition.OnField<*, *>) s.condition.orByField().map {
                (listOf(s.key) + it.first) to it.second
            } else sequenceOf(listOf<SerializableProperty<*, *>>() to s)
        }
    }
}

private fun make(prop: List<SerializableProperty<*, *>>, cond: Condition<*>): Condition<*> {
    @Suppress("UNCHECKED_CAST")
    (return if (prop.isEmpty()) cond
    else Condition.OnField(
        prop.first() as SerializableProperty<Any?, Any?>,
        make(prop.subList(1, prop.size), cond) as Condition<Any?>
    ))
}

@Suppress("UNCHECKED_CAST")
private fun <T> reduceAnd(a: Condition<T>, b: Condition<T>): Condition<T> {
    return when (a) {
        is Condition.Always -> b
        is Condition.Never -> a
        is Condition.And -> when (b) {
            is Condition.And -> Condition.And(a.conditions + b.conditions)
            else -> Condition.And(a.conditions + b)
        }

        is Condition.Equal -> when (b) {
            is Condition.Equal -> if (a.value == b.value) a else Condition.Never()
            is Condition.GreaterThan -> if (a.value.let { it as Comparable<Any?> } > b.value.let { it as Comparable<Any?> }) a else Condition.Never()
            is Condition.LessThan -> if (a.value.let { it as Comparable<Any?> } < b.value.let { it as Comparable<Any?> }) a else Condition.Never()
            is Condition.GreaterThanOrEqual -> if (a.value.let { it as Comparable<Any?> } >= b.value.let { it as Comparable<Any?> }) a else Condition.Never()
            is Condition.LessThanOrEqual -> if (a.value.let { it as Comparable<Any?> } <= b.value.let { it as Comparable<Any?> }) a else Condition.Never()
            is Condition.NotEqual -> if (a.value != b.value) a else Condition.Never()
            is Condition.Inside -> if (a.value in b.values) a else Condition.Never()
            is Condition.NotInside -> if (a.value !in b.values) a else Condition.Never()
            is Condition.And -> Condition.And(b.conditions + a)
            else -> Condition.And(listOf(a, b))
        }

        is Condition.GreaterThan -> when (b) {
            is Condition.GreaterThan -> if (a.value.let { it as Comparable<Any?> } > b.value.let { it as Comparable<Any?> }) a else b
            is Condition.GreaterThanOrEqual -> if (a.value.let { it as Comparable<Any?> } >= b.value.let { it as Comparable<Any?> }) a else b
            is Condition.LessThan -> if (a.value.let { it as Comparable<Any?> } >= b.value.let { it as Comparable<Any?> }) Condition.Never() else Condition.And(listOf(a, b))
            is Condition.LessThanOrEqual -> if (a.value.let { it as Comparable<Any?> } >= b.value.let { it as Comparable<Any?> }) Condition.Never() else Condition.And(listOf(a, b))
            is Condition.Always,
            is Condition.Never,
            is Condition.And,
            is Condition.Equal -> reduceAnd(b, a)

            else -> Condition.And(listOf(a, b))
        }

        is Condition.LessThan -> when (b) {
            is Condition.LessThan -> if (a.value.let { it as Comparable<Any?> } < b.value.let { it as Comparable<Any?> }) a else b
            is Condition.LessThanOrEqual -> if (a.value.let { it as Comparable<Any?> } <= b.value.let { it as Comparable<Any?> }) a else b
            is Condition.GreaterThan,
            is Condition.GreaterThanOrEqual,
            is Condition.Always,
            is Condition.Never,
            is Condition.And,
            is Condition.Equal -> reduceAnd(b, a)

            else -> Condition.And(listOf(a, b))
        }

        is Condition.GreaterThanOrEqual -> when (b) {
            is Condition.GreaterThanOrEqual -> if (a.value.let { it as Comparable<Any?> } >= b.value.let { it as Comparable<Any?> }) a else b
            is Condition.LessThan -> if (a.value.let { it as Comparable<Any?> } >= b.value.let { it as Comparable<Any?> }) Condition.Never() else Condition.And(listOf(a, b))
            is Condition.LessThanOrEqual -> if (a.value.let { it as Comparable<Any?> } > b.value.let { it as Comparable<Any?> }) Condition.Never() else Condition.And(listOf(a, b))
            is Condition.GreaterThan,
            is Condition.Always,
            is Condition.Never,
            is Condition.And,
            is Condition.Equal -> reduceAnd(b, a)

            else -> Condition.And(listOf(a, b))
        }

        is Condition.LessThanOrEqual -> when (b) {
            is Condition.LessThanOrEqual -> if (a.value.let { it as Comparable<Any?> } <= b.value.let { it as Comparable<Any?> }) a else b
            is Condition.LessThan,
            is Condition.Always,
            is Condition.Never,
            is Condition.And,
            is Condition.Equal -> reduceAnd(b, a)

            else -> Condition.And(listOf(a, b))
        }

        is Condition.Inside -> when (b) {
            is Condition.Inside -> Condition.Inside(a.values.toSet().intersect(b.values.toSet()).toList())
            is Condition.NotInside -> Condition.Inside(a.values.toSet().minus(b.values.toSet()).toList())
            is Condition.GreaterThan,
            is Condition.LessThan,
            is Condition.Always,
            is Condition.Never,
            is Condition.And,
            is Condition.Equal -> reduceAnd(b, a)

            else -> Condition.And(listOf(a, b))
        }

        is Condition.NotInside -> when (b) {
            is Condition.NotInside -> Condition.NotInside(a.values.toSet().union(b.values.toSet()).toList())
            is Condition.Inside,
            is Condition.GreaterThan,
            is Condition.LessThan,
            is Condition.Always,
            is Condition.Never,
            is Condition.And,
            is Condition.Equal -> reduceAnd(b, a)

            else -> Condition.And(listOf(a, b))
        }

        else -> Condition.And(listOf(a, b))
    }
}
@Suppress("UNCHECKED_CAST")
private fun <T> reduceOr(a: Condition<T>, b: Condition<T>): Condition<T> {
    return when (a) {
        is Condition.Always -> a
        is Condition.Never -> b
        is Condition.Or -> when (b) {
            is Condition.Or -> Condition.Or(a.conditions + b.conditions)
            else -> Condition.Or(a.conditions + b)
        }

        is Condition.Equal -> when (b) {
            is Condition.Equal -> if (a.value == b.value) a else Condition.Inside(listOf(a.value, b.value))
            is Condition.GreaterThan -> if (a.value.let { it as Comparable<Any?> } > b.value.let { it as Comparable<Any?> }) b else Condition.Or(listOf(a, b))
            is Condition.LessThan -> if (a.value.let { it as Comparable<Any?> } < b.value.let { it as Comparable<Any?> }) b else Condition.Or(listOf(a, b))
            is Condition.GreaterThanOrEqual -> if (a.value.let { it as Comparable<Any?> } >= b.value.let { it as Comparable<Any?> }) b else Condition.Or(listOf(a, b))
            is Condition.LessThanOrEqual -> if (a.value.let { it as Comparable<Any?> } <= b.value.let { it as Comparable<Any?> }) b else Condition.Or(listOf(a, b))
            is Condition.NotEqual -> if (a.value != b.value) b else Condition.Always()
            is Condition.Inside -> if (a.value in b.values) b else Condition.Inside(b.values + a.value)
            is Condition.NotInside -> if (a.value !in b.values) b else Condition.NotInside(b.values - a.value)
            is Condition.Or -> Condition.Or(b.conditions + a)
            else -> Condition.Or(listOf(a, b))
        }

        is Condition.GreaterThan -> when (b) {
            is Condition.GreaterThan -> if (a.value.let { it as Comparable<Any?> } > b.value.let { it as Comparable<Any?> }) b else a
            is Condition.GreaterThanOrEqual -> if (a.value.let { it as Comparable<Any?> } >= b.value.let { it as Comparable<Any?> }) b else a
            is Condition.Always,
            is Condition.Never,
            is Condition.Or,
            is Condition.Equal -> reduceOr(b, a)

            else -> Condition.Or(listOf(a, b))
        }

        is Condition.LessThan -> when (b) {
            is Condition.LessThan -> if (a.value.let { it as Comparable<Any?> } < b.value.let { it as Comparable<Any?> }) b else a
            is Condition.LessThanOrEqual -> if (a.value.let { it as Comparable<Any?> } <= b.value.let { it as Comparable<Any?> }) b else a
            is Condition.Always,
            is Condition.Never,
            is Condition.Or,
            is Condition.Equal -> reduceOr(b, a)

            else -> Condition.Or(listOf(a, b))
        }

        is Condition.GreaterThanOrEqual -> when (b) {
            is Condition.GreaterThanOrEqual -> if (a.value.let { it as Comparable<Any?> } >= b.value.let { it as Comparable<Any?> }) b else a
            is Condition.GreaterThan,
            is Condition.Always,
            is Condition.Never,
            is Condition.Or,
            is Condition.Equal -> reduceOr(b, a)

            else -> Condition.Or(listOf(a, b))
        }

        is Condition.LessThanOrEqual -> when (b) {
            is Condition.LessThanOrEqual -> if (a.value.let { it as Comparable<Any?> } <= b.value.let { it as Comparable<Any?> }) b else a
            is Condition.LessThan,
            is Condition.Always,
            is Condition.Never,
            is Condition.Or,
            is Condition.Equal -> reduceOr(b, a)

            else -> Condition.Or(listOf(a, b))
        }

        is Condition.Inside -> when (b) {
            is Condition.Inside -> Condition.Inside(a.values.toSet().union(b.values.toSet()).toList())
            is Condition.NotInside -> Condition.NotInside(b.values.toSet().minus(a.values.toSet()).toList())
            is Condition.GreaterThan,
            is Condition.LessThan,
            is Condition.Always,
            is Condition.Never,
            is Condition.Or,
            is Condition.Equal -> reduceOr(b, a)

            else -> Condition.Or(listOf(a, b))
        }

        is Condition.NotInside -> when (b) {
            is Condition.NotInside -> Condition.NotInside(a.values.toSet().intersect(b.values.toSet()).toList())
            is Condition.Inside,
            is Condition.GreaterThan,
            is Condition.LessThan,
            is Condition.Always,
            is Condition.Never,
            is Condition.Or,
            is Condition.Equal -> reduceOr(b, a)

            else -> Condition.Or(listOf(a, b))
        }

        else -> Condition.Or(listOf(a, b))
    }
}
