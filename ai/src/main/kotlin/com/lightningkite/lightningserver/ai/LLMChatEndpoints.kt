package com.lightningkite.lightningserver.ai

import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.Message
import ai.koog.agents.core.tools.ToolDescriptor
import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.auth.fetch
import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.typed.AuthAccess
import com.lightningkite.services.ai.koog.LLMClientAndModel
import com.lightningkite.services.ai.koog.LLMClientAndModelSettings
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.ModelPermissions
import com.lightningkite.services.database.insertOne
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * A convenient base class for chat endpoints that use direct LLM execution.
 *
 * Extends [SystemChatEndpoints] with a standard LLM execution loop that:
 * - Builds prompts from conversation history
 * - Calls the LLM with tool definitions
 * - Processes tool calls through the approval workflow
 * - Loops until a final text response or max iterations
 *
 * Subclasses implement abstract properties to configure the assistant.
 *
 * Example:
 * ```kotlin
 * class MyAssistant(
 *     database: ServerSetting<Database.Settings, Database>,
 *     llm: ServerSetting<LLMClientAndModelSettings, LLMClientAndModel>,
 *     myModelInfo: ModelInfo<User, MyModel, Uuid>,
 * ) : LLMChatEndpoints<User>(
 *     database = database,
 *     llmSetting = llm,
 *     authRequirement = MyAuth.require(),
 *     conversationPermissions = { ... },
 *     messagePermissions = { ... },
 * ) {
 *     override val subjectSerializer = User.serializer()
 *     override val systemPrompt = "You are a helpful assistant..."
 *     override val tools = (myModelInfo.readTools(20) + myModelInfo.writeTools(5))
 *         .associateBy { it.name }
 * }
 * ```
 */
public abstract class LLMChatEndpoints<Subject : HasId<*>>(
    database: ServerSetting<Database.Settings, Database>,
    private val llmSetting: ServerSetting<LLMClientAndModelSettings, LLMClientAndModel>,
    authRequirement: AuthRequirement<Subject>,
    conversationPermissions: suspend context(ServerRuntime) AuthAccess<Subject>.() -> ModelPermissions<SystemChatConversation>,
    messagePermissions: suspend context(ServerRuntime) AuthAccess<Subject>.() -> ModelPermissions<SystemChatMessage>,
    private val maxIterations: Int = 15,
    responseLockTimeout: Duration = 5.minutes,
    toolLockTimeout: Duration = 5.minutes,
) : SystemChatEndpoints<Subject>(
    database = database,
    authRequirement = authRequirement,
    conversationPermissions = conversationPermissions,
    messagePermissions = messagePermissions,
    responseLockTimeout = responseLockTimeout,
    toolLockTimeout = toolLockTimeout,
) {
    /**
     * The system prompt that instructs the LLM on its role and capabilities.
     */
    protected abstract val systemPrompt: String

    /**
     * Map of tool name to ChatTool. Typically built from ModelInfo.readTools() and writeTools().
     */
    protected abstract val tools: Map<String, ChatTool<Subject, *>>

    /**
     * Serializer for the Subject type. Used to serialize user info for the LLM context.
     */
    protected abstract val subjectSerializer: KSerializer<Subject>

    /**
     * Build additional sections to include in the system prompt.
     *
     * Override this to add custom context for your use case. Each section is a pair
     * of (label, content) that will be formatted as "label:\ncontent" in the prompt.
     *
     * The default implementation includes the current user's data. Call `super` and
     * add your own sections, or return a completely custom list.
     *
     * Example:
     * ```kotlin
     * override suspend fun buildPromptSections(
     *     auth: AuthAccess<User>,
     *     conversation: SystemChatConversation,
     * ): List<Pair<String, String>> = buildList {
     *     addAll(super.buildPromptSections(auth, conversation))
     *     add("Current time" to Clock.System.now().toString())
     *     add("User's recent orders" to fetchRecentOrders(auth).joinToString("\n"))
     * }
     * ```
     *
     * @return List of (label, content) pairs to include in the system prompt
     */
    context(_: ServerRuntime)
    protected open suspend fun buildPromptSections(
        auth: AuthAccess<Subject>,
        conversation: SystemChatConversation,
    ): List<Pair<String, String>> = buildList {
        auth.authOrNull?.let { authentication ->
            val subject = authentication.fetch()
            val subjectJson = Json.encodeToString(subjectSerializer, subject)
            add("Current user" to subjectJson)
        }
    }

    override fun findToolByName(
        serverRuntime: ServerRuntime,
        auth: AuthAccess<Subject>,
        conversation: SystemChatConversation,
        toolName: String
    ): ChatTool<Subject, *>? = tools[toolName]

    override suspend fun respond(
        serverRuntime: ServerRuntime,
        auth: AuthAccess<Subject>,
        conversation: SystemChatConversation,
    ) {
        with(serverRuntime) {
            val llm = llmSetting()
            val sections = buildPromptSections(auth, conversation)

            val fullSystemPrompt = buildString {
                append(systemPrompt)
                for ((label, content) in sections) {
                    append("\n\n")
                    append(label)
                    append(":\n")
                    append(content)
                }
            }

            runLLMLoop(
                llm = llm,
                tools = tools,
                auth = auth,
                conversation = conversation,
                systemPrompt = fullSystemPrompt,
                maxIterations = maxIterations,
            )
        }
    }
}

//
// Helper functions for LLM chat execution
//

/**
 * Tracks tool results within a single LLM execution loop.
 */
public data class PendingToolResult(
    val toolCallId: String,
    val toolName: String,
    val arguments: String,
    val result: String
)

