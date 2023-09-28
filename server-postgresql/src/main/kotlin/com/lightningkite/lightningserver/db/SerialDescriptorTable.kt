package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningdb.Index
import com.lightningkite.lightningserver.serialization.Serialization
import io.ktor.websocket.Serializer
import kotlinx.coroutines.launch
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
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.serializers.InstantIso8601Serializer
import kotlinx.datetime.serializers.LocalDateIso8601Serializer
import kotlinx.datetime.serializers.LocalDateTimeIso8601Serializer
import kotlinx.datetime.serializers.LocalTimeIso8601Serializer
import java.util.*

class SerialDescriptorTable(name: String, val descriptor: SerialDescriptor) : Table(name.replace(".", "__")) {
    val columnsByDotPath = HashMap<List<String>, ArrayList<Column<Any?>>>()
    init {
        descriptor.columnType()
            .forEach {
                val path = buildList<String> {
                    var current = descriptor
                    for (index in it.descriptorPath) {
                        if (current.kind == StructureKind.CLASS) {
                            add(current.getElementName(index))
                        }
                        current = current.getElementDescriptor(index)
                    }
                }
                val col = registerColumn<Any?>(it.key.joinToString("__"), it.type)
                for(partialSize in 1..path.size)
                    columnsByDotPath.getOrPut(path.subList(0, partialSize)) { ArrayList() }.add(col)
            }
    }

    override val primaryKey: PrimaryKey? = columns.find { it.name =="_id" }?.let { PrimaryKey(it) }

    val col = columns.associateBy { it.name }

