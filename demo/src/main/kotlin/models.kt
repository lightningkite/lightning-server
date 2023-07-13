@file:UseContextualSerialization(Instant::class, UUID::class, ServerFile::class)
package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningdb.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import java.time.Instant
import java.util.*

@Serializable
@DatabaseModel
@AdminTableColumns(["name", "number", "status"])
@Description("A model for testing Lightning Server.")
data class TestModel(
    override val _id: UUID = UUID.randomUUID(),
    val timestamp: Instant = Instant.now(),
    val name: String = "No Name",
    @Description("The number") val number: Int = 3123,
    @MimeType("text/html") @Multiline val content: String = "",
//    @MimeType("image/*") val file: ServerFile? = null,
    @References(TestModel::class) val replyTo: UUID? = null,
    @MultipleReferences(TestModel::class) val comments: List<UUID> = listOf(),
    val privateInfo: String? = null,
    val status: Status = Status.DRAFT,
    val allowedReplies: Condition<TestModel> = Condition.Always(),
    @AdminHidden val hiddenField: Boolean = false
) : HasId<UUID>

@Serializable
enum class Status {
    @DisplayName("Draft") DRAFT,
    @DisplayName("Published") PUBLISHED
}

@Serializable
@DatabaseModel
data class User(
    override val _id: UUID = UUID.randomUUID(),
    override val email: String,
    override val hashedPassword: String = "",
    val isSuperUser: Boolean = false,
) : HasId<UUID>, HasEmail, HasPassword

@Serializable
@DatabaseModel
data class UserAlt(
    override val _id: UUID = UUID.randomUUID(),
    override val email: String
) : HasId<UUID>, HasEmail