package com.lightningkite.lightningserver.ai

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import com.lightningkite.PhoneNumber
import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.UnauthorizedException
import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.settings.invoke
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.fullUrl
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.location
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.typed.AuthAccess
import com.lightningkite.lightningserver.typed.auth
import com.lightningkite.lightningserver.typed.sdk.module
import com.lightningkite.lightningserver.websockets.WebSocketConnection
import com.lightningkite.lightningserver.websockets.WebSocketFrame
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.send
import com.lightningkite.services.data.WebsocketAdapter
import com.lightningkite.services.database.*
import com.lightningkite.services.phonecall.AudioStreamEvent
import com.lightningkite.services.phonecall.CallInstructions
import com.lightningkite.services.phonecall.PhoneCallService
import com.lightningkite.services.pubsub.PubSub
import com.lightningkite.services.voiceagent.*
import com.lightningkite.services.voiceagent.phonecall.PubSubVoiceAgentHandler
import com.lightningkite.services.voiceagent.phonecall.TranscriptRole
import com.lightningkite.services.voiceagent.phonecall.createVoiceAgentStreamInstructions
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger("VoiceChannelSupport")

/**
 * Adds voice and phone call channel support to an existing [SystemChatEndpoints] instance.
 *
 * This class uses composition - it references the chat endpoints and provides
 * WebSocket endpoints for real-time voice conversations with the AI agent.
 *
 * ## Features
 *
 * - **Direct Voice WebSocket**: Clients can connect directly with audio streaming
 * - **Phone Call Integration**: Incoming calls via Twilio connect to voice agent
 * - **Transcript Storage**: Voice transcriptions are stored as SystemChatMessage entries
 * - **Tool Calling**: Voice agent can use the same tools as text chat
 *
 * ## Security Considerations
 *
 * **Phone call authentication is based solely on caller ID (phone number possession).**
 *
 * For incoming phone calls, this class creates synthetic [Authentication] instances based
 * on the caller's phone number. This authentication model relies entirely on the security
 * guarantees provided by your phone service provider (e.g., Twilio).
 *
 * **Important security implications:**
 * - Caller ID can potentially be spoofed, though modern carriers implement STIR/SHAKEN
 *   attestation to reduce this risk
 * - The synthetic authentication has no expiration
 * - Direct voice WebSocket connections use standard authentication (not affected by this)
 *
 * **Recommendations:**
 * - Use a reputable telephony provider that supports STIR/SHAKEN caller ID verification
 * - Consider implementing additional voice-based verification for sensitive operations
 * - Do not expose highly destructive tools through phone channels without additional safeguards
 * - For maximum security, use the direct voice WebSocket which uses standard authentication
 *
 * ## Usage
 *
 * ```kotlin
 * object MyChatBot : LLMChatEndpoints<User>(...) {
 *     override val tools = mapOf(...)
 * }
 *
 * object MyChatBotVoice : VoiceChannelSupport<User, Uuid>(
 *     chatEndpoints = MyChatBot,
 *     authRequirement = Auth.required,
 *     principalType = User,
 *     voiceAgent = voiceAgentSetting,
 *     pubsub = pubsubSetting,
 *     phoneCall = phoneCallSetting, // Optional
 *     voiceInstructions = "You are a helpful assistant...",
 *     resolveTools = { auth, conversation ->
 *         listOf(listPostsTool, createPostTool)
 *     },
 *     resolveSubjectByPhone = { phone ->
 *         userTable().findOne(condition { it.phone eq phone.raw })
 *     },
 * )
 * ```
 *
 * @param Subject The authenticated user type
 * @param ID The ID type for the subject
 * @param chatEndpoints The SystemChatEndpoints instance to add voice support to
 * @param authRequirement Authentication requirement for the voice WebSocket
 * @param principalType The PrincipalType for creating Authentication instances
 * @param voiceAgent Voice agent service settings
 * @param pubsub PubSub service settings (required for Lambda-compatible phone call handling)
 * @param phoneCall Optional phone call service settings for phone integration
 * @param voiceInstructions System instructions for the voice agent
 * @param voice Voice configuration (name, language, speed)
 * @param turnDetection How to detect when user has finished speaking
 * @param resolveTools Function to get available tools for a conversation
 * @param resolveSubjectByPhone Function to resolve a subject from phone number (required for phone support)
 */
