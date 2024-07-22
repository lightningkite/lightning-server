package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.StructureKind
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.ops.InListOrNotInListBaseOp
import org.jetbrains.exposed.sql.ops.SingleValueInListOp
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import com.lightningkite.lightningdb.SerializableProperty

internal data class FieldSet2<V>(val serializer: KSerializer<V>, val fields: Map<String, ExpressionWithColumnType<Any?>>) {
    constructor(serializer: KSerializer<V>, table: SerialDescriptorTable) : this(
        serializer = serializer,
        fields = table.col.mapValues { it.value as ExpressionWithColumnType<Any?> }
    )
    val single: ExpressionWithColumnType<Any?> get() = fields[""] ?: throw IllegalStateException("No column found for ${serializer.descriptor.serialName}")
    fun single(value: V): Pair<ExpressionWithColumnType<Any?>, Expression<Any?>> = single to LiteralOp(single.columnType, formatSingle(value))
    fun sub(property: SerializableProperty<V, *>) = FieldSet2<Any?>(
        serializer = property.serializer as KSerializer<Any?>,
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

    fun format(value: V): Map<ExpressionWithColumnType<Any?>, Expression<Any?>> {
        return PostgresCollection.format.encode(serializer, value).mapKeys { fields[it.key]!! }.mapValues { LiteralOp(it.key.columnType, it.value) }
    }
    fun formatSingle(value: V): Any? {
        return PostgresCollection.format.encode(serializer, value)[""]
    }
    fun formatSingleExpression(value: V): Expression<Any?> {
        return LiteralOp(fields[""]!!.columnType, PostgresCollection.format.encode(serializer, value)[""])
    }
}

fun <T> ISqlExpressionBuilder.condition(
    condition: Condition<T>,
    serializer: KSerializer<T>,
    table: SerialDescriptorTable
): Expression<Boolean> = condition(condition, FieldSet2(serializer, table))
@Suppress("UNCHECKED_CAST")
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
                AndOp(fieldSet.format(condition.value).entries.map {EqOp(it.key, it.value) })
            }
        }
        is Condition.NotEqual -> {
            if (condition.value == null) {
                fieldSet.exists
            } else {
                OrOp(fieldSet.format(condition.value).entries.map {NeqOp(it.key, it.value) })
            }
        }
        is Condition.SetAllElements<*> -> {
            AllIsTrueOp(MapOp(fieldSet as FieldSet2<List<Any?>>, mapper = {
                condition(condition.condition as Condition<Any?>, it)
            }))
        }
        is Condition.ListAllElements<*> -> {
            AllIsTrueOp(MapOp(fieldSet as FieldSet2<List<Any?>>, mapper = {
                condition(condition.condition as Condition<Any?>, it)
            }))
        }
        is Condition.SetAnyElements<*> -> {
            AnyIsTrueOp(MapOp(fieldSet as FieldSet2<List<Any?>>, mapper = {
                condition(condition.condition as Condition<Any?>, it)
            }))
        }
        is Condition.ListAnyElements<*> -> {
            AnyIsTrueOp(MapOp(fieldSet as FieldSet2<List<Any?>>, mapper = {
                condition(condition.condition as Condition<Any?>, it)
            }))
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
                )
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
                    AndOp(fieldSet.format(value).entries.map {EqOp(it.key, it.value) })
                })
        }
        is Condition.NotInside -> {
            if(fieldSet.fields.size == 1)
                NotOp(SingleValueInListOp(fieldSet.single, condition.values.map { fieldSet.formatSingle(it) }))
            else
                AndOp(condition.values.map { value ->
                    OrOp(fieldSet.format(value).entries.map {NeqOp(it.key, it.value) })
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
        is Condition.GeoDistance -> TODO()
        is Condition.StringContains -> {
            val col = fieldSet.single
            if (condition.ignoreCase)
                InsensitiveLikeEscapeOp(col, LiteralOp(TextColumnType(), condition.value), true, null)
            else
                LikeEscapeOp(col, LiteralOp(TextColumnType(), condition.value), true, null)
        }

        is Condition.RawStringContains -> {
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
                ArrayLengthOp(col as Column<List<Any?>>),
                LiteralOp(IntegerColumnType(), condition.count)
            )
        }

        is Condition.ListSizesEquals<*> -> {
            val col = fieldSet.single
            EqOp(
                ArrayLengthOp(col as Column<List<Any?>>),
                LiteralOp(IntegerColumnType(), condition.count)
            )
        }

        is Condition.OnField<*, *> -> condition<Any?>(
            condition.condition as Condition<Any?>,
            fieldSet.sub(condition.key as SerializableProperty<T, Any?>)
        )

        else -> throw IllegalArgumentException()
    }
}

