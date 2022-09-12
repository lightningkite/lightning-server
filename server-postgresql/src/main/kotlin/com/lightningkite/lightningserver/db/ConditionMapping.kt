package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.StructureKind
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.ops.InListOrNotInListBaseOp
import org.jetbrains.exposed.sql.ops.SingleValueInListOp
import kotlin.reflect.KProperty1

internal data class FieldSet2<V>(val serializer: KSerializer<V>, val fields: Map<String, ExpressionWithColumnType<Any?>>) {
    constructor(serializer: KSerializer<V>, table: SerialDescriptorTable) : this(
        serializer = serializer,
        fields = table.col.mapValues { it.value as ExpressionWithColumnType<Any?> }
    )
    val single: ExpressionWithColumnType<Any?> get() = fields[""] ?: throw IllegalStateException("No column found for ${serializer.descriptor.serialName}")
    fun single(value: V): Pair<ExpressionWithColumnType<Any?>, Expression<Any?>> = single to LiteralOp(single.columnType, formatSingle(value))
    fun sub(property: KProperty1<V, *>) = FieldSet2<Any?>(
        serializer = serializer.fieldSerializer(property) as KSerializer<Any?>,
        fields = fields.filter { it.key == property.name || it.key.startsWith(property.name + "__") }
            .mapKeys { it.key.substringAfter(property.name).removePrefix("__") }
    )
    val exists: Expression<Boolean>
        get() = fields["exists"]?.let {
            it as Expression<Boolean>
        } ?: IsNotNullOp(fields.values.first())
    val notExists: Expression<Boolean>
        get() = fields["exists"]?.let {
            NotOp(it as Expression<Boolean>)
        } ?: IsNullOp(fields.values.first())

    fun format(value: V): Map<ExpressionWithColumnType<Any?>, Any?> {
        return PostgresCollection.format.encode(serializer, value).mapKeys { fields[it.key]!! }
    }
    fun formatSingle(value: V): Any? {
        return PostgresCollection.format.encode(serializer, value)[""]
    }
}