public class VoiceChannelSupport<Subject : HasId<ID>, ID : Comparable<ID>>(
    private val chatEndpoints: SystemChatEndpoints<Subject>,
    private val authRequirement: AuthRequirement<Subject>,
    private val principalType: PrincipalType<Subject, ID>,

    // Voice agent settings
    private val voiceAgent: ServerSetting<VoiceAgentService.Settings, VoiceAgentService>,
    private val pubsub: ServerSetting<PubSub.Settings, PubSub>,

    // Optional phone call integration
    private val phoneCall: ServerSetting<PhoneCallService.Settings, PhoneCallService>? = null,

    // Voice session configuration
    private val voiceInstructions: String,
    private val voice: VoiceConfig = VoiceConfig(name = "alloy"),
    private val turnDetection: TurnDetection = TurnDetection.ServerVAD(),

    // Tool resolution
    private val resolveTools: suspend context(ServerRuntime) (AuthAccess<Subject>, SystemChatConversation) -> List<ChatTool<Subject, *>>,

    // Subject resolution for phone calls
    private val resolveSubjectByPhone: (suspend context(ServerRuntime) (PhoneNumber) -> Subject?)? = null,

    // Optional greeting to speak when session starts
    private val greeting: String? = "Hello! How can I help you today?",

    // Number of recent messages to inject as context (0 to disable history injection)
    private val historyMessageLimit: Int = 20,

    // Optional callback for usage tracking
    private val onUsage: (suspend context(ServerRuntime) (conversationId: Uuid, channel: String, usage: UsageStats) -> Unit)? = null,
) : ServerBuilder() {

    //
    // Voice WebSocket (direct client connection)
    //

    @Serializable
    public data class VoiceWebSocketState(
        val conversationId: Uuid,
        val subjectId: String,
        val connectionId: String = Uuid.random().toString(),
    )

    /**
     * WebSocket endpoint for direct voice connection.
     *
     * Clients connect with:
     * - Authentication header/token
     * - Optional `conversationId` query parameter to continue existing conversation
     *
     * Audio format: PCM16 24kHz mono, base64 encoded in JSON messages
     */
    @OptIn(ExperimentalEncodingApi::class)
    public val voiceWebSocket: WebSocketHandler<*, VoiceWebSocketState> =
        path.path("voice") bind WebSocketHandler(
            storageSerializer = VoiceWebSocketState.serializer(),

            willConnect = { request ->
                val auth = request.auth(authRequirement)
                    ?: throw UnauthorizedException("Authentication required")

                val access = AuthAccess(auth)
                val subjectId = auth.rawId.toString()

                val conversationId = request.queryParameters["conversationId"]
                    ?.let { runCatching { Uuid.parse(it) }.getOrNull() }

                val conversation = if (conversationId != null) {
                    chatEndpoints.conversationInfo.table(access).get(conversationId)
                        ?: throw NotFoundException("Conversation not found")
                } else {
                    chatEndpoints.conversationInfo.table(access).insertOne(
                        SystemChatConversation(subjectId = subjectId, createdAt = now())
                    ) ?: throw BadRequestException("Failed to create conversation")
                }

                if (conversation.subjectId != subjectId) {
                    throw UnauthorizedException("Not your conversation")
                }

                VoiceWebSocketState(conversation._id, subjectId)
            },

            didConnect = {
                val connection = this
                val connectionId = connection.currentState.connectionId
                val auth = connection.request.auth(authRequirement)
                    ?: return@WebSocketHandler

                val access = AuthAccess(auth)
                val conversation = chatEndpoints.conversationInfo.table(access).get(connection.currentState.conversationId)
                    ?: return@WebSocketHandler

                // Get tools for this conversation
                val tools = resolveTools(connection, access, conversation)
                val serializableTools = tools.map { it.toSerializableDescriptor(connection.externalSerialization.json.serializersModule) }

                // Build instructions with conversation history context
                val historyContext = if (historyMessageLimit > 0) {
                    with(connection) { loadConversationHistory(conversation._id, historyMessageLimit) }
                } else ""

                val fullInstructions = buildString {
                    append(voiceInstructions)
                    if (historyContext.isNotBlank()) {
                        append("\n\n## Previous Conversation Context\n")
                        append("The user has had previous interactions. Here is the recent history:\n\n")
                        append(historyContext)
                        append("\n\nUse this context to provide continuity, but don't repeat information unless asked.")
                    }
                    // Add greeting instruction if configured
                    greeting?.let { greetingText ->
                        append("\n\n## Initial Greeting\n")
                        append("When the session starts, greet the user with: \"$greetingText\"")
                    }
                }

                // Build session config
                val sessionConfig = VoiceAgentSessionConfig(
                    instructions = fullInstructions,
                    voice = voice,
                    turnDetection = turnDetection,
                    tools = serializableTools,
                    inputTranscription = TranscriptionConfig(), // Enable transcription for storage
                )

                // Create scope first, then session - ensures cleanup on any failure
                val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                var session: VoiceAgentSession? = null

                try {
                    session = voiceAgent().createSession(sessionConfig)

                    // Store session info BEFORE starting event collection
                    val sessionInfo = VoiceSessionInfo(session, scope, access, conversation, tools, CHANNEL_VOICE)
                    voiceSessions[connectionId] = sessionInfo

                    // Start handling events in background
                    scope.launch {
                        try {
                            session.events.collect { event ->
                                handleVoiceEvent(connection, event, session, access, conversation, tools, CHANNEL_VOICE) { text ->
                                    connection.send(WebSocketFrame.Text(text))
                                }
                            }
                        } catch (e: CancellationException) {
                            // Normal cancellation on disconnect
                            throw e
                        } catch (e: Exception) {
                            logger.error(e) { "Error in voice event collection for $connectionId" }
                        }
                    }

                    logger.info { "Voice WebSocket connected: $connectionId" }

                } catch (e: Exception) {
                    // Cleanup on failure
                    logger.error(e) { "Failed to initialize voice session for $connectionId" }
                    voiceSessions.remove(connectionId)
                    session?.let {
                        scope.launch { runCatching { it.close() } }
                    }
                    scope.cancel()
                    throw e
                }
            },

            messageFromClient = { frame ->
                val text = when (frame) {
                    is WebSocketFrame.Text -> frame.content
                    is WebSocketFrame.Binary -> return@WebSocketHandler
                }

                val sessionInfo = voiceSessions[currentState.connectionId] ?: return@WebSocketHandler

                // Parse the message - expect JSON with audio data
                try {
                    val message = kotlinx.serialization.json.Json.decodeFromString<VoiceClientMessage>(text)
                    when (message) {
                        is VoiceClientMessage.Audio -> {
                            val audioBytes = Base64.decode(message.data)
                            sessionInfo.session.sendAudio(audioBytes)
                        }
                        is VoiceClientMessage.Commit -> {
                            sessionInfo.session.commitAudio()
                        }
                        is VoiceClientMessage.Cancel -> {
                            sessionInfo.session.cancelResponse()
                        }
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Error processing voice message" }
                }
            },

            disconnect = {
                val connectionId = currentState.connectionId
                val sessionInfo = voiceSessions.remove(connectionId)
                if (sessionInfo != null) {
                    // Close session first, then cancel scope
                    try {
                        withContext(NonCancellable) {
                            runCatching { sessionInfo.session.close() }
                                .onFailure { logger.warn(it) { "Error closing voice session $connectionId" } }
                        }
                    } finally {
                        sessionInfo.scope.cancel()
                    }
                }
                logger.info { "Voice WebSocket disconnected: $connectionId" }
            }
        )

    /**
     * Track active voice sessions.
     *
     * NOTE: This in-memory map requires sticky WebSocket connections.
     * For serverless (Lambda), use phone integration with PubSubVoiceAgentHandler instead.
     */
    private data class VoiceSessionInfo<Subject : HasId<*>>(
        val session: VoiceAgentSession,
        val scope: CoroutineScope,
        val access: AuthAccess<Subject>,
        val conversation: SystemChatConversation,
        val tools: List<ChatTool<Subject, *>>,
        val channel: String,
    )

    private val voiceSessions = ConcurrentHashMap<String, VoiceSessionInfo<Subject>>()

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun handleVoiceEvent(
        runtime: ServerRuntime,
        event: VoiceAgentEvent,
        session: VoiceAgentSession,
        access: AuthAccess<Subject>,
        conversation: SystemChatConversation,
        tools: List<ChatTool<Subject, *>>,
        channel: String,
        sendFrame: suspend (String) -> Unit,
    ) {
        suspend fun saveUserMessage(text: String) {
            val message = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = conversation.subjectId,
                role = SystemChatMessage.Role.User,
                channel = channel,
                content = text,
                createdAt = with(runtime) { now() },
                skipAutoResponse = true, // Voice agent handles responses directly
            )
            with(runtime) { chatEndpoints.messageInfo.table().insertOne(message) }
        }

        suspend fun saveAssistantMessage(text: String) {
            val message = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = conversation.subjectId,
                role = SystemChatMessage.Role.Assistant,
                channel = channel,
                content = text,
                createdAt = with(runtime) { now() },
                skipAutoResponse = true,
            )
            with(runtime) { chatEndpoints.messageInfo.table().insertOne(message) }
        }

        suspend fun saveToolCallMessage(toolName: String, arguments: String, result: String?, error: String?) {
            val message = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = conversation.subjectId,
                role = SystemChatMessage.Role.ToolRequest,
                channel = channel,
                content = "Called $toolName",
                createdAt = with(runtime) { now() },
                skipAutoResponse = true,
                tool = ToolRequestData(
                    toolName = toolName,
                    arguments = arguments,
                    requiresApproval = false, // Already executed by voice agent
                    result = result,
                    error = error,
                ),
            )
            with(runtime) { chatEndpoints.messageInfo.table().insertOne(message) }
        }

        when (event) {
            is VoiceAgentEvent.SessionCreated -> {
                logger.info { "Voice session created: ${event.sessionId}" }
                sendFrame(kotlinx.serialization.json.Json.encodeToString(
                    VoiceServerMessage.serializer(),
                    VoiceServerMessage.SessionReady(event.sessionId)
                ))
            }

            is VoiceAgentEvent.AudioDelta -> {
                // Forward audio to client
                sendFrame(kotlinx.serialization.json.Json.encodeToString(
                    VoiceServerMessage.serializer(),
                    VoiceServerMessage.Audio(event.delta)
                ))
            }

            is VoiceAgentEvent.InputTranscription -> {
                if (event.isFinal && event.text.isNotBlank()) {
                    logger.info { "User said: ${event.text}" }
                    saveUserMessage(event.text)
                    sendFrame(kotlinx.serialization.json.Json.encodeToString(
                        VoiceServerMessage.serializer(),
                        VoiceServerMessage.UserTranscript(event.text)
                    ))
                }
            }

            is VoiceAgentEvent.TextDone -> {
                if (event.text.isNotBlank()) {
                    logger.info { "Agent said: ${event.text}" }
                    saveAssistantMessage(event.text)
                    sendFrame(kotlinx.serialization.json.Json.encodeToString(
                        VoiceServerMessage.serializer(),
                        VoiceServerMessage.AgentTranscript(event.text)
                    ))
                }
            }

            is VoiceAgentEvent.ToolCallDone -> {
                logger.info { "Tool call: ${event.toolName}(${event.arguments})" }
                val result = executeVoiceTool(runtime, access, conversation, tools, event.toolName, event.arguments)
                logger.info { "Tool result: $result" }

                // Save tool call as a message for conversation history
                val isError = result.contains("\"error\"")
                saveToolCallMessage(
                    toolName = event.toolName,
                    arguments = event.arguments,
                    result = if (!isError) result else null,
                    error = if (isError) result else null,
                )

                session.sendToolResult(event.callId, result)
            }

            is VoiceAgentEvent.SpeechStarted -> {
                sendFrame(kotlinx.serialization.json.Json.encodeToString(
                    VoiceServerMessage.serializer(),
                    VoiceServerMessage.SpeechStarted
                ))
            }

            is VoiceAgentEvent.SpeechEnded -> {
                sendFrame(kotlinx.serialization.json.Json.encodeToString(
                    VoiceServerMessage.serializer(),
                    VoiceServerMessage.SpeechEnded
                ))
            }

            is VoiceAgentEvent.ResponseDone -> {
                event.usage?.let { usage ->
                    logger.info { "Voice usage: input=${usage.inputTokens}, output=${usage.outputTokens}" }
                    // Track usage if callback is provided
                    onUsage?.invoke(runtime, conversation._id, channel, usage)
                }
            }

            is VoiceAgentEvent.Error -> {
                logger.error { "Voice agent error: ${event.code} - ${event.message}" }
                sendFrame(kotlinx.serialization.json.Json.encodeToString(
                    VoiceServerMessage.serializer(),
                    VoiceServerMessage.Error(event.code, event.message)
                ))
            }

            else -> {
                // Ignore other events
            }
        }
    }

    private suspend fun executeVoiceTool(
        runtime: ServerRuntime,
        access: AuthAccess<Subject>,
        conversation: SystemChatConversation,
        tools: List<ChatTool<Subject, *>>,
        toolName: String,
        arguments: String,
    ): String {
        val tool = tools.find { it.name == toolName }
            ?: return """{"error": "Tool '$toolName' not found"}"""

        // Check if user's last message was a verbal approval - must be exactly "Yes" (case-insensitive)
        // This strict requirement prevents accidental approvals from conversational phrases
        val lastUserMessage = with(runtime) {
            chatEndpoints.messageInfo.table()
                .find(
                    condition {
                        (it.conversationId eq conversation._id) and
                        (it.role eq SystemChatMessage.Role.User)
                    },
                    orderBy = sort { it.createdAt.descending() }
                )
                .firstOrNull()
        }

        val isVerbalApproval = lastUserMessage?.content?.let { content ->
            content.filter { it.isLetter() }.equals("yes", ignoreCase = true)
        } ?: false

        // Only check for pending approvals if the user explicitly said exactly "Yes"
        val pendingApproval = if (isVerbalApproval) {
            with(runtime) {
                chatEndpoints.messageInfo.table()
                    .find(
                        condition {
                            (it.conversationId eq conversation._id) and
                            (it.role eq SystemChatMessage.Role.ToolRequest) and
                            (it.tool.notNull.toolName eq toolName) and
                            (it.tool.notNull.requiresApproval eq true) and
                            (it.tool.notNull.approval eq null) and
                            (it.tool.notNull.result eq null) and
                            (it.tool.notNull.error eq null)
                        },
                        orderBy = sort { it.createdAt.descending() }
                    )
                    .firstOrNull()
            }
        } else null

        if (pendingApproval != null) {
            // Found a pending approval - the user said "yes", so auto-approve and execute
            logger.info { "Found pending approval for $toolName, auto-approving and executing" }

            // Update the message with approval
            val approval = ToolApproval(
                approved = true,
                approvedBy = access.auth.rawId,
                approvedAt = with(runtime) { now() },
                reason = "Voice approval"
            )

            // Execute the tool
            val result = try {
                @Suppress("UNCHECKED_CAST")
                val typedTool = tool as ChatTool<Subject, Any?>
                val args = with(runtime) { chatEndpoints.parseToolArg(typedTool, pendingApproval.tool!!.arguments) }
                with(runtime) { typedTool.execute(access, args) }
            } catch (e: Exception) {
                logger.error(e) { "Error executing approved tool $toolName" }
                // Update with error
                with(runtime) {
                    chatEndpoints.messageInfo.table().updateOneById(
                        pendingApproval._id,
                        modification {
                            it.tool.notNull.approval assign approval
                            it.tool.notNull.error assign e.message
                        }
                    )
                }
                return """{"error": "${e.message}"}"""
            }

            // Update with result
            with(runtime) {
                chatEndpoints.messageInfo.table().updateOneById(
                    pendingApproval._id,
                    modification {
                        it.tool.notNull.approval assign approval
                        it.tool.notNull.result assign result
                    }
                )
            }

            return result
        }

        // No pending approval - process normally
        return when (val result = with(runtime) { chatEndpoints.processToolCall(access, conversation, tool, arguments) }) {
            is SystemChatEndpoints.ToolCallResult.Executed -> result.result
            is SystemChatEndpoints.ToolCallResult.Error -> """{"error": "${result.error}"}"""
            is SystemChatEndpoints.ToolCallResult.WaitingForApproval -> {
                // Return a clear message that the voice agent will speak naturally
                // The agent should ask for confirmation in a conversational way
                val actionDescription = try {
                    @Suppress("UNCHECKED_CAST")
                    val typedTool = tool as ChatTool<Subject, Any?>
                    val ser = typedTool.koogSerializer(runtime.externalSerialization.json.serializersModule)
                    val args = typedTool.koogArgParse(kotlinx.serialization.json.Json.decodeFromString(ser, arguments))
                    typedTool.describeCall(args).lowercase()
                } catch (e: Exception) {
                    "perform this action"
                }
                """{"status": "pending_approval", "action": "${tool.name}", "message": "I need your permission to $actionDescription. To approve, say exactly the word 'Yes'. To decline, say 'No'."}"""
            }
        }
    }

    //
    // Phone Call Integration (optional)
    //

    /**
     * Phone audio WebSocket state - minimal since we get conversation info from the Connected event.
     */
    @Serializable
    public data class PhoneAudioState(
        val connectionId: String = Uuid.random().toString(),
        val initialized: Boolean = false,
    )

    public val phoneAudioWebSocket: WebSocketHandler<PathSpec0, PhoneAudioState>? = phoneCall?.let { phoneCallSetting ->
        val voiceSupport = this@VoiceChannelSupport
        path.path("phone").path("audio") bind WebSocketHandler(
            storageSerializer = PhoneAudioState.serializer(),

            willConnect = { request ->
                // Phone WebSocket connections come from Twilio
                // We'll get conversation info from the Connected event's customParameters
                logger.info { "Phone audio WebSocket connecting" }
                PhoneAudioState()
            },

            didConnect = {
                // Don't create the handler yet - wait for the Connected event with customParameters
                logger.info { "Phone audio WebSocket connected, waiting for Connected event" }
            },

            messageFromClient = { frame ->
                val connection = this
                val text = when (frame) {
                    is WebSocketFrame.Text -> frame.content
                    is WebSocketFrame.Binary -> return@WebSocketHandler
                }

                // Check if we already have a handler initialized
                val existingInfo = phoneHandlerStates[currentState.connectionId]
                if (existingInfo != null) {
                    // Handler exists, forward the message
                    existingInfo.handler.onMessage(existingInfo.state, text)
                    return@WebSocketHandler
                }

                // No handler yet - this should be the Connected event
                // Parse it to get customParameters
                val phoneService = phoneCallSetting()
                val audioStreamAdapter = phoneService.audioStream
                    ?: throw BadRequestException("Phone service doesn't support audio streaming")

                val event = try {
                    audioStreamAdapter.parse(WebsocketAdapter.Frame.Text(text))
                } catch (e: Exception) {
                    logger.error(e) { "Failed to parse first phone message: $text" }
                    return@WebSocketHandler
                }

                if (event !is AudioStreamEvent.Connected) {
                    logger.warn { "Expected Connected event but got ${event::class.simpleName}" }
                    return@WebSocketHandler
                }

                logger.info { "Phone Connected event: callId=${event.callId}, customParameters=${event.customParameters}" }

                // Extract conversation info from customParameters
                val conversationIdStr = event.customParameters["conversationId"]
                if (conversationIdStr == null) {
                    logger.error { "Missing conversationId in customParameters" }
                    return@WebSocketHandler
                }
                val conversationId = Uuid.parse(conversationIdStr)

                val subjectIdStr = event.customParameters["subjectId"]
                if (subjectIdStr == null) {
                    logger.error { "Missing subjectId in customParameters" }
                    return@WebSocketHandler
                }

                // Look up conversation
                val conversation = with(connection) { chatEndpoints.conversationInfo.table().get(conversationId) }
                if (conversation == null) {
                    logger.error { "Conversation not found: $conversationId" }
                    return@WebSocketHandler
                }

                // Parse the subject ID
                val subjectId = try {
                    voiceSupport.parseSubjectId(subjectIdStr)
                } catch (e: Exception) {
                    logger.error(e) { "Failed to parse subject ID: $subjectIdStr" }
                    return@WebSocketHandler
                }

                val auth = Authentication(
                    principalType = voiceSupport.principalType,
                    id = subjectId,
                    sessionId = null,
                    issuedAt = with(connection) { now() },
                    expiration = null,
                )
                val access = AuthAccess(auth)

                // Get tools
                val tools = voiceSupport.resolveTools(connection, access, conversation)
                val serializableTools = tools.map { it.toSerializableDescriptor(connection.externalSerialization.json.serializersModule) }

                // Build instructions with conversation history context
                val historyContext = if (voiceSupport.historyMessageLimit > 0) {
                    with(connection) { voiceSupport.loadConversationHistory(conversation._id, voiceSupport.historyMessageLimit) }
                } else ""

                val fullInstructions = buildString {
                    append(voiceSupport.voiceInstructions)
                    if (historyContext.isNotBlank()) {
                        append("\n\n## Previous Conversation Context\n")
                        append("The user has had previous interactions. Here is the recent history:\n\n")
                        append(historyContext)
                        append("\n\nUse this context to provide continuity, but don't repeat information unless asked.")
                    }
                }

                // Build session config
                val sessionConfig = VoiceAgentSessionConfig(
                    instructions = fullInstructions,
                    voice = voiceSupport.voice,
                    turnDetection = voiceSupport.turnDetection,
                    tools = serializableTools,
                    inputTranscription = TranscriptionConfig(),
                )

                // Create handler
                val handler = PubSubVoiceAgentHandler(
                    voiceAgentService = voiceSupport.voiceAgent(),
                    pubsub = voiceSupport.pubsub(),
                    audioStreamAdapter = audioStreamAdapter,
                    sessionConfig = sessionConfig,
                    toolHandler = { toolName, arguments ->
                        voiceSupport.executeVoiceTool(connection, access, conversation, tools, toolName, arguments)
                    },
                    onTranscript = { entry ->
                        when (entry.role) {
                            TranscriptRole.USER -> {
                                val message = SystemChatMessage(
                                    conversationId = conversation._id,
                                    subjectId = conversation.subjectId,
                                    role = SystemChatMessage.Role.User,
                                    channel = CHANNEL_PHONE,
                                    content = entry.text,
                                    createdAt = with(connection) { now() },
                                    skipAutoResponse = true,
                                )
                                with(connection) { chatEndpoints.messageInfo.table().insertOne(message) }
                            }
                            TranscriptRole.AGENT -> {
                                val message = SystemChatMessage(
                                    conversationId = conversation._id,
                                    subjectId = conversation.subjectId,
                                    role = SystemChatMessage.Role.Assistant,
                                    channel = CHANNEL_PHONE,
                                    content = entry.text,
                                    createdAt = with(connection) { now() },
                                    skipAutoResponse = true,
                                )
                                with(connection) { chatEndpoints.messageInfo.table().insertOne(message) }
                            }
                        }
                    }
                )

                val state = handler.createState()
                phoneHandlerStates[currentState.connectionId] = PhoneHandlerInfo(handler, state)

                logger.info { "Phone handler initialized for conversation $conversationId" }

                // Start the handler
                handler.onConnect(state) { responseText -> connection.send(WebSocketFrame.Text(responseText)) }

                // Also forward the Connected event to the handler so it can track streamId
                handler.onMessage(state, text)
            },

            disconnect = {
                logger.info { "Phone audio WebSocket disconnecting: ${currentState.connectionId}" }
                val info = phoneHandlerStates.remove(currentState.connectionId)
                if (info != null) {
                    info.handler.onDisconnect(info.state)
                }
            }
        )
    }

    /**
     * Incoming call webhook - returns instructions to connect to voice agent.
     */
    public val phoneIncoming: ServerBuilder? = phoneCall?.let { phoneCallSetting ->
        val voiceSupport = this@VoiceChannelSupport
        path.path("phone").path("incoming") module Runtime { phoneCallSetting().onIncomingCall }.invoke { event ->
            logger.info { "Incoming call from ${event.from.raw} to ${event.to.raw}" }

            // Resolve subject from phone number
            val resolver = voiceSupport.resolveSubjectByPhone
                ?: throw IllegalStateException("resolveSubjectByPhone required for phone support")

            val subject = resolver(event.from)
            if (subject == null) {
                logger.warn { "Unknown caller: ${event.from.raw}" }
                return@invoke CallInstructions.Say(
                    text = "Sorry, we couldn't identify your phone number. Please contact support.",
                    then = CallInstructions.Hangup
                )
            }

            // Create or find conversation for this caller
            val auth = voiceSupport.createAuthForSubject(subject)
            val access = AuthAccess(auth)
            val conversation = voiceSupport.findOrCreateConversation(access, subject)

            // Get WebSocket URL for audio streaming
            val audioWsHandler = voiceSupport.phoneAudioWebSocket
                ?: throw IllegalStateException("Phone audio WebSocket not initialized")
            // fullUrl() already includes the base URL, just need to change protocol to wss
            val wsUrl = audioWsHandler.location.resolved().fullUrl()
                .replace("http://", "wss://")
                .replace("https://", "wss://")

            logger.info { "Connecting call ${event.callId} to WebSocket: $wsUrl" }

            // Create instructions to connect to voice agent
            // Pass conversationId and subjectId via customParameters - they'll be in the Connected event
            createVoiceAgentStreamInstructions(
                websocketUrl = wsUrl,
                greeting = voiceSupport.greeting ?: "Hello! How can I help you today?",
                customParameters = mapOf(
                    "conversationId" to conversation._id.toString(),
                    "subjectId" to subject._id.toString(),
                    "callId" to event.callId,
                )
            )
        }
    }

    /**
     * Call status webhook - tracks call lifecycle.
     */
    public val phoneStatus: ServerBuilder? = phoneCall?.let { phoneCallSetting ->
        path.path("phone").path("status") module Runtime { phoneCallSetting().onCallStatus }.invoke { event ->
            logger.info { "Call ${event.callId} status: ${event.status}" }
            Unit
        }
    }

    private data class PhoneHandlerInfo(
        val handler: PubSubVoiceAgentHandler,
        val state: PubSubVoiceAgentHandler.ConnectionState,
    )

    /**
     * Track active phone handler states.
     * Uses ConcurrentHashMap for thread safety.
     */
    private val phoneHandlerStates = ConcurrentHashMap<String, PhoneHandlerInfo>()

    //
    // Helper methods
    //

    context(runtime: ServerRuntime)
    private suspend fun findOrCreateConversation(
        access: AuthAccess<Subject>,
        subject: Subject,
    ): SystemChatConversation {
        // Find most recent conversation
        val existing = chatEndpoints.conversationInfo.table(access)
            .find(
                condition { it.subjectId eq subject._id.toString() },
                orderBy = sort { it.updatedAt.descending() }
            )
            .firstOrNull()

        if (existing != null) return existing

        // Create new conversation
        val newConversation = SystemChatConversation(
            subjectId = subject._id.toString(),
            createdAt = now()
        )
        return chatEndpoints.conversationInfo.table(access).insertOne(newConversation)!!
    }

    context(runtime: ServerRuntime)
    private fun createAuthForSubject(subject: Subject): Authentication<Subject> {
        return Authentication(
            principalType = principalType,
            id = subject._id,
            sessionId = null,
            issuedAt = now(),
            expiration = null,
        )
    }

    /**
     * Parse a subject ID from its string representation.
     * Handles common ID types: Uuid, String, Int, Long.
     */
    private fun parseSubjectId(idString: String): ID {
        val serializer = principalType.idSerializer

        // Try to parse based on the serializer's descriptor name
        return try {
            // For most types, try JSON deserialization with proper quoting
            val jsonValue = when {
                // UUIDs and strings need quotes
                serializer.descriptor.serialName.contains("Uuid", ignoreCase = true) ||
                serializer.descriptor.serialName.contains("String", ignoreCase = true) ->
                    "\"$idString\""
                // Numbers don't need quotes
                serializer.descriptor.serialName.contains("Int", ignoreCase = true) ||
                serializer.descriptor.serialName.contains("Long", ignoreCase = true) ->
                    idString
                // Default: try with quotes first, then without
                else -> "\"$idString\""
            }
            kotlinx.serialization.json.Json.decodeFromString(serializer, jsonValue)
        } catch (e: Exception) {
            // Fallback: try without quotes if quoted version failed
            try {
                kotlinx.serialization.json.Json.decodeFromString(serializer, idString)
            } catch (e2: Exception) {
                throw IllegalArgumentException("Cannot parse ID '$idString' for type ${serializer.descriptor.serialName}", e)
            }
        }
    }

    /**
     * Load recent conversation history as a formatted string for context injection.
     */
    context(runtime: ServerRuntime)
    private suspend fun loadConversationHistory(
        conversationId: Uuid,
        limit: Int
    ): String {
        val messages = chatEndpoints.messageInfo.table()
            .find(
                condition { it.conversationId eq conversationId },
                orderBy = sort { it.createdAt.descending() },
                limit = limit
            )
            .toList()
            .reversed() // Show oldest first

        if (messages.isEmpty()) return ""

        return buildString {
            messages.forEach { msg ->
                val role = when (msg.role) {
                    SystemChatMessage.Role.User -> "User"
                    SystemChatMessage.Role.Assistant -> "Assistant"
                    SystemChatMessage.Role.System -> return@forEach // Skip system messages in history
                    SystemChatMessage.Role.Summary -> "Summary"
                    SystemChatMessage.Role.Thinking -> return@forEach // Skip thinking messages
                    SystemChatMessage.Role.ToolRequest -> {
                        val toolInfo = msg.tool
                        if (toolInfo != null) {
                            appendLine("[Tool: ${toolInfo.toolName}]")
                            if (toolInfo.result != null) {
                                appendLine("Result: ${toolInfo.result.take(200)}${if (toolInfo.result.length > 200) "..." else ""}")
                            }
                        }
                        return@forEach
                    }
                    SystemChatMessage.Role.Error -> "Error"
                }
                appendLine("$role: ${msg.content}")
            }
        }
    }

    public companion object {
        public const val CHANNEL_VOICE: String = "voice"
        public const val CHANNEL_PHONE: String = "phone"
    }
}

