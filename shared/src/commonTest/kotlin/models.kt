@file:SharedCode
@file:UseContextualSerialization(UUID::class, Instant::class)
package com.lightningkite.lightningdb

import com.lightningkite.UUID
import com.lightningkite.ZonedDateTime
import com.lightningkite.khrysalis.SharedCode
import com.lightningkite.uuid
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone


@GenerateDataClassPaths()
@Serializable
data class User(
    override val _id: UUID = uuid(),
    var email: String,
    var age: Long = 0,
    var friends: List<UUID> = listOf()
) : HasId<UUID> {
    companion object
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

@Serializable
enum class SampleA { A, B, C }
@Serializable
enum class SampleB { D, E, F }

@GenerateDataClassPaths
@Serializable
data class ClassUsedForEmbedding(
    var value1:String = "default",
    var value2:Int = 1
)

@GenerateDataClassPaths
@Serializable
data class RecursiveEmbed(
    var embedded:RecursiveEmbed? = null,
    var value2:String = "value2"
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
    var int: Int = 0,
    var long: Long = 0,
    var float: Float = 0f,
    var double: Double = 0.0,
    var char: Char = ' ',
    var string: String = "",
    var instant: Instant = Instant.DISTANT_PAST,
    val zonedDateTime: ZonedDateTime = ZonedDateTime(LocalDateTime(2000, 1, 1, 0, 0), TimeZone.currentSystemDefault()),
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
    var instantNullable: Instant? = null,
    var listNullable: List<Int>? = null,
    var mapNullable: Map<String, Int>? = null,
    var embeddedNullable: ClassUsedForEmbedding? = null,
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
data class Cursed(
    override val _id: UUID = uuid(),
    val insideClass: Inside<UUID>
): HasId<UUID> {
    @Serializable
    @GenerateDataClassPaths
    data class Inside<T>(val item: T)
}

//@GenerateDataClassPaths
//@Serializable
//data class MetaModel(
//    override val _id: UUID = uuid(),
//    val number: Int = 22,
//    val condition: Condition<MetaModel> = Condition.Always(),
//    val modification: Modification<MetaModel> = Modification.Chain(listOf())
//): HasId<UUID>