fun <T> ISqlExpressionBuilder.condition(
    condition: Condition<T>,
    serializer: KSerializer<T>,
    table: SerialDescriptorTable
): Expression<Boolean> = condition(condition, FieldSet2(serializer, table))
private fun <T> ISqlExpressionBuilder.condition(
    condition: Condition<T>,
    fieldSet: FieldSet2<T>
): Expression<Boolean> {
    fun op(value: T, make: (Expression<*>, Expression<*>) -> Op<Boolean>): Op<Boolean> {
        val (col, v) = fieldSet.single(value)
        return make(col, v)
    }
    return when (condition) {
        is Condition.Always -> Op.TRUE
        is Condition.Never -> Op.FALSE
        is Condition.And -> AndOp(condition.conditions.map { condition(it, fieldSet) })
        is Condition.Or -> OrOp(condition.conditions.map { condition(it, fieldSet) })
        is Condition.Equal -> {
            if (condition.value == null) {
                fieldSet.notExists
            } else {
                AndOp(fieldSet.format(condition.value).entries.map {EqOp(it.key, LiteralOp(it.key.columnType, it.value)) })
            }
        }
        is Condition.NotEqual -> {
            if (condition.value == null) {
                fieldSet.exists
            } else {
                OrOp(fieldSet.format(condition.value).entries.map {NeqOp(it.key, LiteralOp(it.key.columnType, it.value)) })
            }
        }
        is Condition.SetAllElements<*> -> {
            AllIsTrueOp(MapOp(fieldSet as FieldSet2<List<Any?>>) {
                condition(condition.condition as Condition<Any?>, it)
            })
        }
        is Condition.ListAllElements<*> -> {
            AllIsTrueOp(MapOp(fieldSet as FieldSet2<List<Any?>>) {
                condition(condition.condition as Condition<Any?>, it)
            })
        }
        is Condition.SetAnyElements<*> -> {
            AnyIsTrueOp(MapOp(fieldSet as FieldSet2<List<Any?>>) {
                condition(condition.condition as Condition<Any?>, it)
            })
        }
        is Condition.ListAnyElements<*> -> {
            AnyIsTrueOp(MapOp(fieldSet as FieldSet2<List<Any?>>) {
                condition(condition.condition as Condition<Any?>, it)
            })
        }
        is Condition.Exists<*> -> {
            val keyValue = PostgresCollection.format.encode(fieldSet.serializer.mapKeyElement()!! as KSerializer<Any?>, condition.key)[""]
            ContainsOp(fieldSet.single, LiteralOp(fieldSet.single.columnType, listOf(keyValue)))
        }
        is Condition.OnKey<*> -> {
            val keyValue = PostgresCollection.format.encode(fieldSet.serializer.mapKeyElement()!! as KSerializer<Any?>, condition.key)[""]
            condition(
                condition = condition.condition as Condition<Any?>,
                fieldSet = FieldSet2<Any?>(
                    serializer = fieldSet.serializer.mapValueElement()!! as KSerializer<Any?>,
                    fields = fieldSet.fields.entries.asSequence()
                        .filter { it.key.startsWith("value") }
                        .associate {
                            it.key.removePrefix("value").removePrefix("__") to object: ExpressionWithColumnType<Any?>() {
                                override val columnType: IColumnType
                                    get() = (it.value.columnType as ArrayColumnType).type

                                override fun toQueryBuilder(queryBuilder: QueryBuilder) {
                                    GetOp(
                                        it.value as Expression<List<Any?>>,
                                        CustomFunction<Int>("array_position", IntegerColumnType(), fieldSet.single, LiteralOp((fieldSet.fields[""]!!.columnType as ArrayColumnType).type, keyValue))
                                    ).toQueryBuilder(queryBuilder)
                                }
                            }
                        }
                ).also { println(it) }
            )
        }
//        is Condition.FullTextSearch -> throw IllegalArgumentException()

        is Condition.GreaterThan -> op(condition.value, ::GreaterOp)
        is Condition.LessThan -> op(condition.value, ::LessOp)
        is Condition.GreaterThanOrEqual -> op(condition.value, ::GreaterEqOp)
        is Condition.LessThanOrEqual -> op(condition.value, ::LessEqOp)
        is Condition.IfNotNull<*> -> {
            AndOp(listOf(
                fieldSet.exists,
                condition<Any?>(
                    condition.condition as Condition<Any?>,
                    fieldSet as FieldSet2<Any?>
                )
            ))
        }

        is Condition.Inside -> {
            if(fieldSet.fields.size == 1)
                SingleValueInListOp(fieldSet.single, condition.values.map { fieldSet.formatSingle(it) })
            else
                OrOp(condition.values.map { value ->
                    AndOp(fieldSet.format(value).entries.map {EqOp(it.key, LiteralOp(it.key.columnType, it.value)) })
                })
        }
        is Condition.NotInside -> {
            if(fieldSet.fields.size == 1)
                NotOp(SingleValueInListOp(fieldSet.single, condition.values.map { fieldSet.formatSingle(it) }))
            else
                AndOp(condition.values.map { value ->
                    OrOp(fieldSet.format(value).entries.map {NeqOp(it.key, LiteralOp(it.key.columnType, it.value)) })
                })
        }

        is Condition.IntBitsAnyClear -> {
            val col = fieldSet.single(condition.mask as T)
            return LessOp(
                AndBitOp(col.first as Expression<Int>, col.second as Expression<Int>, IntegerColumnType()),
                col.first
            )
        }

        is Condition.IntBitsAnySet -> {
            val col = fieldSet.single(condition.mask as T)
            return GreaterOp(
                AndBitOp(col.first as Expression<Int>, col.second as Expression<Int>, IntegerColumnType()),
                LiteralOp(IntegerColumnType(), 0)
            )
        }

        is Condition.IntBitsClear -> {
            val col = fieldSet.single(condition.mask as T)
            return EqOp(
                AndBitOp(col.first as Expression<Int>, col.second as Expression<Int>, IntegerColumnType()),
                LiteralOp(IntegerColumnType(), 0)
            )
        }

        is Condition.IntBitsSet -> {
            val col = fieldSet.single(condition.mask as T)
            return EqOp(
                AndBitOp(col.first as Expression<Int>, col.second as Expression<Int>, IntegerColumnType()),
                col.first
            )
        }

        is Condition.Not -> NotOp(condition(condition.condition, fieldSet))
        is Condition.StringContains -> {
            val col = fieldSet.single
            if (condition.ignoreCase)
                InsensitiveLikeEscapeOp(col, LiteralOp(TextColumnType(), condition.value), true, null)
            else
                LikeEscapeOp(col, LiteralOp(TextColumnType(), condition.value), true, null)
        }

        is Condition.RegexMatches -> {
            val col = fieldSet.single
            RegexpOp(col as Column<String>, LiteralOp(TextColumnType(), condition.pattern), true)
        }

        is Condition.SetSizesEquals<*> -> {
            val col = fieldSet.single
            EqOp(
                (col.columnType as ArrayColumnType).arrayLengthOp(col as Column<List<Any?>>),
                LiteralOp(IntegerColumnType(), condition.count)
            )
        }

        is Condition.ListSizesEquals<*> -> {
            val col = fieldSet.single
            EqOp(
                (col.columnType as ArrayColumnType).arrayLengthOp(col as Column<List<Any?>>),
                LiteralOp(IntegerColumnType(), condition.count)
            )
        }

        is Condition.OnField<*, *> -> condition<Any?>(
            condition.condition as Condition<Any?>,
            fieldSet.sub(condition.key as KProperty1<T, Any?>)
        )

        else -> throw IllegalArgumentException()
    }
}