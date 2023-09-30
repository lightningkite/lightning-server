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
