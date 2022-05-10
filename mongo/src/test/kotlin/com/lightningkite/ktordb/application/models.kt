@file:UseContextualSerialization(UUID::class, Instant::class)
package com.lightningkite.ktordb.application

import com.lightningkite.ktordb.*
import com.lightningkite.ktordb.HasId
import com.lightningkite.ktordb.DatabaseModel
import com.lightningkite.ktordb.UUIDFor
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import org.litote.kmongo.coroutine.CoroutineClient
import java.time.Instant
import java.util.*

lateinit var defaultMongo: MongoDatabase

@DatabaseModel()
@Serializable
data class User(
    override val _id: UUID = UUID.randomUUID(),
    var email: String,
    var age:Long = 0,
    var friends: List<UUIDFor<User>> = listOf()
): HasId { companion object }

val User.Companion.mongo get() = defaultMongo.collection<User>()

@DatabaseModel()
@Serializable
data class Post(
    override val _id: UUID = UUID.randomUUID(),
    var author: UUIDFor<User>,
    var content: String,
    var at: Long? = null
): HasId { companion object }


val Post.Companion.mongo get() = defaultMongo.collection<Post>()

@DatabaseModel()
@Serializable
data class Employee(
    override val _id: @Contextual UUID = UUID.randomUUID(),
    var dictionary: Map<String, Int> = mapOf(),
): HasId { companion object }

val Employee.Companion.mongo get() = defaultMongo.collection<Employee>()



@DatabaseModel
@Serializable
data class EmbeddedObjectTest(
    override val _id: UUID = UUID.randomUUID(),
    var name:String = "",
    var embed1:ClassUsedForEmbedding = ClassUsedForEmbedding("value1", 1),
    var embed2:ClassUsedForEmbedding = ClassUsedForEmbedding("value2", 2),
): HasId { companion object }

val EmbeddedObjectTest.Companion.mongo get() = defaultMongo.collection<EmbeddedObjectTest>()
    .postCreate { println("Created $it") }
    .preDelete { println("Deleted $it") }
    .postChange { old, new -> println("Changed $old to $new") }

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
    var name:String = "",
    var embed1:ClassUsedForEmbedding? = null,
): HasId { companion object }

val EmbeddedNullable.Companion.mongo get() = defaultMongo.collection<EmbeddedNullable>()

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
): HasId { companion object }
val LargeTestModel.Companion.mongo get() = defaultMongo.collection<LargeTestModel>()

@DatabaseModel
@Serializable
data class EmbeddedMap(
    override val _id: UUID = UUID.randomUUID(),
    var map: Map<String, RecursiveEmbed>,
) : HasId
val EmbeddedMap.Companion.mongo get() = defaultMongo.collection<EmbeddedMap>()
