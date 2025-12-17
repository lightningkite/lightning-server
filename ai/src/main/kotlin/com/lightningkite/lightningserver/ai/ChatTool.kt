package com.lightningkite.lightningserver.ai

import ai.koog.agents.core.tools.ToolDescriptor
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.typed.AuthAccess
import com.lightningkite.services.database.HasId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlin.time.Instant

/**
 * Result of checking whether a tool call requires approval.
 */
public sealed class ApprovalRequirement {
    /** Execute immediately without approval */
    public data object AutoApproved : ApprovalRequirement()

    /**
     * Requires explicit user approval for this specific call.
     *
     * @property reason Human-readable explanation of why approval is needed
     * @property description Human-readable description of what this specific call will do
     */
    public data class RequiresApproval(
        val reason: String,
        val description: String,
    ) : ApprovalRequirement()
}

/**
 * An object representing a lazily-evaluated explanation of something that is needed to know to use a set of tools.
 * We share them to reduce the amount of context used.
 */
public interface SharedExplanation {
    public val includes: Set<SharedExplanation> get() = setOf()
    public fun render(): String
}

public data class TotalExplanation(
    val unique: String,
    val sharedExplanations: List<SharedExplanation> = listOf(),
)

/**
 * A tool that can be used in SystemChatEndpoints with dynamic approval workflow support.
 *
 * Tools can determine whether they require approval based on:
 * - The authenticated user's permissions
 * - The specific arguments for this call
 * - Conversation-level pre-authorizations
 * - Any other business logic
 *
 * @param T The argument type for this tool
 */
public abstract class ChatTool<Subject: HasId<*>?, T> {
    /** Unique name for this tool (used in LLM function calling) */
    public abstract val name: String

    /** Description for the LLM to understand when to use this tool */
    context(serverRuntime: ServerRuntime) public abstract suspend fun description(
        auth: AuthAccess<Subject>,
    ): TotalExplanation

    /** Serializer for the arguments */
    public abstract val argsSerializer: KSerializer<T>

    /** Serializer for the arguments */
    private val noWrappingNeeded by lazy { argsSerializer.descriptor.kind == StructureKind.CLASS && !argsSerializer.descriptor.isInline }
    @Serializable
    private data class Box<T>(
        val argument: T
    )
    public fun koogSerializer(module: SerializersModule): KSerializer<*> = if(noWrappingNeeded) argsSerializer else Box.serializer(argsSerializer)
    @Suppress("UNCHECKED_CAST")
    public fun koogArgParse(fromKoog: Any?): T = if(noWrappingNeeded) fromKoog as T else (fromKoog as Box<T>).argument

    context(serverRuntime: ServerRuntime)
    public suspend fun koogDescriptor(auth: AuthAccess<Subject>): ToolDescriptor = koogSerializer(serverRuntime.externalSerialization.json.serializersModule).asToolDescriptor(name, description(auth).unique, serverRuntime.externalSerialization.json.serializersModule, 6)

    /**
     * Check if this specific call requires approval.
     *
     * This method can consider:
     * - The authenticated user's permissions
     * - The specific arguments (e.g., deleting own post vs others')
     * - Conversation-level authorizations (pre-approved tools)
     * - Rate limits, time of day, etc.
     *
     * @param serverRuntime The server runtime context
     * @param auth The current user's auth context
     * @param args The parsed arguments for this call
     * @param conversationAuthorizations Tools the user has pre-authorized for this conversation
     * @return [ApprovalRequirement.AutoApproved] to execute immediately,
     *         or [ApprovalRequirement.RequiresApproval] to wait for user approval
     */
    context(serverRuntime: ServerRuntime) public abstract suspend fun checkApproval(
        auth: AuthAccess<Subject>,
        args: T,
        conversationAuthorizations: Set<ToolAuthorization>,
    ): ApprovalRequirement

    /**
     * Execute the tool with the given arguments.
     * Only called after approval check passes or user explicitly approves.
     *
     * @param serverRuntime The server runtime context
     * @param auth The current user's auth context
     * @param args The parsed arguments
     * @return The result string to send back to the LLM
     */
    context(serverRuntime: ServerRuntime) public abstract suspend fun execute(
        auth: AuthAccess<Subject>,
        args: T,
    ): String

    /**
     * Human-readable description of what this specific call will do.
     * Used in approval UI and for audit logging.
     *
     * Override to provide more descriptive messages like "Delete blog post 'My Title'"
     * instead of the default which just shows the tool name.
     *
     * @param args The parsed arguments
     * @return Description of the action
     */
    public open fun describeCall(args: T): String = name
}

/**
 * Helper to check if a tool is authorized in the given set of authorizations.
 * Handles wildcard "*" authorization and expiration checking.
 */
public fun <Subject: HasId<*>?> ChatTool<Subject, *>.isAuthorizedIn(
    authorizations: Set<ToolAuthorization>,
    currentTime: Instant,
): Boolean = authorizations.any { auth ->
    (auth.toolName == name || auth.toolName == "*") &&
    (auth.expiresAt == null || auth.expiresAt > currentTime)
}

/**
 * A tool that never requires approval. Use for read-only operations.
 *
 * Subclasses only need to implement [execute] - approval check always returns [ApprovalRequirement.AutoApproved].
 */
public abstract class AutoApprovedTool<Subject: HasId<*>?, T> : ChatTool<Subject, T>() {
    context(serverRuntime: ServerRuntime) final override suspend fun checkApproval(
        auth: AuthAccess<Subject>,
        args: T,
        conversationAuthorizations: Set<ToolAuthorization>,
    ): ApprovalRequirement = ApprovalRequirement.AutoApproved
}

/**
 * A tool that always requires approval unless pre-authorized for the conversation.
 *
 * Subclasses need to implement [execute] and provide [approvalReason].
 * Override [describeCall] for better approval UI messages.
 */
public abstract class AlwaysRequiresApprovalTool<Subject: HasId<*>?, T>(
    /** Human-readable reason why this tool requires approval */
    public val approvalReason: String,
) : ChatTool<Subject, T>() {

    context(serverRuntime: ServerRuntime) final override suspend fun checkApproval(
        auth: AuthAccess<Subject>,
        args: T,
        conversationAuthorizations: Set<ToolAuthorization>,
    ): ApprovalRequirement {
        // Check if pre-authorized for this conversation
        if (isAuthorizedIn(conversationAuthorizations, with(serverRuntime) { now() })) {
            return ApprovalRequirement.AutoApproved
        }

        return ApprovalRequirement.RequiresApproval(
            reason = approvalReason,
            description = describeCall(args)
        )
    }
}
