package com.lightningkite.lightningserver.ai

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.data.IndexSet
import com.lightningkite.services.data.References
import com.lightningkite.services.database.HasId
import com.lightningkite.services.files.ServerFile
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A distributed lock for response processing.
 * Contains holder ID for ownership verification and timestamp for timeout recovery.
 */
@Serializable
@GenerateDataClassPaths
public data class ProcessingLock(
    /** Unique ID of the lock holder (e.g., task execution UUID) */
    val holderId: String,
    /** When the lock was acquired */
    val acquiredAt: Instant,
)

/**
 * A distributed lock for tool execution.
 * Ensures tools are only executed once even with concurrent requests.
 */
@Serializable
@GenerateDataClassPaths
public data class ToolExecutionLock(
    /** Unique ID of the lock holder */
    val holderId: String,
    /** When the lock was acquired */
    val acquiredAt: Instant,
)

/**
 * Record of tool approval decision with audit trail.
 */
@Serializable
@GenerateDataClassPaths
public data class ToolApproval(
    /** Whether the tool was approved or rejected */
    val approved: Boolean,
    /** Subject ID of the user who approved/rejected */
    val approvedBy: String,
    /** When the approval decision was made */
    val approvedAt: Instant,
    /** Optional reason for rejection */
    val reason: String? = null,
)

/**
 * Records that a user has authorized a tool (or tool pattern) for a conversation.
 * When authorized, future calls to the tool won't require individual approval.
 */
@Serializable
@GenerateDataClassPaths
public data class ToolAuthorization(
    /** The tool name to authorize, or "*" for all tools */
    val toolName: String,
    /** Who authorized this */
    val authorizedBy: String,
    /** When it was authorized */
    val authorizedAt: Instant,
    /** Optional expiration - null means permanent for the conversation */
    val expiresAt: Instant? = null,
)

/**
 * Data specific to tool request messages.
 * Encapsulates tool name, arguments, approval state, execution lock, and results.
 */
@Serializable
@GenerateDataClassPaths
public data class ToolRequestData(
    /** Name of the tool being called */
    val toolName: String,
    /** JSON-formatted arguments for the tool */
    val arguments: String,
    /** Whether this tool requires user approval before execution */
    val requiresApproval: Boolean = false,
    /** Human-readable reason why approval is required */
    val approvalReason: String? = null,
    /** Approval record (null = pending if requiresApproval is true) */
    val approval: ToolApproval? = null,
    /** Execution lock to prevent duplicate execution */
    val executionLock: ToolExecutionLock? = null,
    /** Result of tool execution (null if not yet executed) */
    val result: String? = null,
    /** Error message if tool execution failed */
    val error: String? = null,
)

/**
 * Represents a conversation in the system chat.
 * Conversations contain messages and track processing state.
 */
@Serializable
@GenerateDataClassPaths
@IndexSet(["subjectId", "createdAt"])
public data class SystemChatConversation(
    override val _id: Uuid = Uuid.random(),
    /** The authenticated user's ID who owns this conversation */
    val subjectId: String,
    /** Optional display name for the conversation */
    val name: String = "",
    /** Whether to automatically trigger response generation on new user messages */
    val autoProcess: Boolean = true,
    /** Distributed lock for response processing */
    val processingLock: ProcessingLock? = null,
    /** Tools (or patterns) the user has pre-authorized for this conversation */
    val toolAuthorizations: List<ToolAuthorization> = emptyList(),
    /** When the conversation was created */
    val createdAt: Instant,
    /** When the conversation was last updated */
    val updatedAt: Instant = createdAt,
) : HasId<Uuid>

/**
 * Represents a single message in a system chat conversation.
 *
 * Messages can be from users, the assistant, system prompts, tool requests,
 * thinking/reasoning, or errors.
 */
@Serializable
@GenerateDataClassPaths
@IndexSet(["conversationId", "createdAt"])
@IndexSet(["subjectId", "createdAt"])
public data class SystemChatMessage(
    override val _id: Uuid = Uuid.random(),
    /** The conversation this message belongs to */
    @References(SystemChatConversation::class)
    val conversationId: Uuid,
    /** The subject ID associated with this message */
    val subjectId: String,
    /** The type/role of this message */
    val role: Role,
    /** Additional context/categorization as needed by implementors */
    val channel: String? = null,
    /** The message content (text for most roles, human-readable description for ToolRequest) */
    val content: String,
    /** File attachments */
    val attachments: List<ServerFile> = emptyList(),
    /** When the message was created */
    val createdAt: Instant,
    /** Tool-specific data (only present when role == ToolRequest) */
    val tool: ToolRequestData? = null,
) : HasId<Uuid> {

    /**
     * Message role/type enumeration.
     */
    @Serializable
    public enum class Role {
        /** Messages from the user */
        User,
        /** Messages from the system/AI assistant */
        Assistant,
        /** System prompts or context */
        System,
        /** Tool/function call request with approval workflow */
        ToolRequest,
        /** LLM reasoning/chain-of-thought */
        Thinking,
        /** Error messages from the system */
        Error
    }
}

/**
 * Request body for approving or rejecting a tool request.
 */
@Serializable
public data class ToolApprovalRequest(
    /** Whether to approve (true) or reject (false) the tool request */
    val approved: Boolean,
    /** Optional reason for rejection */
    val reason: String? = null,
)

/**
 * Request body for authorizing a tool for the conversation.
 */
@Serializable
public data class AuthorizeToolRequest(
    /** Tool name to authorize, or "*" for all tools */
    val toolName: String,
    /** Optional duration in seconds - null means permanent for conversation */
    val durationSeconds: Long? = null,
)
