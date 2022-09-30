package com.lightningkite.lightningserver.db

import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.statements.api.PreparedStatementApi
import org.jetbrains.exposed.sql.statements.Statement
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.sql.ResultSet

abstract class ReturningStatement(type: StatementType, targets: List<Table>) :
    Iterable<ResultRow>, Statement<ResultSet>(type, targets) {
    protected val transaction get() = TransactionManager.current()

    abstract val set: FieldSet

    override fun PreparedStatementApi.executeInternal(transaction: Transaction): ResultSet =
        executeQuery()

    private var iterator: Iterator<ResultRow>? = null

    fun exec() {
        require(iterator == null) { "already executed" }

        val resultIterator = ResultIterator(transaction.exec(this)!!)
        iterator = if (transaction.db.supportsMultipleResultSets) resultIterator
        else Iterable { resultIterator }.toList().iterator()
    }

    override fun iterator(): Iterator<ResultRow> =
        iterator ?: throw IllegalStateException("must call exec() first")

    protected inner class ResultIterator(val rs: ResultSet) : Iterator<ResultRow> {
        private var hasNext: Boolean? = null

        private val fieldsIndex = set.realFields.toSet().mapIndexed { index, expression -> expression to index }.toMap()

        override operator fun next(): ResultRow {
            if (hasNext == null) hasNext()
            if (hasNext == false) throw NoSuchElementException()
            hasNext = null
            return ResultRow.create(rs, fieldsIndex)
        }

        override fun hasNext(): Boolean {
            if (hasNext == null) hasNext = rs.next()
            if (hasNext == false) rs.close()
            return hasNext!!
        }
    }
}

class DeleteReturningStatement(
    private val table: Table,
    private val where: Op<Boolean>? = null,
    private val limit: Int? = null,
    private val returning: ColumnSet? = null,
) : ReturningStatement(StatementType.DELETE, listOf(table)) {
    override val set: FieldSet = returning ?: table

    override fun prepareSQL(transaction: Transaction): String = buildString {
        append("DELETE FROM ")
        append(transaction.identity(table))
        if (where != null) {
            append(" WHERE ")
            append(QueryBuilder(true).append(where).toString())
        }
        if (limit != null) {
            append(" LIMIT ")
            append(limit)
        }
        append(" RETURNING ")
        if (returning != null) {
            append(QueryBuilder(true).append(returning).toString())
        } else {
            append("*")
        }
    }

    override fun arguments(): Iterable<Iterable<Pair<IColumnType, Any?>>> =
        QueryBuilder(true).run {
            where?.toQueryBuilder(this)
            listOf(args)
        }

    companion object {
        fun where(
            table: Table,
            op: Op<Boolean>,
            limit: Int? = null,
            returning: ColumnSet? = null,
        ): DeleteReturningStatement = DeleteReturningStatement(
            table,
            op,
            limit,
            returning
        ).apply {
            exec()
        }
    }
}

fun Table.deleteReturningWhere(
    limit: Int? = null,
    returning: ColumnSet? = null,
    where: SqlExpressionBuilder.() -> Op<Boolean>,
): DeleteReturningStatement =
    DeleteReturningStatement.where(
        this,
        SqlExpressionBuilder.run(where),
        limit,
        returning
    )

class UpdateReturningStatement(
    private val table: Table,
    private val where: Op<Boolean>? = null,
    private val limit: Int? = null,
    private val returning: ColumnSet? = null,
) : ReturningStatement(StatementType.UPDATE, listOf(table)) {
    override val set: FieldSet = returning ?: table

    private val firstDataSet: List<Pair<Column<*>, Any?>>
        get() = values.toList()

    override fun prepareSQL(transaction: Transaction): String =
        with(QueryBuilder(true)) {
            +"UPDATE "
            table.describe(transaction, this)

            firstDataSet.appendTo(this, prefix = " SET ") { (col, value) ->
                append("${transaction.identity(col)}=")
                registerArgument(col, value)
            }

            where?.let {
                +" WHERE "
                +it
            }
            limit?.let {
                +" LIMIT "
                +it
            }

            +" RETURNING "
            if (returning != null) {
                append(returning)
            } else {
                +"*"
            }

            toString()
        }

    override fun arguments(): Iterable<Iterable<Pair<IColumnType, Any?>>> =
        QueryBuilder(true).run {
            for ((key, value) in values) {
                registerArgument(key, value)
            }
            where?.toQueryBuilder(this)
            listOf(args)
        }

    // region UpdateBuilder
    private val values: MutableMap<Column<*>, Any?> = LinkedHashMap()

    operator fun <S> set(column: Column<S>, value: S) {
        when {
            values.containsKey(column) -> error("$column is already initialized")
            !column.columnType.nullable && value == null -> error("Trying to set null to not nullable column $column")
            else -> values[column] = value
        }
    }

    @JvmName("setWithEntityIdExpression")
    operator fun <S, ID : EntityID<S>, E : Expression<S>> set(
        column: Column<ID>,
        value: E,
    ) {
        require(!values.containsKey(column)) { "$column is already initialized" }
        values[column] = value
    }

    @JvmName("setWithEntityIdValue")
    operator fun <S : Comparable<S>, ID : EntityID<S>, E : S?> set(
        column: Column<ID>,
        value: E,
    ) {
        require(!values.containsKey(column)) { "$column is already initialized" }
        values[column] = value
    }

    operator fun <T, S : T, E : Expression<S>> set(column: Column<T>, value: E) =
        update(column, value)

    operator fun <S> set(column: CompositeColumn<S>, value: S) {
        @Suppress("UNCHECKED_CAST")
        column.getRealColumnsWithValues(value).forEach { (realColumn, itsValue) ->
            set(
                realColumn as Column<Any?>,
                itsValue
            )
        }
    }

    fun <T, S : T?> update(column: Column<T>, value: Expression<S>) {
        require(!values.containsKey(column)) { "$column is already initialized" }
        values[column] = value
    }

    fun <T, S : T?> update(
        column: Column<T>,
        value: SqlExpressionBuilder.() -> Expression<S>,
    ) {
        require(!values.containsKey(column)) { "$column is already initialized" }
        values[column] = SqlExpressionBuilder.value()
    }
    // endregion
}

