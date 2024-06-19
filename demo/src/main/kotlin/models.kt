@file:UseContextualSerialization(Instant::class, UUID::class, ServerFile::class)
package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningdb.*
import kotlinx.datetime.Clock
import com.lightningkite.now
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlinx.datetime.Instant
import java.util.*
import com.lightningkite.UUID
import com.lightningkite.uuid

@Serializable
@GenerateDataClassPaths
@AdminTableColumns(["name", "number", "status"])
@Description("A model for testing Lightning Server.")
data class TestModel(
    override val _id: UUID = uuid(),
    val timestamp: Instant = now(),
    val name: String = "No Name",
    @Description("The number") val number: Int = 3123,
    @MimeType("text/html") @Multiline val content: String = "",
    @MimeType("image/*") val file: ServerFile? = null,
    @References(TestModel::class) val replyTo: UUID? = null,
    @MultipleReferences(TestModel::class) val comments: List<UUID> = listOf(),
    val privateInfo: String? = null,
    val status: Status = Status.DRAFT,
    @AdminHidden val hiddenField: Boolean = false
) : HasId<UUID>

@Serializable
enum class Status {
    @DisplayName("Draft") DRAFT,
    @DisplayName("Published") PUBLISHED
}

@Serializable
@GenerateDataClassPaths
data class User(
    override val _id: UUID = uuid(),
    override val email: String,
    override val hashedPassword: String = "",
    val isSuperUser: Boolean = false,
) : HasId<UUID>, HasEmail, HasPassword

@Serializable
@GenerateDataClassPaths
data class UserAlt(
    override val _id: UUID = uuid(),
    override val email: String
) : HasId<UUID>, HasEmail