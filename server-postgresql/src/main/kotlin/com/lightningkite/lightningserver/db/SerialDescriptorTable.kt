package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.serialization.Serialization
import io.ktor.websocket.Serializer
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.internal.GeneratedSerializer
import kotlinx.serialization.modules.SerializersModule
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.javatime.*
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.*

class SerialDescriptorTable(val descriptor: SerialDescriptor) : Table(descriptor.serialName.substringAfterLast('.')) {
    init {
        descriptor.columnType().forEach {
            registerColumn<Any?>(it.first.joinToString("__"), it.second)
        }
    }
}

/**
 * TABLE FORMAT
 *
 * Lists become Structure of Arrays (SOA)
 * Maps become Structure of Arrays (SOA) as well
 * Classes have an additional not null field if needed
 *
 */

internal fun SerialDescriptor.columnType(): List<Pair<List<String>, ColumnType>> = when(this.unnull()) {
    UUIDSerializer.descriptor -> listOf(listOf<String>() to UUIDColumnType().also { it.nullable = this.isNullable })
    LocalDateSerializer.descriptor -> listOf(listOf<String>() to JavaLocalDateColumnType().also { it.nullable = this.isNullable })
    InstantSerializer.descriptor -> listOf(listOf<String>() to JavaInstantColumnType().also { println(this); it.nullable = this.isNullable })
    DurationSerializer.descriptor -> listOf(listOf<String>() to JavaDurationColumnType().also { it.nullable = this.isNullable })
    //LocalDateTimeSerializer.descriptor -> listOf(listOf<String>() to JavaLocalDateTimeColumnType().also { it.nullable = this.isNullable })
    LocalTimeSerializer.descriptor -> listOf(listOf<String>() to JavaLocalTimeColumnType().also { it.nullable = this.isNullable })
    ZonedDateTimeSerializer.descriptor -> listOf(
        listOf<String>() to JavaInstantColumnType().also { it.nullable = this.isNullable },
        listOf<String>("zone") to VarCharColumnType(32).also { it.nullable = this.isNullable },
    )
    else -> when (kind) {
        SerialKind.CONTEXTUAL -> throw Error()
        PolymorphicKind.OPEN -> throw NotImplementedError()
        PolymorphicKind.SEALED -> throw NotImplementedError()
        PrimitiveKind.BOOLEAN -> listOf(listOf<String>() to BooleanColumnType().also { it.nullable = this.isNullable })
        PrimitiveKind.BYTE -> listOf(listOf<String>() to ByteColumnType().also { it.nullable = this.isNullable })
        PrimitiveKind.CHAR -> listOf(listOf<String>() to CharColumnType(1).also { it.nullable = this.isNullable })
        PrimitiveKind.DOUBLE -> listOf(listOf<String>() to DoubleColumnType().also { it.nullable = this.isNullable })
        PrimitiveKind.FLOAT -> listOf(listOf<String>() to FloatColumnType().also { it.nullable = this.isNullable })
        PrimitiveKind.INT -> listOf(listOf<String>() to IntegerColumnType().also { it.nullable = this.isNullable })
        PrimitiveKind.LONG -> listOf(listOf<String>() to LongColumnType().also { it.nullable = this.isNullable })
        PrimitiveKind.SHORT -> listOf(listOf<String>() to ShortColumnType().also { it.nullable = this.isNullable })
        PrimitiveKind.STRING -> listOf(listOf<String>() to TextColumnType().also { it.nullable = this.isNullable })
        SerialKind.ENUM -> listOf(listOf<String>() to TextColumnType().also { it.nullable = this.isNullable })
        StructureKind.LIST -> getRealElementDescriptor(0).columnType()
            .map { it.first to ArrayColumnType(it.second).also { it.nullable = this.isNullable } }
        StructureKind.CLASS -> {
            val nullCol = if (isNullable) listOf(listOf<String>("exists") to BooleanColumnType()) else listOf()
            nullCol + (0 until elementsCount).flatMap {
                this.getRealElementDescriptor(it).columnType().map { sub ->
                    (listOf(getElementName(it)) + sub.first) to sub.second.also {
                        it.nullable = it.nullable || isNullable
                    }
                }
            }
        }

        StructureKind.MAP -> {
            getRealElementDescriptor(0).columnType()
                .map { it.first to ArrayColumnType(it.second).also { it.nullable = this.isNullable } }
                .plus(
                    getRealElementDescriptor(1).columnType().map {
                        it.first.plus("value") to ArrayColumnType(it.second).also {
                            it.nullable = this.isNullable
                        }
                    })
        }

        StructureKind.OBJECT -> listOf(listOf<String>() to TextColumnType().also { it.nullable = this.isNullable })
    }
}