//
// Client/Server message types for voice WebSocket
//

@Serializable
public sealed class VoiceClientMessage {
    /** Send audio data (base64 encoded PCM16 24kHz) */
    @Serializable
    @kotlinx.serialization.SerialName("audio")
    public data class Audio(val data: String) : VoiceClientMessage()

    /** Commit audio buffer (for manual turn detection) */
    @Serializable
    @kotlinx.serialization.SerialName("commit")
    public data object Commit : VoiceClientMessage()

    /** Cancel current response */
    @Serializable
    @kotlinx.serialization.SerialName("cancel")
    public data object Cancel : VoiceClientMessage()
}

@Serializable
public sealed class VoiceServerMessage {
    /** Session is ready */
    @Serializable
    @kotlinx.serialization.SerialName("session_ready")
    public data class SessionReady(val sessionId: String) : VoiceServerMessage()

    /** Audio data from agent (base64 encoded PCM16 24kHz) */
    @Serializable
    @kotlinx.serialization.SerialName("audio")
    public data class Audio(val data: String) : VoiceServerMessage()

    /** User speech transcription */
    @Serializable
    @kotlinx.serialization.SerialName("user_transcript")
    public data class UserTranscript(val text: String) : VoiceServerMessage()

    /** Agent speech transcription */
    @Serializable
    @kotlinx.serialization.SerialName("agent_transcript")
    public data class AgentTranscript(val text: String) : VoiceServerMessage()

