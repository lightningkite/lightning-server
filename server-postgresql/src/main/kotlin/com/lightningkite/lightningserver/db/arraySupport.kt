package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.listElement
import kotlinx.serialization.KSerializer
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.statements.jdbc.JdbcConnectionImpl
import org.jetbrains.exposed.sql.transactions.TransactionManager

fun <T> Table.array(name: String, columnType: ColumnType): Column<List<T>> = registerColumn(name, ArrayColumnType(columnType))
fun Table.arrayTypeless(name: String, columnType: ColumnType): Column<List<*>> = registerColumn(name, ArrayColumnType(columnType))

class ArrayColumnType(val type: ColumnType) : ColumnType() {
    override fun sqlType(): String = buildString {
        append(type.sqlType())
        append(" ARRAY")
    }
    override fun valueToDB(value: Any?): Any? {
        if(value == null) return null
        if (value is List<*>) {
            val columnType = type.sqlType().split("(")[0]
            val jdbcConnection = (TransactionManager.current().connection as JdbcConnectionImpl).connection
            return jdbcConnection.createArrayOf(columnType, value.map { type.valueToDB(it) }.toTypedArray())
        } else {
            throw IllegalStateException("Not sure how to translate $value into a db array!")
        }
    }
    override fun valueFromDB(value: Any): List<Any?> {
        return when (value) {
            is java.sql.Array -> (value.array as Array<*>).toList()
            is Array<*> -> value.toList()
            is List<*> -> value
            else -> error("Not sure how to parse ${value} (${value::class.qualifiedName}) from the database!")
        }.map { if(it == null) null else type.valueFromDB(it) }
    }

    override fun valueToString(value: Any?): String {
        if(value == null) return "NULL"
        return when (value) {
            is java.sql.Array -> (value.array as Array<*>).toList()
            is Array<*> -> value.toList()
            is List<*> -> value
            else -> error("Not sure how to parse ${value} (${value::class.qualifiedName}) from the database!")
        }.joinToString(",", "ARRAY[", "]") { type.valueToString(it) }
    }

    override fun notNullValueToDB(value: Any): Any {
        when (value) {
            is Array<*> -> {
                if (value.isEmpty())
                    return "'{}'"

                val columnType = type.sqlType().split("(")[0]
                val jdbcConnection = (TransactionManager.current().connection as JdbcConnectionImpl).connection
                return jdbcConnection.createArrayOf(columnType, value.map { type.valueToDB(it) }.toTypedArray()) ?: error("Can't create non null array for $value")
            }
            is List<*> -> {
                if (value.isEmpty())
                    return "'{}'"

                val columnType = type.sqlType().split("(")[0]
                val jdbcConnection = (TransactionManager.current().connection as JdbcConnectionImpl).connection
                return jdbcConnection.createArrayOf(columnType, value.map { type.valueToDB(it) }.toTypedArray()) ?: error("Can't create non null array for $value")
            }
            else -> throw IllegalStateException("Not sure how to translate $value (${value::class.qualifiedName}) into a db array!")
        }
    }
    fun arrayLengthOp(value: Expression<List<*>>) = CustomFunction<Int>("array_length", IntegerColumnType(), value, intLiteral(1))
    fun index(array: Expression<List<*>>, value: Expression<*>) = CustomFunction<Int>("array_position", IntegerColumnType(), array, value)
}

//class ArrayOp<T>(val expr1: Expression<List<T>>, val keyword: String = "ANY") : Op<T>() {
//    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
//        if (expr2 is OrOp) {
//            queryBuilder.append("(").append(expr2).append(")")
//        } else {
//            queryBuilder.append(expr2)
//        }
//        queryBuilder.append(" = $keyword (")
//        if (expr1 is OrOp) {
//            queryBuilder.append("(").append(expr1).append(")")
//        } else {
//            queryBuilder.append(expr1)
//        }
//        queryBuilder.append(")")
//    }
//}

class GetOp<T>(val source: Expression<List<T>>, val index: Expression<Int>): Op<T>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append(source)
        queryBuilder.append("[")
        queryBuilder.append(index)
        queryBuilder.append("]")
    }
}

class AllIsTrueOp(val source: Expression<List<Boolean>>): Op<Boolean>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append("TRUE = ALL (")
        queryBuilder.append(source)
        queryBuilder.append(")")
    }
}

class AnyIsTrueOp(val source: Expression<List<Boolean>>): Op<Boolean>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append("TRUE = ANY (")
        queryBuilder.append(source)
        queryBuilder.append(")")
    }
}

internal class MapOp<A, B>(val sources: FieldSet2<List<A>>, val mapper: (FieldSet2<A>)->Expression<B>): Op<List<B>>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append("(SELECT ")
        queryBuilder.append(mapper(
            FieldSet2<A>(
                sources.serializer.listElement()!! as KSerializer<A>,
                fields = sources.fields.mapValues {
                    object: ExpressionWithColumnType<Any?>() {
                        override val columnType: IColumnType
                            get() = (it.value.columnType as ArrayColumnType).type
                        override fun toQueryBuilder(queryBuilder: QueryBuilder) {
                            queryBuilder.append(it.key.takeUnless { it.isEmpty() } ?: "it")
                        }
                    }
                }
            )
        ))
        queryBuilder.append(" FROM unnest(")
        var first = true
        for(f in sources.fields) {
            if(first) first = false
            else queryBuilder.append(", ")
            queryBuilder.append(f.value)
        }
        queryBuilder.append(") x(")
        first = true
        for(s in sources.fields) {
            if(first) first = false
            else queryBuilder.append(", ")
            queryBuilder.append(s.key.takeUnless { it.isEmpty() } ?: "it")
        }
        queryBuilder.append("))")
    }
}

class ContainsOp(expr1: Expression<*>, expr2: Expression<*>) : ComparisonOp(expr1, expr2, "@>")

infix fun<T, S> ExpressionWithColumnType<T>.contains(arry: List<S>) : Op<Boolean> = ContainsOp(this, QueryParameter(arry, columnType))

class InsensitiveLikeEscapeOp(expr1: Expression<*>, expr2: Expression<*>, like: Boolean, val escapeChar: Char?) : ComparisonOp(expr1, expr2, if (like) "ILIKE" else "NOT ILIKE") {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        super.toQueryBuilder(queryBuilder)
        if (escapeChar != null){
            with(queryBuilder){
                +" ESCAPE "
                +stringParam(escapeChar.toString())
            }
        }
    }
}
