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
data class TestModel(
    override val _id: UUID = UUID.randomUUID(),
    val timestamp: Instant = Instant.now(),
    val name: String = "No Name",
    @Description("The number") val number: Int = 3123,
    @MimeType("text/html") @JsonSchemaFormat("jodit") val content: String = "",
    @MimeType("image/*") val file: ServerFile? = null,
    @References(TestModel::class) val replyTo: UUID? = null,
    val status: Status = Status.DRAFT
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
    override val email: String
) : HasId<UUID>, HasEmail