@file:SharedCode
@file:UseContextualSerialization(UUID::class, Instant::class)
package com.lightningkite.lightningdb

import com.lightningkite.khrysalis.SharedCode
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import java.time.Instant
import java.util.*

@DatabaseModel()
@Serializable
data class User(
    override val _id: UUID = UUID.randomUUID(),
    var email: String,
    var age: Long = 0,
    var friends: List<UUIDFor<User>> = listOf()
) : HasId<UUID> {
    companion object
}

@DatabaseModel()
@Serializable
data class Post(
    override val _id: UUID = UUID.randomUUID(),
    var author: UUIDFor<User>,
    var content: String,
    var at: Long? = null
) : HasId<UUID> {
    companion object
}

@DatabaseModel()
@Serializable
data class Employee(
    override val _id: @Contextual UUID = UUID.randomUUID(),
    var dictionary: Map<String, Int> = mapOf(),
) : HasId<UUID> {
    companion object
}


@DatabaseModel
@Serializable
data class EmbeddedObjectTest(
    override val _id: UUID = UUID.randomUUID(),
    var name: String = "",
    var embed1: ClassUsedForEmbedding = ClassUsedForEmbedding("value1", 1),
    var embed2: ClassUsedForEmbedding = ClassUsedForEmbedding("value2", 2),
) : HasId<UUID> {
    companion object
}


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

@DatabaseModel
@Serializable
data class EmbeddedNullable(
    override val _id: UUID = UUID.randomUUID(),
    var name: String = "",
    var embed1: ClassUsedForEmbedding? = null,
) : HasId<UUID> {
    companion object
}

@DatabaseModel
@Serializable
data class LargeTestModel(
    override val _id: UUID = UUID.randomUUID(),
    var boolean: Boolean = false,
    var byte: Byte = 0,
    var short: Short = 0,
    var int: Int = 0,
    var long: Long = 0,
    var float: Float = 0f,
    var double: Double = 0.0,
    var char: Char = ' ',
    var string: String = "",
    var instant: Instant = Instant.ofEpochMilli(0L),
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

@DatabaseModel
@Serializable
data class EmbeddedMap(
    override val _id: UUID = UUID.randomUUID(),
    var map: Map<String, RecursiveEmbed>,
) : HasId<UUID>

@DatabaseModel
@Serializable
data class Cursed(
    override val _id: UUID = UUID.randomUUID(),
    val insideClass: Inside<UUID>
): HasId<UUID> {
    @Serializable
    data class Inside<T>(val item: T)
}

@DatabaseModel
@Serializable
data class MetaModel(
    override val _id: UUID = UUID.randomUUID(),
    val number: Int = 22,
    val condition: Condition<MetaModel> = Condition.Always(),
    val modification: Modification<MetaModel> = Modification.Chain(listOf())
): HasId<UUID>