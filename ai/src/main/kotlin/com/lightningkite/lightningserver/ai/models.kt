package com.lightningkite.lightningserver.ai

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.data.IndexSet
import com.lightningkite.services.data.References
import com.lightningkite.services.database.HasId
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.uuid.Uuid


/**
 * Represents a long-running conversation with an AI chatbot.
 *
 * ConversationMessages belong to a specific conversation.
 */
@Serializable
@GenerateDataClassPaths
@IndexSet(["subjectId", "createdAt"])
public data class Conversation(
    override val _id: Uuid = Uuid.random(),
    val subjectId: String,
    val name: String,
    val createdAt: Instant,
) : HasId<Uuid>


/**
 * Represents a single message in a conversation with an AI chatbot.
 *
 * Messages are stored in order to maintain conversation history.
 */
@Serializable
@GenerateDataClassPaths
@IndexSet(["conversationId", "timestamp"])
@IndexSet(["subjectId", "createdAt"])
public data class ConversationMessage(
    override val _id: Uuid = Uuid.random(),
    @References(Conversation::class) val conversationId: Uuid,
    val subjectId: String,
    val role: Role,
    val channel: String? = null,
    val content: String,
    val createdAt: Instant,
) : HasId<Uuid> {

    @Serializable
    public enum class Role {
        User,
        Assistant,
        System
    }
}


@Serializable
public data class CompressedHistory(
    val compressed: String,
    val llmThinking: List<String>? = null,
    val createdAt: Instant,
)