/**
 * Runs an LLM execution loop that processes tool calls through the approval workflow.
 *
 * This function:
 * 1. Checks for pending approvals and stops if found
 * 2. Builds a prompt from conversation history
 * 3. Calls the LLM
 * 4. Processes tool calls or returns the final text response
 * 5. Loops until done or max iterations reached
 */
context(_: ServerRuntime)
public suspend fun <Subject : HasId<*>> SystemChatEndpoints<Subject>.runLLMLoop(
    llm: LLMClientAndModel,
    tools: Map<String, ChatTool<Subject, *>>,
    auth: AuthAccess<Subject>,
    conversation: SystemChatConversation,
    systemPrompt: String,
    maxIterations: Int = 15,
) {
    val toolDescriptors = tools.values.map {
        it.koogDescriptor(serverRuntime.externalSerialization.json.serializersModule)
    }
    val pendingToolResults = mutableListOf<PendingToolResult>()

    suspend fun insertMessage(role: SystemChatMessage.Role, content: String) {
        messageInfo.table().insertOne(SystemChatMessage(
            conversationId = conversation._id,
            subjectId = conversation.subjectId,
            role = role,
            content = content,
            createdAt = now()
        ))
    }

    repeat(maxIterations) {
        val history = getConversationHistory(auth, conversation._id)
        if (history.hasPendingApproval()) return

        val responses = llm.execute(
            prompt = history.toKoogPrompt(systemPrompt, pendingToolResults),
            tools = toolDescriptors
        )

        if (responses.isEmpty()) {
            insertMessage(SystemChatMessage.Role.Error, "LLM returned no response")
            return
        }

        val textContent = responses
            .filterIsInstance<Message.Assistant>()
            .map { it.content }
            .filter { it.isNotBlank() }
            .joinToString("\n")

        if (textContent.isNotBlank()) {
            insertMessage(SystemChatMessage.Role.Assistant, textContent)
        }

        val toolCalls = responses.filterIsInstance<Message.Tool.Call>()

        if (toolCalls.isEmpty()) {
            return
        }

        // Process tool calls
        pendingToolResults.clear()
        for (tc in toolCalls) {
            val result = processLLMToolCall(tools, auth, conversation, tc)
            if (result == null) return  // Waiting for approval
            pendingToolResults.add(result)
        }
    }

    insertMessage(SystemChatMessage.Role.Error, "Response generation stopped: max iterations ($maxIterations) reached")
}

/**
 * Process a single tool call from the LLM response.
 * Returns null if waiting for approval, otherwise returns the tool result record.
 */
context(_: ServerRuntime)
private suspend fun <Subject : HasId<*>> SystemChatEndpoints<Subject>.processLLMToolCall(
    tools: Map<String, ChatTool<Subject, *>>,
    auth: AuthAccess<Subject>,
    conversation: SystemChatConversation,
    toolCall: Message.Tool.Call,
): PendingToolResult? {
    val toolCallId = toolCall.id ?: ""
    val toolName = toolCall.tool
    val arguments = toolCall.content

    fun result(output: String) = PendingToolResult(toolCallId, toolName, arguments, output)

    val tool = tools[toolName]
        ?: return result("Error: Unknown tool '$toolName'")

    @Suppress("UNCHECKED_CAST")
    return when (val callResult = processToolCall(auth, conversation, tool as ChatTool<Subject, Any>, arguments)) {
        is SystemChatEndpoints.ToolCallResult.WaitingForApproval -> null
        is SystemChatEndpoints.ToolCallResult.Executed -> result(callResult.result)
        is SystemChatEndpoints.ToolCallResult.Error -> result("Error: ${callResult.error}")
    }
}

/**
 * Check if conversation history has a pending tool approval.
 */
public fun List<SystemChatMessage>.hasPendingApproval(): Boolean = any { msg ->
    msg.role == SystemChatMessage.Role.ToolRequest &&
    msg.tool?.requiresApproval == true &&
    msg.tool?.approval == null
}

/**
 * Convert conversation history to a Koog Prompt.
 */
public fun List<SystemChatMessage>.toKoogPrompt(
    systemPrompt: String,
    pendingToolResults: List<PendingToolResult> = emptyList(),
): Prompt = prompt(existing = Prompt.Empty) {
    system(systemPrompt)

    for (msg in this@toKoogPrompt) {
        val msgContent = msg.content
        when (msg.role) {
            SystemChatMessage.Role.User -> user(msgContent)
            SystemChatMessage.Role.Assistant -> assistant(msgContent)
            SystemChatMessage.Role.System -> system(msgContent)
            SystemChatMessage.Role.ToolRequest -> {
                val toolData = msg.tool ?: continue
                val toolName = toolData.toolName
                val toolCallId = msg._id.toString()
                val toolArgs = toolData.arguments
                val toolResult = toolData.result
                val toolError = toolData.error
                val approval = toolData.approval

                tool {
                    call(toolCallId, toolName, toolArgs)
                }

                when {
                    toolResult != null -> tool { result(toolCallId, toolName, toolResult) }
                    toolError != null -> tool { result(toolCallId, toolName, "Error: $toolError") }
                    approval != null && !approval.approved -> {
                        tool { result(toolCallId, toolName, "Tool execution rejected: ${approval.reason ?: "User declined"}") }
                    }
                }
            }
            SystemChatMessage.Role.Thinking -> { /* skip */ }
            SystemChatMessage.Role.Error -> system("Previous error: $msgContent")
        }
    }

    for (record in pendingToolResults) {
        tool {
            call(record.toolCallId, record.toolName, record.arguments)
            result(record.toolCallId, record.toolName, record.result)
        }
    }
}
