package com.lightningkite.lightningserver.ai

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.ForbiddenException
import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.UnauthorizedException
import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.lightningserver.definition.Task
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.PathSpec1
import com.lightningkite.lightningserver.pathing.arg1
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.invoke
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.runtime.send
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.lightningserver.typed.sdk.SdkModule.Companion.withSdkInfo
import com.lightningkite.lightningserver.typed.sdk.module
import com.lightningkite.lightningserver.websockets.WebSocketFrame
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketTopic
import com.lightningkite.lightningserver.websockets.subscribe
import com.lightningkite.services.database.*
import com.lightningkite.services.database.insertOne
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import com.lightningkite.lightningserver.ai.models.*

/**
 * Abstract base class for system chat endpoints with tool approval workflow.
 *
 * Provides a complete chat infrastructure with:
 * - Conversation and message tables with REST endpoints
 * - WebSocket support for real-time message streaming
 * - Tool execution workflow with dynamic approval based on context
 * - Per-conversation tool authorization (users can pre-approve tools)
 * - Distributed locking for response generation and tool execution
 *
 * Subclasses implement [respond] which controls the LLM interaction and uses
 * [processToolCall] to handle tool execution with approval workflow.
 *
 * @param Subject The authenticated user type
 * @param database Database setting for storing conversations and messages
 * @param authRequirement Authentication requirement for all endpoints
 * @param conversationPermissions Permission rules for conversation access
 * @param messagePermissions Permission rules for message access
 * @param responseLockTimeout How long before a response processing lock is considered stale
 * @param toolLockTimeout How long before a tool execution lock is considered stale
 */