    /** User started speaking */
    @Serializable
    @kotlinx.serialization.SerialName("speech_started")
    public data object SpeechStarted : VoiceServerMessage()

    /** User stopped speaking */
    @Serializable
    @kotlinx.serialization.SerialName("speech_ended")
    public data object SpeechEnded : VoiceServerMessage()

    /** Error occurred */
    @Serializable
    @kotlinx.serialization.SerialName("error")
    public data class Error(val code: String, val message: String) : VoiceServerMessage()
}

//
// Tool descriptor conversion
//

/**
 * Convert a ChatTool to SerializableToolDescriptor for voice agent.
 */
public fun <Subject : HasId<*>, T> ChatTool<Subject, T>.toSerializableDescriptor(
    module: SerializersModule
): SerializableToolDescriptor {
    val koogDescriptor = koogDescriptor(module)
    return koogDescriptor.toSerializable()
}

/**
 * Convert Koog ToolDescriptor to SerializableToolDescriptor.
 */
public fun ToolDescriptor.toSerializable(): SerializableToolDescriptor {
    return SerializableToolDescriptor(
        name = name,
        description = description,
        requiredParameters = requiredParameters.map { it.toSerializable() },
        optionalParameters = optionalParameters.map { it.toSerializable() },
    )
}

/**
 * Convert Koog ToolParameterDescriptor to SerializableToolParameterDescriptor.
 */