internal interface FieldModifier {
    fun modify(key: String, modify: (Expression<Any?>)->Expression<Any?>)
}
internal fun FieldModifier.sub(subKey: String): FieldModifier {
    return object: FieldModifier {
        override fun modify(key: String, modify: (Expression<Any?>) -> Expression<Any?>) {
            if(key.isEmpty()) this@sub.modify(subKey, modify)
            else this@sub.modify(subKey + "__" + key, modify)
        }
    }
}
internal inline fun <T> FieldModifier.modifySingle(set: FieldSet2<T>, crossinline action: (type: IColumnType, old: Expression<Any?>) -> Expression<Any?>) {
    modify("") { action(set.single.columnType, it) }
}
internal inline fun <T> FieldModifier.modifyEach(set: FieldSet2<T>, value: T, crossinline action: (type: IColumnType, value: Expression<Any?>, old: Expression<Any?>) -> Expression<Any?>) {
    PostgresCollection.format.encode(set.serializer, value).forEach {
        modify(it.key) { old ->
            val t = set.fields[it.key]!!.columnType
            action(t, LiteralOp(t, it.value), old)
        }
    }
}

@Suppress("UNCHECKED_CAST")
fun <T> UpdateBuilder<*>.modification(
    modification: Modification<T>,
    serializer: KSerializer<T>,
    table: SerialDescriptorTable
) {
    val map = HashMap<String, Expression<Any?>>()
    object: FieldModifier {
        fun default(key: String) = table.col[key]!! as Expression<Any?>
        override fun modify(key: String, modify: (Expression<Any?>) -> Expression<Any?>) {
            map[key] = modify(map[key] ?: default(key))
        }
    }.modification(modification, serializer, table)
    for(entry in map) {
        this.update(table.col[entry.key]!! as Column<Any?>, entry.value)
    }
}

@Suppress("UNCHECKED_CAST")
fun <T> UpdateReturningOldStatement.modification(
    modification: Modification<T>,
    serializer: KSerializer<T>,
    table: SerialDescriptorTable
) {
    val map = HashMap<String, Expression<Any?>>()
    object: FieldModifier {
        fun default(key: String) = table.col[key]!! as Expression<Any?>
        override fun modify(key: String, modify: (Expression<Any?>) -> Expression<Any?>) {
            map[key] = modify(map[key] ?: default(key))
        }
    }.modification(modification, serializer, table)
    for(entry in map) {
        this.update(table.col[entry.key]!! as Column<Any?>, entry.value)
    }
}