    init {
        val seen = HashSet<SerialDescriptor>()
        fun handleDescriptor(descriptor: SerialDescriptor) {
            if (!seen.add(descriptor)) return
            descriptor.annotations.forEach {
                when (it) {
                    is UniqueSet -> index(
                        isUnique = true,
                        columns = it.fields.flatMap { columnsByDotPath[it.split('.')]!! }.toTypedArray()
                    )

                    is UniqueSetJankPatch -> {
                        val sets: MutableList<MutableList<String>> = mutableListOf()
                        var current = mutableListOf<String>()
                        it.fields.forEach { value ->
                            if (value == ":") {
                                sets.add(current)
                                current = mutableListOf()
                            } else {
                                current.add(value)
                            }
                        }
                        sets.add(current)
                        sets.forEach { set ->
                            index(
                                isUnique = true,
                                columns = set.flatMap { columnsByDotPath[it.split('.')]!! }.toTypedArray()
                            )
                        }
                    }

                    is IndexSet -> index(
                        isUnique = false,
                        columns = it.fields.flatMap { columnsByDotPath[it.split('.')]!! }.toTypedArray()
                    )

                    is IndexSetJankPatch -> {
                        val sets: MutableList<MutableList<String>> = mutableListOf()
                        var current = mutableListOf<String>()
                        it.fields.forEach { value ->
                            if (value == ":") {
                                sets.add(current)
                                current = mutableListOf()
                            } else {
                                current.add(value)
                            }
                        }
                        sets.add(current)
                        sets.forEach { set ->
                            index(
                                isUnique = true,
                                columns = set.flatMap { columnsByDotPath[it.split('.')]!! }.toTypedArray()
                            )
                        }
                    }

                    is TextIndex -> {
                        // TODO
                    }

                    is NamedUniqueSet -> index(
                        customIndexName = it.indexName,
                        isUnique = true,
                        columns = it.fields.flatMap { columnsByDotPath[it.split('.')]!! }.toTypedArray()
                    )

                    is NamedUniqueSetJankPatch -> {
                        val sets: MutableList<MutableList<String>> = mutableListOf()
                        var current = mutableListOf<String>()
                        it.fields.forEach { value ->
                            if (value == ":") {
                                sets.add(current)
                                current = mutableListOf()
                            } else {
                                current.add(value)
                            }
                        }
                        sets.add(current)
                        val names = it.indexNames.split(":").map { it.trim() }

                        sets.forEachIndexed { index, set ->
                            index(
                                customIndexName = names.getOrNull(index),
                                isUnique = true,
                                columns = it.fields.flatMap { columnsByDotPath[it.split('.')]!! }.toTypedArray()
                            )
                        }
                    }

                    is NamedIndexSet -> index(
                        customIndexName = it.indexName,
                        isUnique = false,
                        columns = it.fields.flatMap { columnsByDotPath[it.split('.')]!! }.toTypedArray()
                    )

                    is NamedIndexSetJankPatch -> {

                        val sets: MutableList<MutableList<String>> = mutableListOf()
                        var current = mutableListOf<String>()
                        it.fields.forEach { value ->
                            if (value == ":") {
                                sets.add(current)
                                current = mutableListOf()
                            } else {
                                current.add(value)
                            }
                        }
                        sets.add(current)
                        val names = it.indexNames.split(":").map { it.trim() }

                        sets.forEachIndexed { index, set ->
                            index(
                                customIndexName = names.getOrNull(index),
                                isUnique = true,
                                columns = it.fields.flatMap { columnsByDotPath[it.split('.')]!! }.toTypedArray()
                            )
                        }

                    }
                }
            }
            (0 until descriptor.elementsCount).forEach { index ->
                val sub = descriptor.getElementDescriptor(index)
                if (sub.kind == StructureKind.CLASS) handleDescriptor(sub)
                descriptor.getElementAnnotations(index).forEach {
                    when (it) {
                        is Unique -> index(
                            isUnique = true,
                            columns = columnsByDotPath[listOf(descriptor.getElementName(index))]!!.toTypedArray()
                        )

                        is Index -> index(
                            isUnique = false,
                            columns = columnsByDotPath[listOf(descriptor.getElementName(index))]!!.toTypedArray()
                        )

                        is NamedUnique -> index(
                            customIndexName = it.indexName,
                            isUnique = true,
                            columns = columnsByDotPath[listOf(descriptor.getElementName(index))]!!.toTypedArray()
                        )

                        is NamedIndex -> index(
                            customIndexName = it.indexName,
                            isUnique = false,
                            columns = columnsByDotPath[listOf(descriptor.getElementName(index))]!!.toTypedArray()
                        )
                    }
                }
            }
        }
        handleDescriptor(descriptor)
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

data class SerialDescriptorColumns(val descriptor: SerialDescriptor, val columns: List<Column<*>>)

internal data class ColumnTypeInfo(val key: List<String>, val type: ColumnType, val descriptorPath: List<Int>)

internal fun SerialDescriptor.columnType(): List<ColumnTypeInfo> = when (this.unnull()) {
    UUIDSerializer.descriptor -> listOf(
        ColumnTypeInfo(
            listOf<String>(),
            UUIDColumnType().also { it.nullable = this.isNullable },
            listOf()
        )
    )

    LocalDateIso8601Serializer.descriptor -> listOf(
        ColumnTypeInfo(
            listOf<String>(),
            JavaLocalDateColumnType().also { it.nullable = this.isNullable },
            listOf()
        )
    )

    InstantIso8601Serializer.descriptor -> listOf(
        ColumnTypeInfo(
            listOf<String>(),
            JavaInstantColumnType().also { it.nullable = this.isNullable },
            listOf()
        )
    )

    DurationSerializer.descriptor -> listOf(
        ColumnTypeInfo(
            listOf<String>(),
            JavaDurationColumnType().also { it.nullable = this.isNullable },
            listOf()
        )
    )
    LocalDateTimeIso8601Serializer.descriptor -> listOf(
        ColumnTypeInfo(
            listOf<String>(),
            JavaLocalDateTimeColumnType().also { it.nullable = this.isNullable },
            listOf()
        )
    )
    LocalTimeIso8601Serializer.descriptor -> listOf(
        ColumnTypeInfo(
            listOf<String>(),
            JavaLocalTimeColumnType().also { it.nullable = this.isNullable },
            listOf()
        )
    )

    else -> when (kind) {
        SerialKind.CONTEXTUAL -> throw Error()
        PolymorphicKind.OPEN -> throw NotImplementedError()
        PolymorphicKind.SEALED -> throw NotImplementedError()
        PrimitiveKind.BOOLEAN -> listOf(
            ColumnTypeInfo(
                listOf<String>(),
                BooleanColumnType().also { it.nullable = this.isNullable },
                listOf()
            )
        )

        PrimitiveKind.BYTE -> listOf(
            ColumnTypeInfo(
                listOf<String>(),
                ByteColumnType().also { it.nullable = this.isNullable },
                listOf()
            )
        )

        PrimitiveKind.CHAR -> listOf(
            ColumnTypeInfo(
                listOf<String>(),
                CharColumnType(1).also { it.nullable = this.isNullable },
                listOf()
            )
        )

        PrimitiveKind.DOUBLE -> listOf(
            ColumnTypeInfo(
                listOf<String>(),
                DoubleColumnType().also { it.nullable = this.isNullable },
                listOf()
            )
        )

        PrimitiveKind.FLOAT -> listOf(
            ColumnTypeInfo(
                listOf<String>(),
                FloatColumnType().also { it.nullable = this.isNullable },
                listOf()
            )
        )

        PrimitiveKind.INT -> listOf(
            ColumnTypeInfo(
                listOf<String>(),
                IntegerColumnType().also { it.nullable = this.isNullable },
                listOf()
            )
        )

        PrimitiveKind.LONG -> listOf(
            ColumnTypeInfo(
                listOf<String>(),
                LongColumnType().also { it.nullable = this.isNullable },
                listOf()
            )
        )

        PrimitiveKind.SHORT -> listOf(
            ColumnTypeInfo(
                listOf<String>(),
                ShortColumnType().also { it.nullable = this.isNullable },
                listOf()
            )
        )

        PrimitiveKind.STRING -> listOf(
            ColumnTypeInfo(
                listOf<String>(),
                TextColumnType().also { it.nullable = this.isNullable },
                listOf()
            )
        )

        SerialKind.ENUM -> listOf(
            ColumnTypeInfo(
                listOf<String>(),
                TextColumnType().also { it.nullable = this.isNullable },
                listOf()
            )
        )

        StructureKind.LIST -> getRealElementDescriptor(0).columnType()
            .map {
                ColumnTypeInfo(
                    it.key,
                    ArrayColumnType(it.type).also { it.nullable = this.isNullable },
                    listOf(0) + it.descriptorPath
                )
            }

        StructureKind.CLASS -> {
            val nullCol = if (isNullable) listOf(
                ColumnTypeInfo(
                    listOf<String>("exists"),
                    BooleanColumnType(),
                    listOf()
                )
            ) else listOf()
            nullCol + (0 until elementsCount).flatMap { index ->
                this.getRealElementDescriptor(index).columnType().map { sub ->
                    ColumnTypeInfo(
                        key = (listOf(getElementName(index)) + sub.key),
                        type = sub.type.also {
                            it.nullable = it.nullable || isNullable
                        },
                        descriptorPath = listOf(index) + sub.descriptorPath
                    )
                }
            }
        }

        StructureKind.MAP -> {
            getRealElementDescriptor(0).columnType()
                .map {
                    ColumnTypeInfo(
                        it.key,
                        ArrayColumnType(it.type).also { it.nullable = this.isNullable },
                        listOf(0) + it.descriptorPath
                    )
                }
                .plus(
                    getRealElementDescriptor(1).columnType().map {
                        ColumnTypeInfo(
                            it.key + "value",
                            ArrayColumnType(it.type).also { it.nullable = this.isNullable },
                            listOf(1) + it.descriptorPath
                        )
                    })
        }

        StructureKind.OBJECT -> listOf(
            ColumnTypeInfo(
                listOf<String>(),
                TextColumnType().also { it.nullable = this.isNullable },
                listOf()
            )
        )
    }
}

private fun SerialDescriptor.getRealElementDescriptor(index: Int): SerialDescriptor {
    val e = getElementDescriptor(index)
    return if (e.kind == SerialKind.CONTEXTUAL) {
        if (e.isNullable) {
            SerialDescriptorForNullable(Serialization.module.getContextualDescriptor(e)!!)
        } else {
            Serialization.module.getContextualDescriptor(e)!!
        }
    } else e
}

private fun SerialDescriptor.unnull(): SerialDescriptor = this.nullElement() ?: this
private fun SerialDescriptor.nullElement(): SerialDescriptor? {
    try {
        val theoreticalMethod = this::class.java.getDeclaredField("original")
        try { theoreticalMethod.isAccessible = true } catch(e: Exception) {}
        return theoreticalMethod.get(this) as SerialDescriptor
    } catch(e: Exception) { return null }
}

internal class SerialDescriptorForNullable(
    internal val original: SerialDescriptor,
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