public fun ToolParameterDescriptor.toSerializable(): SerializableToolParameterDescriptor {
    return SerializableToolParameterDescriptor(
        name = name,
        description = description,
        type = type.toSerializable(),
    )
}

/**
 * Convert Koog ToolParameterType to SerializableToolParameterType.
 */
public fun ToolParameterType.toSerializable(): SerializableToolParameterType {
    return when (this) {
        is ToolParameterType.String -> SerializableToolParameterType.String
        is ToolParameterType.Integer -> SerializableToolParameterType.Integer
        is ToolParameterType.Float -> SerializableToolParameterType.Float
        is ToolParameterType.Boolean -> SerializableToolParameterType.Boolean
        is ToolParameterType.Null -> SerializableToolParameterType.Null
        is ToolParameterType.Enum -> SerializableToolParameterType.Enum(entries.toList())
        is ToolParameterType.List -> SerializableToolParameterType.ListType(itemsType.toSerializable())
        is ToolParameterType.Object -> SerializableToolParameterType.Object(
            properties = properties.map { it.toSerializable() },
            requiredProperties = requiredProperties?.toList() ?: emptyList(),
            additionalProperties = additionalProperties ?: false,
        )
        is ToolParameterType.AnyOf -> SerializableToolParameterType.AnyOf(
            types.map { it.toSerializable() }
        )
    }
}