internal fun <T> FieldModifier.modification(
    modification: Modification<T>,
    serializer: KSerializer<T>,
    table: SerialDescriptorTable
): Unit = modification(modification, FieldSet2(serializer, table))
@Suppress("UNCHECKED_CAST")
private fun <T> FieldModifier.modification(
    modification: Modification<T>,
    fieldSet: FieldSet2<T>
): Unit {
    when(modification) {
        is Modification.Chain -> modification.modifications.forEach { modification(modification, fieldSet) }
        is Modification.Assign -> modifyEach(fieldSet, modification.value) { type, it, old -> it }
        is Modification.IfNotNull<*> -> modification<Any?>(modification.modification as Modification<Any?>, fieldSet as FieldSet2<Any?>)
        is Modification.CoerceAtMost -> modifySingle(fieldSet) { type, old -> CustomFunction("LEAST", type, fieldSet.formatSingleExpression(modification.value), old) }
        is Modification.CoerceAtLeast -> modifySingle(fieldSet) { type, old -> CustomFunction("GREATEST", type, fieldSet.formatSingleExpression(modification.value), old) }
        is Modification.Increment -> modifySingle(fieldSet) { type, old -> PlusOp(fieldSet.formatSingleExpression(modification.by), old, type) }
        is Modification.Multiply -> modifySingle(fieldSet) { type, old -> TimesOp(fieldSet.formatSingleExpression(modification.by), old, type) }
        is Modification.AppendString -> modifySingle(fieldSet) { type, old -> Concat("", old, fieldSet.formatSingleExpression(modification.value as T)) as Expression<Any?> }
        is Modification.AppendRawString -> modifySingle(fieldSet) { type, old -> Concat("", old, fieldSet.formatSingleExpression(modification.value as T)) as Expression<Any?> }
        is Modification.ListAppend<*> -> modifyEach(fieldSet, modification.items as T) { type, it, old -> ConcatOp(old, it) }
        is Modification.ListRemove<*> -> fieldSet.fields.forEach {
            modify(it.key) { old -> MapOp(fieldSet as FieldSet2<List<Any?>>, { f -> f.fields[it.key]!! }, { SqlExpressionBuilder.run { NotOp(condition(modification.condition as Condition<Any?>, it)) } }) as Expression<Any?> }
        }
        is Modification.ListRemoveInstances<*> -> modification(Modification.ListRemove(Condition.Inside(modification.items)) as Modification<T>, fieldSet)
        is Modification.ListPerElement<*> -> fieldSet.fields.forEach {
            modify(it.key) { old -> MapOp(
                sources = fieldSet as FieldSet2<List<Any?>>,
                mapper = { f ->
                    lateinit var result: Expression<Any?>
                    object: FieldModifier {
                        override fun modify(key: String, modify: (Expression<Any?>) -> Expression<Any?>) {
                            result = modify(f.fields[it.key]!!)
                        }
                    }.modification(modification.modification as Modification<Any?>, f)
                    if(modification.condition is Condition.Always<*>) result
                    else with(SqlExpressionBuilder) {
                        case()
                            .When(condition(modification.condition as Condition<Any?>, f), result)
                            .Else(f.fields[it.key]!!)
                    }
                }
            ) as Expression<Any?> }
        }
        is Modification.ListDropFirst<*> -> fieldSet.fields.forEach {
            modify(it.key) { old -> SliceOp(old as Expression<List<Any?>>, from = LiteralOp(IntegerColumnType(), 2)) as Expression<Any?> }
        }
        is Modification.ListDropLast<*> -> fieldSet.fields.forEach {
            modify(it.key) { old -> SliceOp(old as Expression<List<Any?>>, to = MinusOp(ArrayLengthOp(old as Expression<List<Any?>>), LiteralOp(IntegerColumnType(), 1), IntegerColumnType())) as Expression<Any?> }
        }
        is Modification.SetDropFirst<*> -> fieldSet.fields.forEach {
            modify(it.key) { old -> SliceOp(old as Expression<List<Any?>>, from = LiteralOp(IntegerColumnType(), 2)) as Expression<Any?> }
        }
        is Modification.SetDropLast<*> -> fieldSet.fields.forEach {
            modify(it.key) { old -> SliceOp(old as Expression<List<Any?>>, to = MinusOp(ArrayLengthOp(old as Expression<List<Any?>>), LiteralOp(IntegerColumnType(), 1), IntegerColumnType())) as Expression<Any?> }
        }
        is Modification.SetAppend<*> -> {
            if(fieldSet.fields.size == 1) {
                modifySingle(fieldSet) { type, old ->
                    object: Op<Any?>() {
                        override fun toQueryBuilder(queryBuilder: QueryBuilder) {
                            queryBuilder.append("ARRAY(SELECT DISTINCT UNNEST(")
                            queryBuilder.append(old)
                            queryBuilder.append(" || ")
                            queryBuilder.append(fieldSet.formatSingleExpression(modification.items as T))
                            queryBuilder.append("))")
                        }
                    }
                }
            } else TODO()
        }
        is Modification.SetRemove<*> -> fieldSet.fields.forEach {
            modify(it.key) { old -> MapOp(fieldSet as FieldSet2<List<Any?>>, { f -> f.fields[it.key]!! }, { SqlExpressionBuilder.run { NotOp(condition(modification.condition as Condition<Any?>, it)) } }) as Expression<Any?> }
        }
        is Modification.SetRemoveInstances<*> -> modification(Modification.SetRemove(Condition.Inside(modification.items.toList())) as Modification<T>, fieldSet)
        is Modification.SetPerElement<*> -> fieldSet.fields.forEach {
            modify(it.key) { old -> MapOp(
                sources = fieldSet as FieldSet2<List<Any?>>,
                mapper = { f ->
                    lateinit var result: Expression<Any?>
                    object: FieldModifier {
                        override fun modify(key: String, modify: (Expression<Any?>) -> Expression<Any?>) {
                            result = modify(f.fields[it.key]!!)
                        }
                    }.modification(modification.modification as Modification<Any?>, f)
                    if(modification.condition is Condition.Always<*>) result
                    else with(SqlExpressionBuilder) {
                        case()
                            .When(condition(modification.condition as Condition<Any?>, f), result)
                            .Else(f.fields[it.key]!!)
                    }
                }
            ) as Expression<Any?> }
        }
        is Modification.Combine<*> -> TODO()
        is Modification.ModifyByKey<*> -> TODO()
        is Modification.RemoveKeys<*> -> TODO()
        is Modification.OnField<*, *> -> sub(modification.key.name).modification(modification.modification as Modification<Any?>, fieldSet.sub(modification.key as SerializableProperty<T, Any?>))
    }
}