public abstract class SystemChatEndpoints<Subject : HasId<*>>(
    database: ServerSetting<Database.Settings, Database>,
    authRequirement: AuthRequirement<Subject>,
    conversationPermissions: suspend context(ServerRuntime) AuthAccess<Subject>.() -> ModelPermissions<SystemChatConversation>,
    messagePermissions: suspend context(ServerRuntime) AuthAccess<Subject>.() -> ModelPermissions<SystemChatMessage>,
    private val responseLockTimeout: Duration = 5.minutes,
    private val toolLockTimeout: Duration = 5.minutes,
) : ServerBuilder() {

    /**
     * Generate a response to a user message or continue after tool execution.
     *
     * Called when:
     * - User creates a new message with role == User
     * - A tool finishes execution (approved and executed, or rejected)
     *
     * Implementation should:
     * - Load conversation history via [getConversationHistory]
     * - Call the LLM with appropriate prompting
     * - For tool calls from LLM: use [processToolCall] which handles approval workflow
     * - Insert assistant messages via [insertAssistantMessage]
     *
     * The processing lock is managed by the framework (acquired before, released after).
     *
     * @param serverRuntime The server runtime context
     * @param auth The authenticated user's access
     * @param conversation The conversation context (includes tool authorizations)
     */
    context(serverRuntime: ServerRuntime) protected abstract suspend fun respond(
        auth: AuthAccess<Subject>,
        conversation: SystemChatConversation,
    )

    /**
     * Find a tool by name for approved tool execution.
     * Subclasses must implement this to provide tools when executing approved requests.
     */
    context(serverRuntime: ServerRuntime) protected abstract fun findToolByName(
        auth: AuthAccess<Subject>,
        conversation: SystemChatConversation,
        toolName: String,
    ): ChatTool<Subject, *>?

    // Paths
    private val conversationPath = path.path("conversations")
    private val messagePath = path.path("messages")

    // Model Infos
    public val conversationInfo: ModelInfo<Subject, SystemChatConversation, Uuid> = database.explicitModelInfo(
        auth = authRequirement,
        serializer = SystemChatConversation.serializer(),
        idSerializer = Uuid.serializer(),
        permissions = conversationPermissions,
    )

    public val messageInfo: ModelInfo<Subject, SystemChatMessage, Uuid> = database.explicitModelInfo(
        auth = authRequirement,
        serializer = SystemChatMessage.serializer(),
        idSerializer = Uuid.serializer(),
        permissions = messagePermissions,
        postPermissionsForUser = {
            it.postCreate { message ->
                when (message.role) {
                    SystemChatMessage.Role.User -> {
                        // User message triggers response generation (unless explicitly skipped)
                        if (!message.skipAutoResponse) {
                            triggerResponseTask(TaskInput(message, auth))
                        }
                    }
                    else -> { /* No action for other message types */ }
                }
            }.interceptCreate { message ->
                // Validate message belongs to user's conversation
                val conversation = conversationInfo.table(this).get(message.conversationId)
                if (conversation?.subjectId != auth.rawId.toString())
                    throw BadRequestException("Message must belong to your conversation")
                if (message.subjectId != auth.rawId.toString())
                    throw BadRequestException("Message subjectId must match your ID")
                message
            }
        }
    )

    // Serializable wrapper for task input
    @Serializable
    public data class TaskInput(
        val message: SystemChatMessage,
        val auth: Authentication<*>
    )

    //
    // Helper methods for subclasses to use
    //

    /**
     * Get the conversation history as a list of messages.
     * Useful for building LLM context.
     */
    context(_: ServerRuntime)
    public suspend fun getConversationHistory(
        access: AuthAccess<Subject>,
        conversationId: Uuid,
    ): List<SystemChatMessage> {
        return messageInfo.table(access)
            .find(
                condition { it.conversationId eq conversationId },
                orderBy = sort { it.createdAt.ascending() }
            )
            .toList()
    }

    /**
     * Find the channel info (channel and externalIdentifier) from the most recent
     * message in this conversation that has external channel info.
     * This is used to propagate channel info to response messages.
     */
    context(_: ServerRuntime)
    protected suspend fun findChannelInfo(conversationId: Uuid): Pair<String?, String?> {
        val recentExternal = messageInfo.table()
            .find(
                condition {
                    (it.conversationId eq conversationId) and
                            (it.externalIdentifier neq null)
                },
                orderBy = sort { it.createdAt.descending() }
            )
            .firstOrNull()
        return recentExternal?.channel to recentExternal?.externalIdentifier
    }

    /**
     * Result of processing a tool call.
     */
    public sealed class ToolCallResult {
        /**
         * Tool was executed (either auto-approved or already approved).
         * Contains the result string from the tool.
         */
        public data class Executed(val result: String) : ToolCallResult()

        /**
         * Tool requires approval. Response generation should stop.
         * Will resume after user approves/rejects via the approval endpoint.
         */
        public data object WaitingForApproval : ToolCallResult()

        /**
         * Tool processing failed (parse error, tool not found, etc.)
         * Contains the error message.
         */
        public data class Error(val error: String) : ToolCallResult()
    }

    /**
     * Process a tool call from the LLM.
     *
     * This method:
     * 1. Finds the tool by name
     * 2. Parses arguments
     * 3. Checks if approval is needed (via [ChatTool.checkApproval])
     * 4. Either executes immediately or creates a pending approval request
     *
     * @param access The authenticated user's access
     * @param conversation The conversation context
     * @param tool The ChatTool to execute
     * @param argumentsJson The JSON-encoded arguments from the LLM
     * @return [ToolCallResult] indicating what happened
     */
    context(_: ServerRuntime)
    public suspend fun <T> processToolCall(
        access: AuthAccess<Subject>,
        conversation: SystemChatConversation,
        tool: ChatTool<Subject, T>,
        argumentsJson: String,
    ): ToolCallResult {
        // Helper to create and insert tool request messages
        suspend fun insertToolMessage(
            content: String,
            requiresApproval: Boolean,
            approvalReason: String? = null,
            result: String? = null,
            error: String? = null,
        ): SystemChatMessage {
            val message = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = conversation.subjectId,
                role = SystemChatMessage.Role.ToolRequest,
                content = content,
                createdAt = now(),
                tool = ToolRequestData(
                    toolName = tool.name,
                    arguments = argumentsJson,
                    requiresApproval = requiresApproval,
                    approvalReason = approvalReason,
                    result = result,
                    error = error,
                )
            )
            messageInfo.table().insertOne(message)
            return message
        }

        // Parse arguments
        val args = try {
            parseToolArg(tool, argumentsJson)
        } catch (e: Exception) {
            val errorMsg = "Invalid arguments: ${e.message}"
            insertToolMessage(
                content = "Failed to parse arguments",
                requiresApproval = false,
                error = errorMsg,
            )
            return ToolCallResult.Error(errorMsg)
        }

        // Check approval requirement
        val authorizations = conversation.toolAuthorizations.toSet()
        val approvalResult = with(serverRuntime) {
            tool.checkApproval(access, args, authorizations)
        }

        return when (approvalResult) {
            is ApprovalRequirement.AutoApproved -> {
                val description = tool.describeCall(args)
                val result = try {
                    with(serverRuntime) { tool.execute(access, args) }
                } catch (e: Exception) {
                    "Error: ${e.message ?: "Unknown error"}"
                }
                insertToolMessage(
                    content = description,
                    requiresApproval = false,
                    result = result,
                )
                ToolCallResult.Executed(result)
            }

            is ApprovalRequirement.RequiresApproval -> {
                insertToolMessage(
                    content = approvalResult.description,
                    requiresApproval = true,
                    approvalReason = approvalResult.reason,
                )
                ToolCallResult.WaitingForApproval
            }
        }
    }

    /**
     * Find the most recent pending tool request in a conversation.
     */
    context(_: ServerRuntime)
    private suspend fun findPendingToolRequest(
        access: AuthAccess<Subject>,
        conversationId: Uuid,
    ): SystemChatMessage? {
        return messageInfo.table(access)
            .find(
                condition {
                    (it.conversationId eq conversationId) and
                    (it.role eq SystemChatMessage.Role.ToolRequest)
                },
                orderBy = sort { it.createdAt.descending() }
            )
            .toList()
            .firstOrNull { msg ->
                msg.tool?.requiresApproval == true && msg.tool?.approval == null
            }
    }

    //
    // Internal lock management
    //

    context(_: ServerRuntime)
    private suspend fun tryAcquireProcessingLock(conversationId: Uuid): String? {
        val lockId = Uuid.random().toString()
        val currentTime = now()

        val lockAcquired = conversationInfo.table().updateOne(
            condition {
                (it._id eq conversationId) and (
                    (it.processingLock eq null) or
                    (it.processingLock.notNull.acquiredAt lt (currentTime - responseLockTimeout))
                )
            },
            modification {
                it.processingLock assign ProcessingLock(
                    holderId = lockId,
                    acquiredAt = currentTime
                )
            }
        ).new != null

        return if (lockAcquired) lockId else null
    }

    context(_: ServerRuntime)
    private suspend fun releaseProcessingLock(conversationId: Uuid, lockId: String) {
        conversationInfo.table().updateOne(
            condition {
                (it._id eq conversationId) and
                (it.processingLock.notNull.holderId eq lockId)
            },
            modification {
                it.processingLock assign null
                it.updatedAt assign now()
            }
        )
    }

    public class StopProcessing(message: String): Throwable(message)

    context(_: ServerRuntime)
    private suspend fun runRespondWithLock(
        access: AuthAccess<Subject>,
        conversation: SystemChatConversation,
    ) {
        if (!conversation.autoProcess) return

        val lockId = tryAcquireProcessingLock(conversation._id) ?: return

        try {
            with(serverRuntime) {
                respond(access, conversation)
            }
        } catch(_: StopProcessing) {
            // Cool.
        } catch (e: Exception) {
            e.printStackTrace()
            // Get channel info to propagate to error message
            val (channel, externalIdentifier) = findChannelInfo(conversation._id)
            val message = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = conversation.subjectId,
                role = SystemChatMessage.Role.Error,
                channel = channel,
                externalIdentifier = externalIdentifier,
                content = e.message ?: "Unknown error",
                createdAt = now()
            )
            messageInfo.table().insertOne(message)
        } finally {
            releaseProcessingLock(conversation._id, lockId)
        }
    }

    context(_: ServerRuntime)
    public fun <T> parseToolArg(
        tool: ChatTool<Subject, T>,
        arguments: String,
    ): T {
        val ser = tool.koogSerializer(serverRuntime.externalSerialization.json.serializersModule)
        return tool.koogArgParse(Json.decodeFromString(ser, arguments))
    }

    /**
     * Execute a tool that was approved via the approval endpoint.
     */
    context(_: ServerRuntime)
    private suspend fun executeApprovedTool(
        access: AuthAccess<Subject>,
        conversation: SystemChatConversation,
        message: SystemChatMessage,
        tool: ChatTool<Subject, *>,
    ) {
        val toolData = message.tool ?: return

        @Suppress("UNCHECKED_CAST")
        tool as ChatTool<Subject, Any>

        val args = try {
            parseToolArg(tool, toolData.arguments)
        } catch (e: Exception) {
            e.printStackTrace()
            messageInfo.table().updateOneById(
                message._id,
                modification { it.tool.notNull.error assign "Failed to parse arguments: ${e.message}" }
            )
            return
        }

        // Acquire tool execution lock
        val lockId = Uuid.random().toString()
        val currentTime = now()

        val lockAcquired = messageInfo.table().updateOne(
            condition {
                (it._id eq message._id) and
                (it.tool.notNull.result eq null) and
                (it.tool.notNull.error eq null) and (
                    (it.tool.notNull.executionLock eq null) or
                    (it.tool.notNull.executionLock.notNull.acquiredAt lt (currentTime - toolLockTimeout))
                )
            },
            modification {
                it.tool.notNull.executionLock assign ToolExecutionLock(lockId, currentTime)
            }
        ).new != null

        if (!lockAcquired) return

        val (result, error) = try {
            with(serverRuntime) { tool.execute(access, args) } to null
        } catch (e: Exception) {
            null to (e.message ?: "Unknown error")
        }

        messageInfo.table().updateOne(
            condition {
                (it._id eq message._id) and
                (it.tool.notNull.executionLock.notNull.holderId eq lockId)
            },
            modification {
                if (result != null) it.tool.notNull.result assign result
                if (error != null) it.tool.notNull.error assign error
                it.tool.notNull.executionLock assign null
            }
        )
    }

    //
    // Public API for external channels
    //

    /**
     * Trigger automatic response generation for a user message.
     *
     * External channels (SMS, email, etc.) should call this after inserting a user message
     * to trigger the LLM to generate a response. Voice/phone channels should NOT call this
     * since they handle responses directly through the voice agent.
     *
     * This checks [SystemChatMessage.skipAutoResponse] - if true, no response is triggered.
     *
     * @param access The authenticated user's access
     * @param message The user message to respond to (must have role == User)
     */
    context(_: ServerRuntime)
    public suspend fun triggerAutoResponse(
        access: AuthAccess<Subject>,
        message: SystemChatMessage,
    ) {
        if (message.role != SystemChatMessage.Role.User) return
        if (message.skipAutoResponse) return
        triggerResponseTask(TaskInput(message, access.auth))
    }

    /**
     * Trigger tool execution after a tool request has been approved.
     *
     * External channels should call this after recording a tool approval with approved=true.
     *
     * @param access The authenticated user's access
     * @param message The tool request message (must have role == ToolRequest and approval.approved == true)
     */
    context(_: ServerRuntime)
    public suspend fun triggerToolExecution(
        access: AuthAccess<Subject>,
        message: SystemChatMessage,
    ) {
        if (message.role != SystemChatMessage.Role.ToolRequest) return
        if (message.tool?.approval?.approved != true) return
        executeToolTask(TaskInput(message, access.auth))
    }

    /**
     * Trigger response continuation after a tool request has been rejected.
     *
     * External channels should call this after recording a tool approval with approved=false.
     * The LLM will continue generating a response, likely acknowledging the rejection.
     *
     * @param access The authenticated user's access
     * @param message The tool request message (must have role == ToolRequest and approval.approved == false)
     */
    context(_: ServerRuntime)
    public suspend fun triggerContinueResponse(
        access: AuthAccess<Subject>,
        message: SystemChatMessage,
    ) {
        if (message.role != SystemChatMessage.Role.ToolRequest) return
        if (message.tool?.approval == null) return
        continueResponseTask(TaskInput(message, access.auth))
    }

    //
    // Tasks
    //

    private val triggerResponseTask: Task<TaskInput> =
        path.path("trigger-response") bind Task<TaskInput> { input ->
            @Suppress("UNCHECKED_CAST")
            val access = AuthAccess(input.auth as Authentication<Subject>)
            val conversation = conversationInfo.table(access).get(input.message.conversationId) ?: return@Task

            runRespondWithLock(access, conversation)
        }

    private val continueResponseTask: Task<TaskInput> =
        path.path("continue-response") bind Task<TaskInput> { input ->
            @Suppress("UNCHECKED_CAST")
            val access = AuthAccess(input.auth as Authentication<Subject>)
            val conversation = conversationInfo.table(access).get(input.message.conversationId) ?: return@Task

            runRespondWithLock(access, conversation)
        }

    private val executeToolTask: Task<TaskInput> =
        path.path("execute-tool") bind Task<TaskInput> { input ->
            val message = input.message
            val toolData = message.tool ?: return@Task

            // Validate state
            if (message.role != SystemChatMessage.Role.ToolRequest) return@Task
            if (toolData.result != null || toolData.error != null) return@Task
            if (toolData.requiresApproval && toolData.approval?.approved != true) return@Task

            @Suppress("UNCHECKED_CAST")
            val access = AuthAccess(input.auth as Authentication<Subject>)
            val conversation = conversationInfo.table(access).get(message.conversationId) ?: return@Task

            val tool = with(serverRuntime) {
                findToolByName(access, conversation, toolData.toolName)
            }
            if (tool == null) {
                messageInfo.table().updateOneById(
                    message._id,
                    modification { it.tool.notNull.error assign "Tool no longer available" }
                )
            } else {
                executeApprovedTool(access, conversation, message, tool)
            }

            // Continue response generation
            continueResponseTask(TaskInput(message, input.auth))
        }

    //
    // REST Endpoints
    //

    public val conversations: ModelRestEndpoints<Subject, SystemChatConversation, Uuid> =
        conversationPath module ModelRestEndpoints(conversationInfo).withSdkInfo("SystemChatConversationsApi", "conversations")

    public inner class MessagesEndpoints: ServerBuilder() {
        public val info: ModelInfo<Subject, SystemChatMessage, Uuid> get() = messageInfo

        public val messages: ModelRestEndpoints<Subject, SystemChatMessage, Uuid> =
            path include ModelRestEndpoints(messageInfo)

        public val messageUpdates: ModelRestUpdatesWebsocket<Subject, SystemChatMessage, Uuid> =
            path include ModelRestUpdatesWebsocket(messageInfo)

    }

    public val messages: MessagesEndpoints =
        messagePath module MessagesEndpoints().withSdkInfo("SystemChatMessagesApi", "messages")

    public val approveToolRequest: ApiHttpHandler<PathSpec1<Uuid>, Subject, ToolApprovalRequest, SystemChatMessage> =
        messagePath.arg<Uuid>("id").path("approve").post bind ApiHttpHandler(
            summary = "Approve Tool Request",
            description = "Approves or rejects a pending tool request. Records who approved and when.",
            auth = authRequirement,
            successCode = HttpStatus.OK,
            errorCases = listOf(
                LSError(HttpStatus.NotFound.code, "not-found", "Message not found"),
                LSError(HttpStatus.BadRequest.code, "not-tool-request", "Message is not a tool request"),
                LSError(HttpStatus.BadRequest.code, "no-approval-required", "Tool request does not require approval"),
                LSError(HttpStatus.BadRequest.code, "already-processed", "Tool request already approved or rejected"),
            ),
            implementation = { input: ToolApprovalRequest ->
                approve(this, request.arg1, input)
            }
        )

    public val authorizeTool: ApiHttpHandler<PathSpec1<Uuid>, Subject, AuthorizeToolRequest, SystemChatConversation> =
        conversationPath.arg<Uuid>("id").path("authorize-tool").post bind ApiHttpHandler(
            summary = "Authorize Tool",
            description = "Pre-authorize a tool for this conversation. Future calls won't require individual approval.",
            auth = authRequirement,
            successCode = HttpStatus.OK,
            errorCases = listOf(
                LSError(HttpStatus.NotFound.code, "not-found", "Conversation not found"),
                LSError(HttpStatus.Forbidden.code, "not-owner", "Not your conversation"),
            ),
            implementation = { input: AuthorizeToolRequest ->
                val conversationId = route.arg1
                val conversation = conversationInfo.table(this).get(conversationId)
                    ?: throw NotFoundException("Conversation not found")

                if (conversation.subjectId != auth.rawId.toString()) {
                    throw UnauthorizedException("Not your conversation")
                }

                val currentTime = now()
                val authorization = ToolAuthorization(
                    toolName = input.toolName,
                    authorizedBy = auth.rawId.toString(),
                    authorizedAt = currentTime,
                    expiresAt = input.durationSeconds?.let { currentTime + it.seconds }
                )

                conversationInfo.table(this).updateOneById(
                    conversationId,
                    modification {
                        it.toolAuthorizations += authorization
                    }
                ).new ?: throw NotFoundException("Failed to update conversation")
            }
        )

    context(runtime: ServerRuntime)
    private suspend fun approve(access: AuthAccess<Subject>, messageId: Uuid, input: ToolApprovalRequest): SystemChatMessage {
        val message = messageInfo.table(access).get(messageId)
            ?: throw NotFoundException("Message not found")
        val allowedToChatAsUser = messageInfo.permissions(access).create(SystemChatMessage(
            conversationId = message.conversationId,
            subjectId = message.subjectId,
            role = SystemChatMessage.Role.User,
            content = "",
            createdAt = now()
        ))
        if(!allowedToChatAsUser) throw ForbiddenException(detail = "not-found", message = "Item not found")

        if (message.role != SystemChatMessage.Role.ToolRequest)
            throw BadRequestException(detail = "not-tool-request", message = "Message is not a tool request")

        val tool = message.tool
            ?: throw BadRequestException(detail = "not-tool-request", message = "Message has no tool data")

        if (!tool.requiresApproval)
            throw BadRequestException(detail = "no-approval-required", message = "Tool request does not require approval")

        if (tool.approval != null)
            throw BadRequestException(detail = "already-processed", message = "Tool request already approved or rejected")

        val approval = ToolApproval(
            approved = input.approved,
            approvedBy = access.auth.rawId,
            approvedAt = now(),
            reason = input.reason
        )

        val updated = messageInfo.table().updateOneById(
            messageId,
            modification { it.tool.notNull.approval assign approval }
        ).new ?: throw NotFoundException("Failed to update message")

        if (input.approved) {
            executeToolTask(TaskInput(updated, access.auth))
        } else {
            continueResponseTask(TaskInput(updated, access.auth))
        }

        return updated
    }

    //
    // Simple Chat WebSocket
    //

    @Serializable
    public data class SimpleChatWebsocketStorage(
        val conversationId: Uuid,
        val subjectId: String,
        val channel: String? = null,
    )

    private val simpleChatMessageTopic: WebSocketTopic<PathSpec1<Uuid>, SystemChatMessage> =
        path.path("simple-chat").arg<Uuid>("conversationId").topic(SystemChatMessage.serializer())

    public val simpleChat: WebSocketHandler<PathSpec0, SimpleChatWebsocketStorage> =
        path.path("simple-chat") bind WebSocketHandler(
            storageSerializer = SimpleChatWebsocketStorage.serializer(),

            willConnect = { request ->
                val authResult = request.auth(authRequirement)
                    ?: throw UnauthorizedException("Authentication required")

                val access = AuthAccess(authResult)
                val subjectId = authResult.rawId.toString()

                val existingConversationId = request.queryParameters["conversationId"]
                    ?.let { runCatching { Uuid.parse(it) }.getOrNull() }

                val channel = request.queryParameters["channel"]

                val conversation = if (existingConversationId != null) {
                    val existing = conversationInfo.table(access).get(existingConversationId)
                        ?: throw NotFoundException("Conversation not found")
                    if (existing.subjectId != subjectId) {
                        throw UnauthorizedException("Not your conversation")
                    }
                    existing
                } else {
                    val newConversation = SystemChatConversation(
                        subjectId = subjectId,
                        createdAt = now()
                    )
                    conversationInfo.table(access).insertOne(newConversation)
                        ?: throw BadRequestException("Failed to create conversation")
                }

                SimpleChatWebsocketStorage(
                    conversationId = conversation._id,
                    subjectId = subjectId,
                    channel = channel,
                )
            },

            didConnect = {
                subscribe(simpleChatMessageTopic, currentState.conversationId)
            },

            messageFromClient = { frame ->
                val text = when (frame) {
                    is WebSocketFrame.Text -> frame.content
                    is WebSocketFrame.Binary -> return@WebSocketHandler
                }

                if (text.isBlank()) return@WebSocketHandler

                val authResult = request.auth(authRequirement)
                    ?: return@WebSocketHandler

                val access = AuthAccess(authResult)

                // Check for tool approval/rejection commands
                val trimmedText = text.trim()
                val isApproval = trimmedText.equals("YES", ignoreCase = true)
                val isRejection = trimmedText.equals("NO", ignoreCase = true) || trimmedText.lowercase().startsWith("no:")

                if (isApproval || isRejection) {
                    val pendingToolRequest = findPendingToolRequest(access, currentState.conversationId)
                    if (pendingToolRequest != null) {
                        val (approved, reason) = if (isApproval) {
                            true to null
                        } else {
                            val rejectionReason = if (trimmedText.lowercase().startsWith("no:")) {
                                trimmedText.substringAfter(":").trim()
                            } else null
                            false to rejectionReason
                        }
                        approve(AuthAccess(authResult), pendingToolRequest._id, ToolApprovalRequest(approved, reason))
                        return@WebSocketHandler
                    }
                }

                // Regular user message
                val message = SystemChatMessage(
                    conversationId = currentState.conversationId,
                    subjectId = currentState.subjectId,
                    role = SystemChatMessage.Role.User,
                    channel = currentState.channel,
                    content = text,
                    createdAt = now()
                )


                messageInfo.table(access).insertOne(message)
            },

            topicHandlers = {
                simpleChatMessageTopic bind { subscriptionMessage ->
                    val msg = subscriptionMessage.value
                    if (msg.channel != null && msg.channel != currentState.channel) {
                        return@bind // Skip messages from other channels
                    }

                    when (msg.role) {
                        SystemChatMessage.Role.Assistant -> {
                            send(WebSocketFrame.Text(msg.content))
                        }
                        SystemChatMessage.Role.Error -> {
                            send(WebSocketFrame.Text("[Error] ${msg.content}"))
                        }
                        SystemChatMessage.Role.ToolRequest -> {
                            val tool = msg.tool ?: return@bind
                            if (tool.requiresApproval && tool.approval == null) {
                                // Format the tool request for the user
                                // Use message content for description, falling back to tool info
                                val toolDescription = msg.content.ifBlank {
                                    tool.approvalReason ?: "Execute ${tool.toolName}"
                                }
                                send(WebSocketFrame.Text("[Tool Request] $toolDescription\nReply YES to approve or NO to reject."))
                            }
                        }
                        else -> { /* Ignore other message types */ }
                    }
                }
            },

            disconnect = { _ -> }
        )

    /**
     * Determine if a message should be broadcast to websocket clients.
     */
    private fun shouldBroadcastMessage(message: SystemChatMessage): Boolean = when (message.role) {
        SystemChatMessage.Role.Assistant,
        SystemChatMessage.Role.Error -> true
        SystemChatMessage.Role.ToolRequest ->
            message.tool?.requiresApproval == true && message.tool?.approval == null
        else -> false
    }

    init {
        messageInfo.registerChangeListener { changes ->
            changes.changes.forEach { change ->
                change.new?.let { message ->
                    if (shouldBroadcastMessage(message)) {
                        simpleChatMessageTopic.send(message.conversationId, message)
                    }
                }
            }
        }
    }
}
