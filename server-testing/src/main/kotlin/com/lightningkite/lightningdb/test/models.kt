@file:UseContextualSerialization(UUID::class, Instant::class, ServerFile::class)
package com.lightningkite.lightningdb.test

import com.lightningkite.GeoCoordinate
import com.lightningkite.lightningdb.*
import com.lightningkite.lightningdb.HasId
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlinx.datetime.Instant
import java.util.*
import com.lightningkite.lightningdb.*
import com.lightningkite.UUID
import com.lightningkite.uuid

@GenerateDataClassPaths()
@Serializable
data class User(
    override val _id: UUID = uuid(),
    @Unique override var email: String,
    @Unique override val phoneNumber: String,
    var age: Long = 0,
    var friends: List<UUID> = listOf()
) : HasId<UUID>, HasEmail, HasPhoneNumber {
    companion object
}

@GenerateDataClassPaths
@Serializable
data class CompoundKeyTestModel(
    override val _id: CompoundTestKey = CompoundTestKey("first", "second")
): HasId<CompoundTestKey>

@GenerateDataClassPaths
@Serializable
data class CompoundTestKey(
    val first: String,
    val second: String,
): Comparable<CompoundTestKey> {
    companion object {
        val comparator = compareBy<CompoundTestKey> { it.first }.thenBy { it.second }
    }
    override fun compareTo(other: CompoundTestKey): Int = comparator.compare(this, other)
}

@GenerateDataClassPaths()
@Serializable
data class Post(
    override val _id: UUID = uuid(),
    var author: UUID,
    var content: String,
    var at: Long? = null
) : HasId<UUID> {
    companion object
}

@GenerateDataClassPaths()
@Serializable
data class Employee(
    override val _id: @Contextual UUID = uuid(),
    var dictionary: Map<String, Int> = mapOf(),
) : HasId<UUID> {
    companion object
}

@GenerateDataClassPaths
@Serializable
data class EmbeddedObjectTest(
    override val _id: UUID = uuid(),
    var name: String = "",
    var embed1: ClassUsedForEmbedding = ClassUsedForEmbedding("value1", 1),
    var embed2: ClassUsedForEmbedding = ClassUsedForEmbedding("value2", 2),
) : HasId<UUID> {
    companion object
}

@GenerateDataClassPaths
@Serializable
data class ClassUsedForEmbedding(
    var value1:String = "default",
    var value2:Int = 1
)

@Serializable
data class RecursiveEmbed(
    var embedded:RecursiveEmbed? = null,
    var value2:String = "value2"
)

@GenerateDataClassPaths
@Serializable
data class HasServerFiles(
    val file: ServerFile,
    val optionalFile: ServerFile? = null
)

@GenerateDataClassPaths
@Serializable
data class EmbeddedNullable(
    override val _id: UUID = uuid(),
    var name: String = "",
    var embed1: ClassUsedForEmbedding? = null,
) : HasId<UUID> {
    companion object
}

@GenerateDataClassPaths
@Serializable
data class LargeTestModel(
    override val _id: UUID = uuid(),
    var boolean: Boolean = false,
    var byte: Byte = 0,
    var short: Short = 0,
    @Index var int: Int = 0,
    var long: Long = 0,
    var float: Float = 0f,
    var double: Double = 0.0,
    var char: Char = ' ',
    var string: String = "",
    var uuid: UUID = UUID(0L, 0L),
    var instant: Instant = Instant.fromEpochMilliseconds(0L),
    var list: List<Int> = listOf(),
    var listEmbedded: List<ClassUsedForEmbedding> = listOf(),
    var set: Set<Int> = setOf(),
    var setEmbedded: Set<ClassUsedForEmbedding> = setOf(),
    var map: Map<String, Int> = mapOf(),
    var embedded: ClassUsedForEmbedding = ClassUsedForEmbedding(),
    var booleanNullable: Boolean? = null,
    var byteNullable: Byte? = null,
    var shortNullable: Short? = null,
    var intNullable: Int? = null,
    var longNullable: Long? = null,
    var floatNullable: Float? = null,
    var doubleNullable: Double? = null,
    var charNullable: Char? = null,
    var stringNullable: String? = null,
    var uuidNullable: UUID? = null,
    var instantNullable: Instant? = null,
    var listNullable: List<Int>? = null,
    var mapNullable: Map<String, Int>? = null,
    var embeddedNullable: ClassUsedForEmbedding? = null,
) : HasId<UUID> {
    companion object
}

@GenerateDataClassPaths
@Serializable
data class GeoTest(
    override val _id: UUID = uuid(),
    val geo: GeoCoordinate = GeoCoordinate(41.727019, -111.8443002)
) : HasId<UUID> {
    companion object
}

@GenerateDataClassPaths
@Serializable
data class EmbeddedMap(
    override val _id: UUID = uuid(),
    var map: Map<String, RecursiveEmbed>,
) : HasId<UUID>

@GenerateDataClassPaths
@Serializable
data class MetaTestModel(
    override val _id: UUID = uuid(),
    val condition: Condition<LargeTestModel>,
    val modification: Modification<LargeTestModel>
) : HasId<UUID> {
}