fun <T : Table> T.updateReturning(
    where: SqlExpressionBuilder.() -> Op<Boolean>,
    limit: Int? = null,
    returning: ColumnSet? = null,
    body: T.(UpdateReturningStatement) -> Unit,
): UpdateReturningStatement = UpdateReturningStatement(
    this,
    SqlExpressionBuilder.run(where),
    limit,
    returning
).apply {
    this@updateReturning.body(this)
    exec()
}

class UpdateReturningOldStatement(
    private val table: Table,
    private val where: Op<Boolean>? = null,
    private val limit: Int? = null,
) : ReturningStatement(StatementType.UPDATE, listOf(table)) {
    val readAlias = table.alias("old")
    override val set: FieldSet = readAlias
//    override val set: FieldSet = readAlias.slice(readAlias.columns.map { it.alias("old__" + it.name) } + writeAlias.columns.map { it.alias("new__" + it.name) })

    private val firstDataSet: List<Pair<Column<*>, Any?>>
        get() = values.toList()

    override fun prepareSQL(transaction: Transaction): String =
        with(QueryBuilder(true)) {
            +"UPDATE "
            table.describe(transaction, this)

            firstDataSet.appendTo(this, prefix = " SET ") { (col, value) ->
                append("${transaction.identity(col)}=")
                registerArgument(col, value)
            }

            +" FROM (SELECT * FROM "
            table.describe(transaction, this)
            where?.let {
                +" WHERE "
                +it
            }
            limit?.let {
                +" LIMIT "
                +(it.toString())
            }
            +" FOR UPDATE) old"

            +" WHERE "
            +AndOp(table.primaryKey!!.columns.map { EqOp(it, readAlias[it]) })

            +" RETURNING "
            var first = true
            readAlias.columns.forEach {
                if (first) first = false
                else +","
                +it
                +" AS "
                +transaction.identity(it)
            }

            toString()
        }

    override fun arguments(): Iterable<Iterable<Pair<IColumnType, Any?>>> =
        QueryBuilder(true).run {
            for ((key, value) in values) {
                registerArgument(key, value)
            }
            where?.toQueryBuilder(this)
            listOf(args)
        }

    // region UpdateBuilder
    private val values: MutableMap<Column<*>, Any?> = LinkedHashMap()

    operator fun <S> set(column: Column<S>, value: S) {
        when {
            values.containsKey(column) -> error("$column is already initialized")
            !column.columnType.nullable && value == null -> error("Trying to set null to not nullable column $column")
            else -> values[column] = value
        }
    }

    @JvmName("setWithEntityIdExpression")
    operator fun <S, ID : EntityID<S>, E : Expression<S>> set(
        column: Column<ID>,
        value: E,
    ) {
        require(!values.containsKey(column)) { "$column is already initialized" }
        values[column] = value
    }

    @JvmName("setWithEntityIdValue")
    operator fun <S : Comparable<S>, ID : EntityID<S>, E : S?> set(
        column: Column<ID>,
        value: E,
    ) {
        require(!values.containsKey(column)) { "$column is already initialized" }
        values[column] = value
    }

    operator fun <T, S : T, E : Expression<S>> set(column: Column<T>, value: E) =
        update(column, value)

    operator fun <S> set(column: CompositeColumn<S>, value: S) {
        @Suppress("UNCHECKED_CAST")
        column.getRealColumnsWithValues(value).forEach { (realColumn, itsValue) ->
            set(
                realColumn as Column<Any?>,
                itsValue
            )
        }
    }

    fun <T, S : T?> update(column: Column<T>, value: Expression<S>) {
        require(!values.containsKey(column)) { "$column is already initialized" }
        values[column] = value
    }

    fun <T, S : T?> update(
        column: Column<T>,
        value: SqlExpressionBuilder.() -> Expression<S>,
    ) {
        require(!values.containsKey(column)) { "$column is already initialized" }
        values[column] = SqlExpressionBuilder.value()
    }
    // endregion
}

fun <T : Table> T.updateReturningOld(
    where: SqlExpressionBuilder.() -> Op<Boolean>,
    limit: Int? = null,
    body: T.(UpdateReturningOldStatement) -> Unit,
): UpdateReturningOldStatement = UpdateReturningOldStatement(
    this,
    SqlExpressionBuilder.run(where),
    limit
).apply {
    this@updateReturningOld.body(this)
    exec()
}