private fun SerialDescriptor.getRealElementDescriptor(index: Int): SerialDescriptor {
    val e = getElementDescriptor(index)
    return if (e.kind == SerialKind.CONTEXTUAL) {
        if(e.isNullable) {
            SerialDescriptorForNullable(Serialization.module.getContextualDescriptor(e)!!)
        } else {
            Serialization.module.getContextualDescriptor(e)!!
        }
    }
    else e
}

private fun SerialDescriptor.unnull(): SerialDescriptor = this.nullElement() ?: this

internal class SerialDescriptorForNullable(
    internal val original: SerialDescriptor
) : SerialDescriptor by original {

    override val serialName: String = original.serialName + "?"
    override val isNullable: Boolean
        get() = true

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SerialDescriptorForNullable) return false
        if (original != other.original) return false
        return true
    }

    override fun toString(): String {
        return "$original?"
    }

    override fun hashCode(): Int {
        return original.hashCode() * 31
    }
}

//interface PerColumnWriter {
//    fun <T> handle(column: FutureColumn<T>, value: T)
//}
//
//interface PerColumnReader {
//    fun <T> handle(column: FutureColumn<T>): T
//}
//
//data class FutureColumn<T>(val name: String, val type: ColumnType, val table: Table) {
//    val column: Column<T> by lazy { table.registerColumn(name, type) }
//}
//
//interface FieldHandler<M : Any, T> {
//    val columns: List<FutureColumn<*>>
//    fun split(value: T, action: PerColumnWriter)
//    fun merge(action: PerColumnReader): T
//}
//
//class ColumnHandler<M : Any, T>(val column: FutureColumn<T>) : FieldHandler<M, T> {
//    override val columns: List<FutureColumn<*>> = listOf(column)
//    override fun split(value: T, action: PerColumnWriter) {
//        action.handle(column, value)
//    }
//
//    override fun merge(action: PerColumnReader): T {
//        return action.handle(column)
//    }
//}
//
//
//class ZonedDateTimeFieldHandler<M : Any>(val prefix: List<String>, table: Table, isNullable: Boolean) :
//    FieldHandler<M, ZonedDateTime> {
//    val main = ColumnHandler<M, Instant>(
//        FutureColumn<Instant>(
//            prefix.joinToString("__"),
//            JavaInstantColumnType().also { it.nullable = isNullable },
//            table
//        )
//    )
//    val zone = ColumnHandler<M, String>(
//        FutureColumn<String>(
//            prefix.plus("zone").joinToString("__"),
//            TextColumnType().also { it.nullable = isNullable },
//            table
//        )
//    )
//    override val columns: List<FutureColumn<*>> = listOf(main.column, zone.column)
//    override fun split(value: ZonedDateTime, action: PerColumnWriter) {
//        action.handle(main.column, value.toInstant())
//        action.handle(zone.column, value.zone.id)
//    }
//
//    override fun merge(action: PerColumnReader): ZonedDateTime {
//        return ZonedDateTime.ofInstant(
//            action.handle(main.column),
//            ZoneId.of(action.handle(zone.column))
//        )
//    }
//}
//
//class ListFieldHandler<M : Any, T>(val it: FieldHandler<M, T>) : FieldHandler<M, List<T>> {
//    val columnMap: Map<FutureColumn<*>, FutureColumn<List<Any?>>> = it.columns.associateWith {
//        FutureColumn(it.name, ArrayColumnType(it.type), it.table)
//    }
//    override val columns: List<FutureColumn<*>> = columnMap.values.toList()
//
//    override fun split(value: List<T>, action: PerColumnWriter) {
//        val virtual = HashMap<FutureColumn<*>, ArrayList<Any?>>()
//        for (item in value) {
//            it.split(item, object : PerColumnWriter {
//                override fun <T> handle(column: FutureColumn<T>, value: T) {
//                    virtual.getOrPut(column) { ArrayList<Any?>() }.add(value)
//                }
//            })
//        }
//        for (entry in columnMap) {
//            action.handle(entry.value, virtual[entry.key]!!)
//        }
//    }
//
//    override fun merge(action: PerColumnReader): List<T> {
//        val parts = HashMap<FutureColumn<*>, List<Any?>>()
//        for (entry in columnMap) {
//            parts[entry.key] = action.handle(entry.value)
//        }
//        return parts.values.first().indices.map { index ->
//            it.merge(object : PerColumnReader {
//                override fun <T> handle(column: FutureColumn<T>): T = parts[column]!![index] as T
//            })
//        }
//    }
//}
//
//class MapFieldHandler<M : Any, K, V>(val keyHandler: FieldHandler<M, K>, val valueHandler: FieldHandler<M, V>) :
//    FieldHandler<M, Map<K, V>> {
//    val keyColumnMap: Map<FutureColumn<*>, FutureColumn<List<Any?>>> = keyHandler.columns.associateWith {
//        FutureColumn(it.name, ArrayColumnType(it.type), it.table)
//    }
//    val valueColumnMap: Map<FutureColumn<*>, FutureColumn<List<Any?>>> = valueHandler.columns.associateWith {
//        FutureColumn(it.name, ArrayColumnType(it.type), it.table)
//    }
//    override val columns: List<FutureColumn<*>> = keyColumnMap.values.toList() + valueColumnMap.values.toList()
//
//    override fun split(value: Map<K, V>, action: PerColumnWriter) {
//        val virtualKeys = HashMap<FutureColumn<*>, ArrayList<Any?>>()
//        val virtualValues = HashMap<FutureColumn<*>, ArrayList<Any?>>()
//        for (item in value) {
//            keyHandler.split(item.key, object : PerColumnWriter {
//                override fun <T> handle(column: FutureColumn<T>, value: T) {
//                    virtualKeys.getOrPut(column) { ArrayList<Any?>() }.add(value)
//                }
//            })
//            valueHandler.split(item.value, object : PerColumnWriter {
//                override fun <T> handle(column: FutureColumn<T>, value: T) {
//                    virtualValues.getOrPut(column) { ArrayList<Any?>() }.add(value)
//                }
//            })
//        }
//        for (entry in keyColumnMap) {
//            action.handle(entry.value, virtualKeys[entry.key]!!)
//        }
//        for (entry in valueColumnMap) {
//            action.handle(entry.value, virtualValues[entry.key]!!)
//        }
//    }
//
//    override fun merge(action: PerColumnReader): Map<K, V> {
//        val partsKey = HashMap<FutureColumn<*>, List<Any?>>()
//        val partsValue = HashMap<FutureColumn<*>, List<Any?>>()
//        for (entry in keyColumnMap) {
//            partsKey[entry.key] = action.handle(entry.value)
//        }
//        for (entry in valueColumnMap) {
//            partsValue[entry.key] = action.handle(entry.value)
//        }
//        return partsKey.values.first().indices.associate { index ->
//            keyHandler.merge(object : PerColumnReader {
//                override fun <T> handle(column: FutureColumn<T>): T = partsKey[column]!![index] as T
//            }) to valueHandler.merge(object : PerColumnReader {
//                override fun <T> handle(column: FutureColumn<T>): T = partsValue[column]!![index] as T
//            })
//        }
//    }
//}
//
//@OptIn(InternalSerializationApi::class)
//class ClassFieldHandler<M : Any, T>(val serializer: KSerializer<T>, table: Table, prefix: List<String>) :
//    FieldHandler<M, T> {
//    val serialDescriptor get() = serializer.descriptor
//    val parts = (0 until serialDescriptor.elementsCount).map {
//        @Suppress("UNCHECKED_CAST")
//        ((serializer as GeneratedSerializer<*>).childSerializers().get(it) as KSerializer<Any?>).fieldHandler<M, Any?>(
//            table,
//            prefix + serialDescriptor.getElementName(it)
//        )
//    }
//
//    override val columns: List<FutureColumn<*>> = parts.flatMap { it.columns }
//
//    override fun merge(action: PerColumnReader): T {
//        TODO("Not yet implemented")
//    }
//
//    override fun split(value: T, action: PerColumnWriter) {
//        TODO("Not yet implemented")
//    }
//}
//
//private fun <M : Any, T> KSerializer<T>.fieldHandler(
//    table: Table,
//    prefix: List<String> = listOf(),
//): FieldHandler<M, T> = when (this.descriptor.unnull()) {
//    ServerFileSerialization.descriptor -> ColumnHandler(
//        FutureColumn(
//            prefix.joinToString("__"),
//            TextColumnType().also { it.nullable = this.descriptor.isNullable },
//            table
//        )
//    )
//
//    UUIDSerializer.descriptor -> ColumnHandler(
//        FutureColumn(
//            prefix.joinToString("__"),
//            UUIDColumnType().also { it.nullable = this.descriptor.isNullable },
//            table
//        )
//    )
//
//    LocalDateSerializer.descriptor -> ColumnHandler(
//        FutureColumn(
//            prefix.joinToString("__"),
//            JavaLocalDateColumnType().also { it.nullable = this.descriptor.isNullable },
//            table
//        )
//    )
//
//    InstantSerializer.descriptor -> ColumnHandler(
//        FutureColumn(
//            prefix.joinToString("__"),
//            JavaInstantColumnType().also { it.nullable = this.descriptor.isNullable },
//            table
//        )
//    )
//
//    DurationSerializer.descriptor -> ColumnHandler(
//        FutureColumn(
//            prefix.joinToString("__"),
//            JavaDurationColumnType().also { it.nullable = this.descriptor.isNullable },
//            table
//        )
//    )
//    //LocalDateTimeSerializer.descriptor -> listOf(listOf<String>() to JavaLocalDateTimeColumnType().also { it.nullable = this.isNullable })
//    LocalTimeSerializer.descriptor -> ColumnHandler(
//        FutureColumn(
//            prefix.joinToString("__"),
//            JavaLocalTimeColumnType().also { it.nullable = this.descriptor.isNullable },
//            table
//        )
//    )
//
//    ZonedDateTimeSerializer.descriptor -> ZonedDateTimeFieldHandler<M>(
//        prefix,
//        table,
//        descriptor.isNullable
//    ) as FieldHandler<M, T>
//
//    else -> when (descriptor.kind) {
//        SerialKind.CONTEXTUAL -> throw Error()
//        PolymorphicKind.OPEN -> throw NotImplementedError()
//        PolymorphicKind.SEALED -> throw NotImplementedError()
//        PrimitiveKind.BOOLEAN -> ColumnHandler(
//            FutureColumn(
//                prefix.joinToString("__"),
//                BooleanColumnType().also { it.nullable = this.descriptor.isNullable },
//                table
//            )
//        )
//
//        PrimitiveKind.BYTE -> ColumnHandler(
//            FutureColumn(
//                prefix.joinToString("__"),
//                ByteColumnType().also { it.nullable = this.descriptor.isNullable },
//                table
//            )
//        )
//
//        PrimitiveKind.CHAR -> ColumnHandler(
//            FutureColumn(
//                prefix.joinToString("__"),
//                CharColumnType(1).also { it.nullable = this.descriptor.isNullable },
//                table
//            )
//        )
//
//        PrimitiveKind.DOUBLE -> ColumnHandler(
//            FutureColumn(
//                prefix.joinToString("__"),
//                DoubleColumnType().also { it.nullable = this.descriptor.isNullable },
//                table
//            )
//        )
//
//        PrimitiveKind.FLOAT -> ColumnHandler(
//            FutureColumn(
//                prefix.joinToString("__"),
//                FloatColumnType().also { it.nullable = this.descriptor.isNullable },
//                table
//            )
//        )
//
//        PrimitiveKind.INT -> ColumnHandler(
//            FutureColumn(
//                prefix.joinToString("__"),
//                IntegerColumnType().also { it.nullable = this.descriptor.isNullable },
//                table
//            )
//        )
//
//        PrimitiveKind.LONG -> ColumnHandler(
//            FutureColumn(
//                prefix.joinToString("__"),
//                LongColumnType().also { it.nullable = this.descriptor.isNullable },
//                table
//            )
//        )
//
//        PrimitiveKind.SHORT -> ColumnHandler(
//            FutureColumn(
//                prefix.joinToString("__"),
//                ShortColumnType().also { it.nullable = this.descriptor.isNullable },
//                table
//            )
//        )
//
//        PrimitiveKind.STRING -> ColumnHandler(
//            FutureColumn(
//                prefix.joinToString("__"),
//                TextColumnType().also { it.nullable = this.descriptor.isNullable },
//                table
//            )
//        )
//
//        SerialKind.ENUM -> ColumnHandler(
//            FutureColumn(
//                prefix.joinToString("__"),
//                TextColumnType().also { it.nullable = this.descriptor.isNullable },
//                table
//            )
//        )
//
//        StructureKind.LIST -> {
//            @Suppress("UNCHECKED_CAST")
//            val it = (listElement() as KSerializer<Any?>).fieldHandler<M, Any?>(table, prefix)
//            @Suppress("UNCHECKED_CAST")
//            ListFieldHandler(it) as FieldHandler<M, T>
//        }
//
//        StructureKind.CLASS -> {
//            val nullCol = if (descriptor.isNullable) listOf(listOf<String>() to BooleanColumnType()) else listOf()
//            TODO(
//                """nullCol + (0 until elementsCount).flatMap {
//                this.getRealElementDescriptor(it).columnType().map { sub ->
//                    (sub.first + listOf(getElementName(it))) to sub.second
//                }
//            }"""
//            )
//        }
//
//        StructureKind.MAP -> {
//            @Suppress("UNCHECKED_CAST")
//            val k = (mapKeyElement() as KSerializer<Any?>).fieldHandler<M, Any?>(table, prefix + "key")
//
//            @Suppress("UNCHECKED_CAST")
//            val v = (mapValueElement() as KSerializer<Any?>).fieldHandler<M, Any?>(table, prefix + "value")
//            @Suppress("UNCHECKED_CAST")
//            MapFieldHandler(k, v) as FieldHandler<M, T>
//        }
//
//        StructureKind.OBJECT -> ColumnHandler(
//            FutureColumn(
//                prefix.joinToString("__"),
//                TextColumnType().also { it.nullable = descriptor.isNullable },
//                table
//            )
//        )
//    }
//}
