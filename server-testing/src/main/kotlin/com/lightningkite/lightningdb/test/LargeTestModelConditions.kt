package com.lightningkite.lightningdb.test

import com.lightningkite.lightningdb.Condition
import com.lightningkite.lightningdb.setCopy
import java.time.Instant
import com.lightningkite.lightningdb.SerializableProperty

object LargeTestModelConditions {
    class Case(
        val condition: Condition<LargeTestModel>,
        val include: List<LargeTestModel>,
        val exclude: List<LargeTestModel>,
    )

    class ComparableType<T: Comparable<T>>(
        val field: SerializableProperty<LargeTestModel, T>,
        val nullable: SerializableProperty<LargeTestModel, T?>,
        val low: T,
        val middle: T,
        val high: T,
    ) {
        val cases = listOf(
            Case(
                condition = Condition.OnField(field, Condition.Equal(high)),
                include = listOf(field.setCopy(LargeTestModel(), high)),
                exclude = listOf(field.setCopy(LargeTestModel(), low), field.setCopy(LargeTestModel(), middle)),
            ),
            Case(
                condition = Condition.OnField(field, Condition.NotEqual(high)),
                include = listOf(field.setCopy(LargeTestModel(), low), field.setCopy(LargeTestModel(), middle)),
                exclude = listOf(field.setCopy(LargeTestModel(), high)),
            ),
            Case(
                condition = Condition.OnField(field, Condition.GreaterThan(middle)),
                include = listOf(field.setCopy(LargeTestModel(), high)),
                exclude = listOf(field.setCopy(LargeTestModel(), low), field.setCopy(LargeTestModel(), middle)),
            ),
            Case(
                condition = Condition.OnField(field, Condition.LessThan(middle)),
                include = listOf(field.setCopy(LargeTestModel(), low)),
                exclude = listOf(field.setCopy(LargeTestModel(), high), field.setCopy(LargeTestModel(), middle)),
            ),
            Case(
                condition = Condition.OnField(field, Condition.GreaterThanOrEqual(middle)),
                include = listOf(field.setCopy(LargeTestModel(), high), field.setCopy(LargeTestModel(), middle)),
                exclude = listOf(field.setCopy(LargeTestModel(), low)),
            ),
            Case(
                condition = Condition.OnField(field, Condition.LessThanOrEqual(middle)),
                include = listOf(field.setCopy(LargeTestModel(), low), field.setCopy(LargeTestModel(), middle)),
                exclude = listOf(field.setCopy(LargeTestModel(), high)),
            ),
            Case(
                condition = Condition.OnField(nullable, Condition.IfNotNull(Condition.Equal(high))),
                include = listOf(nullable.setCopy(LargeTestModel(), high)),
                exclude = listOf(nullable.setCopy(LargeTestModel(), low), field.setCopy(LargeTestModel(), middle), nullable.setCopy(LargeTestModel(), null)),
            ),
            Case(
                condition = Condition.OnField(nullable, Condition.IfNotNull(Condition.NotEqual(high))),
                include = listOf(nullable.setCopy(LargeTestModel(), low), field.setCopy(LargeTestModel(), middle)),
                exclude = listOf(nullable.setCopy(LargeTestModel(), high), nullable.setCopy(LargeTestModel(), null)),
            ),
            Case(
                condition = Condition.OnField(nullable, Condition.IfNotNull(Condition.GreaterThan(middle))),
                include = listOf(nullable.setCopy(LargeTestModel(), high)),
                exclude = listOf(nullable.setCopy(LargeTestModel(), low), field.setCopy(LargeTestModel(), middle), nullable.setCopy(LargeTestModel(), null)),
            ),
            Case(
                condition = Condition.OnField(nullable, Condition.IfNotNull(Condition.LessThan(middle))),
                include = listOf(nullable.setCopy(LargeTestModel(), low)),
                exclude = listOf(nullable.setCopy(LargeTestModel(), high), field.setCopy(LargeTestModel(), middle), nullable.setCopy(LargeTestModel(), null)),
            ),
            Case(
                condition = Condition.OnField(nullable, Condition.IfNotNull(Condition.GreaterThanOrEqual(middle))),
                include = listOf(nullable.setCopy(LargeTestModel(), high), field.setCopy(LargeTestModel(), middle)),
                exclude = listOf(nullable.setCopy(LargeTestModel(), low), nullable.setCopy(LargeTestModel(), null)),
            ),
            Case(
                condition = Condition.OnField(nullable, Condition.IfNotNull(Condition.LessThanOrEqual(middle))),
                include = listOf(nullable.setCopy(LargeTestModel(), low), field.setCopy(LargeTestModel(), middle)),
                exclude = listOf(nullable.setCopy(LargeTestModel(), high), nullable.setCopy(LargeTestModel(), null)),
            ),
        )
    }

    class OtherType<T>(
        val field: SerializableProperty<LargeTestModel, T>,
        val first: T,
        val second: T,
    )

    val types: List<ComparableType<*>> = listOf(
        ComparableType<Byte>(LargeTestModel_byte, LargeTestModel_byteNullable, 0.toByte(), 1.toByte(), 2.toByte()),
        ComparableType<Short>(LargeTestModel_short, LargeTestModel_shortNullable, 0.toShort(), 1.toShort(), 2.toShort()),
        ComparableType<Int>(LargeTestModel_int, LargeTestModel_intNullable, 0.toInt(), 1.toInt(), 2.toInt()),
        ComparableType<Long>(LargeTestModel_long, LargeTestModel_longNullable, 0.toLong(), 1.toLong(), 2.toLong()),
        ComparableType<Float>(LargeTestModel_float, LargeTestModel_floatNullable, 0f, 1f, 2f),
        ComparableType<Double>(LargeTestModel_double, LargeTestModel_doubleNullable, 0.0, 1.0, 2.0),
        ComparableType<Char>(LargeTestModel_char, LargeTestModel_charNullable, 'a', 'b', 'c'),
        ComparableType<String>(LargeTestModel_string, LargeTestModel_stringNullable, "aa", "ab", "ac"),
        ComparableType<Instant>(
            LargeTestModel_instant,
            LargeTestModel_instantNullable,
            Instant.now().minusSeconds(1),
            Instant.now(),
            Instant.now().plusSeconds(1)
        ),
    )

    val cases = listOf<List<Case>>(
        types.flatMap { it.cases }
    ).flatten()
}

