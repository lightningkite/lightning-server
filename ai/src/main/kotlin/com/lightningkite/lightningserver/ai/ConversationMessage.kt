package com.lightningkite.lightningserver.ai

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.HasId
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * Represents a single message in a conversation with an AI chatbot.
 *
 * Messages are stored in order and used to maintain conversation history
 * across multiple chat() calls.
 */
@Serializable
@GenerateDataClassPaths
public data class ConversationMessage(
    override val _id: Uuid = Uuid.random(),
    val conversationId: Uuid,
    val role: Role,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
) : HasId<Uuid> {
    @Serializable
    public enum class Role {
        User,
        Assistant,
        System
    }
}
