package com.lightningkite.lightningserver.ai

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.markdown.markdown
import ai.koog.prompt.message.Message
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
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.fullUrl
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.location
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.settings.invoke
import com.lightningkite.lightningserver.typed.AuthAccess
import com.lightningkite.lightningserver.typed.auth
import com.lightningkite.lightningserver.typed.sdk.module
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketFrame
import com.lightningkite.lightningserver.websockets.send
import com.lightningkite.services.data.WebsocketAdapter
import com.lightningkite.services.database.*
import com.lightningkite.services.phonecall.AudioStreamCommand
import com.lightningkite.services.phonecall.AudioStreamEvent
import com.lightningkite.services.phonecall.AudioStreamStart
import com.lightningkite.services.phonecall.CallInstructions
import com.lightningkite.services.phonecall.PhoneCallService
import com.lightningkite.services.pubsub.PubSub
import com.lightningkite.services.voiceagent.*
import com.lightningkite.services.voiceagent.phonecall.PubSubVoiceAgentHandler
import com.lightningkite.services.voiceagent.phonecall.TranscriptRole
import com.lightningkite.services.voiceagent.phonecall.createVoiceAgentStreamInstructions
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger("VoiceChannelSupport")

/**
 * Adds voice and phone call channel support to an existing [LLMChatEndpoints] instance.
 *
 * This class provides WebSocket endpoints for real-time voice conversations with the AI agent,
 * supporting both direct voice connections and phone call integration via Twilio.
 *
 * ## Lambda Compatibility
 *
 * This implementation is designed to work with AWS Lambda + API Gateway WebSockets.
 * It uses [PubSubVoiceAgentHandler] which leverages PubSub for state coordination,
 * allowing the system to scale across multiple Lambda instances.
 *
 * ## Features
 *
 * - **Direct Voice WebSocket**: Clients can connect directly with audio streaming
 * - **Phone Call Integration**: Incoming calls via Twilio connect to voice agent
 * - **Transcript Storage**: Voice transcriptions are stored as SystemChatMessage entries
 * - **Tool Calling**: Voice agent can use the same tools as text chat
 * - **LLM-Generated Greetings**: The LLM generates greetings naturally in its voice
 *
 * @param Subject The authenticated user type
 * @param ID The ID type for the subject
 * @param chatEndpoints The LLMChatEndpoints instance to add voice support to (provides tools and instructions)
 * @param authRequirement Authentication requirement for the voice WebSocket
 * @param principalType The PrincipalType for creating Authentication instances
 * @param voiceAgent Voice agent service settings
 * @param pubsub PubSub service settings (required for Lambda-compatible state management)
 * @param phoneCall Optional phone call service settings for phone integration
 * @param voice Voice configuration (name, language, speed)
 * @param turnDetection How to detect when user has finished speaking
 * @param resolveSubjectByPhone Function to resolve a subject from phone number (required for phone support)
 * @param historyMessageLimit Number of recent messages to inject as context (0 to disable)
 * @param onUsage Optional callback for usage tracking
 */
public class VoiceChannelSupport<Subject : HasId<ID>, ID : Comparable<ID>>(
    private val chatEndpoints: LLMChatEndpoints<Subject>,
    private val authRequirement: AuthRequirement<Subject>,
    private val principalType: PrincipalType<Subject, ID>,
    private val voiceAgent: ServerSetting<VoiceAgentService.Settings, VoiceAgentService>,
    private val pubsub: ServerSetting<PubSub.Settings, PubSub>,
    private val phoneCall: ServerSetting<PhoneCallService.Settings, PhoneCallService>? = null,
    private val voice: VoiceConfig = VoiceConfig(name = "alloy"),
    private val turnDetection: TurnDetection = TurnDetection.ServerVAD(),
    private val resolveSubjectByPhone: (suspend context(ServerRuntime) (PhoneNumber) -> Subject?)? = null,
    private val voiceInstructions: String = """
        Now that we're starting a new voice session, greet the user naturally and ask how you can help.  Since this is a voice conversation, ensure you are brief but clear in your dialog.
    """.trimIndent(),
    private val historyMessageLimit: Int = 20,
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
                val state = currentState
                val auth = request.auth(authRequirement) ?: return@WebSocketHandler
                val access = AuthAccess(auth)

                val conversation = chatEndpoints.conversationInfo.table(access)
                    .get(state.conversationId) ?: return@WebSocketHandler

                // Create handler for this connection
                val handler = createVoiceHandler(
                    connectionId = state.connectionId,
                    access = access,
                    conversation = conversation,
                    channel = CHANNEL_VOICE
                )

                // Store handler
                voiceHandlers[state.connectionId] = handler

                // Initialize session
                handler.handler.onConnect(handler.state) { responseText ->
                    connection.send(WebSocketFrame.Text(responseText))
                }

                // Trigger initial greeting after a short delay to ensure event handlers are ready
                handler.state.scope.launch {
                    kotlinx.coroutines.delay(100)
                    // Add a system message to trigger the greeting
                    handler.state.session?.addMessage("system", "Voice session started. Greet the user now.")
                    handler.state.session?.createResponse()
                }

                logger.info { "Voice WebSocket connected: ${state.connectionId}" }
            },

            messageFromClient = { frame ->
                val text = when (frame) {
                    is WebSocketFrame.Text -> frame.content
                    is WebSocketFrame.Binary -> return@WebSocketHandler
                }

                val handlerInfo = voiceHandlers[currentState.connectionId]
                    ?: return@WebSocketHandler

                handlerInfo.handler.onMessage(handlerInfo.state, text)
            },

            disconnect = {
                val handlerInfo = voiceHandlers.remove(currentState.connectionId)
                if (handlerInfo != null) {
                    handlerInfo.handler.onDisconnect(handlerInfo.state)
                }
                logger.info { "Voice WebSocket disconnected: ${currentState.connectionId}" }
            }
        )

    private data class VoiceHandlerInfo(
        val handler: PubSubVoiceAgentHandler,
        val state: PubSubVoiceAgentHandler.ConnectionState,
    )

    private val voiceHandlers = ConcurrentHashMap<String, VoiceHandlerInfo>()

    /**
     * Create a voice agent handler for the given connection.
     */
    private suspend fun ServerRuntime.createVoiceHandler(
        connectionId: String,
        access: AuthAccess<Subject>,
        conversation: SystemChatConversation,
        channel: String,
    ): VoiceHandlerInfo {
        // Get tools from LLMChatEndpoints
        val tools = chatEndpoints.tools.values.toList()
        val serializableTools = tools.map {
            it.toSerializableDescriptor(access)
        }

        val previousChat = chatEndpoints.messagesForPrompt(conversation)
        val prompt = promptAlt(Prompt.Empty) {
            chatEndpoints.promptPreMessages(this, access, conversation)
            chatEndpoints.promptToolInfoMessages(this, access, conversation)
            if(previousChat.size <= historyMessageLimit) {
                append(previousChat)
            } else {
                val toCompress = previousChat.dropLast(historyMessageLimit * 3 / 4)
                val toNotCompress = previousChat.takeLast(historyMessageLimit * 3 / 4)
                val compressed = chatEndpoints.defaultLlm().execute(
                    promptAlt(existing = Prompt.Empty) {
                        chatEndpoints.promptPreMessages(
                            this,
                            access,
                            conversation
                        )  // To provide some context for the messages
                        append(toCompress)
                        user {
                            markdown {
                                +"Create a comprehensive summary of this conversation."
                                br()
                                +"Include the following in your summary:"
                                numbered {
                                    item("Key objectives and problems being addressed")
                                    item("All tools used along with their purpose and outcomes")
                                    item("Critical information discovered or generated")
                                    item("Current progress status and conclusions reached")
                                    item("Any pending questions or unresolved issues")
                                }
                                br()
                                +"FORMAT YOUR SUMMARY WITH CLEAR SECTIONS for easy reference, including:"
                                bulleted {
                                    item("Key Objectives")
                                    item("Tools Used & Results")
                                    item("Key Findings")
                                    item("Current Status")
                                    item("Next Steps")
                                }
                                br()
                                +"This summary will be the ONLY context available for continuing this conversation, along with the system message."
                                +"Ensure it contains ALL essential information needed to proceed effectively."
                            }
                        }
                    },
                    listOf()
                )
                system("=== Conversation Summary ===\n$compressed\n=== Recent Messages ===")
                append(toNotCompress)
                system("=== Right now ===\n$voiceInstructions")
            }
            chatEndpoints.promptPostMessages(this, access, conversation)
        }

        // Convert prompt to voice instructions
        val fullInstructions = buildString {
            // Add system messages as base instructions
            prompt.messages.forEach { message ->
                when (message) {
                    is Message.System -> {
                        append(message.content)
                        appendLine()
                    }
                    is Message.User -> {
                        appendLine("USER: ${message.content}")
                    }
                    is Message.Assistant -> {
                        appendLine("ASSISTANT: ${message.content}")
                    }
                    else -> {} // Skip other message types
                }
            }
        }

        // Create session config
        val sessionConfig = VoiceAgentSessionConfig(
            instructions = fullInstructions,
            voice = voice,
            turnDetection = turnDetection,
            tools = serializableTools,
            inputTranscription = TranscriptionConfig(), // Enable transcription for storage
        )

        // Create adapter
        val adapter = DirectVoiceStreamAdapter(connectionId)

        // Create handler
        val handler = PubSubVoiceAgentHandler(
            voiceAgentService = voiceAgent(),
            pubsub = pubsub(),
            audioStreamAdapter = adapter,
            sessionConfig = sessionConfig,
            toolHandler = { toolName, arguments ->
                executeVoiceTool(this, access, conversation, tools, toolName, arguments)
            },
            onTranscript = { entry ->
                when (entry.role) {
                    TranscriptRole.USER -> saveUserMessage(conversation, entry.text, channel)
                    TranscriptRole.AGENT -> saveAssistantMessage(conversation, entry.text, channel)
                }
            }
        )

        val state = handler.createState()
        return VoiceHandlerInfo(handler, state)
    }

    //
    // Phone Call Integration (optional)
    //

    @Serializable
    public data class PhoneAudioState(
        val connectionId: String = Uuid.random().toString(),
        val initialized: Boolean = false,
        val conversationId: Uuid? = null,
        val subjectId: String? = null,
    )

    public val phoneAudioWebSocket: WebSocketHandler<PathSpec0, PhoneAudioState>? = phoneCall?.let { phoneCallSetting ->
        path.path("phone").path("audio") bind WebSocketHandler(
            storageSerializer = PhoneAudioState.serializer(),

            willConnect = { request ->
                logger.info { "Phone audio WebSocket connecting" }
                PhoneAudioState()
            },

            messageFromClient = { frame ->
                val connection = this
                val text = when (frame) {
                    is WebSocketFrame.Text -> frame.content
                    is WebSocketFrame.Binary -> return@WebSocketHandler
                }

                // Check if we already have a handler initialized
                val existingInfo = phoneHandlers[currentState.connectionId]
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
                    ?: run {
                        logger.error { "Missing conversationId in customParameters" }
                        return@WebSocketHandler
                    }
                val conversationId = Uuid.parse(conversationIdStr)

                val subjectIdStr = event.customParameters["subjectId"]
                    ?: run {
                        logger.error { "Missing subjectId in customParameters" }
                        return@WebSocketHandler
                    }

                // Look up conversation
                val conversation = with(connection) { chatEndpoints.conversationInfo.table().get(conversationId) }
                    ?: run {
                        logger.error { "Conversation not found: $conversationId" }
                        return@WebSocketHandler
                    }

                // Parse the subject ID and create auth
                val subjectId = try {
                    parseSubjectId(subjectIdStr)
                } catch (e: Exception) {
                    logger.error(e) { "Failed to parse subject ID: $subjectIdStr" }
                    return@WebSocketHandler
                }

                val auth = Authentication(
                    principalType = principalType,
                    id = subjectId,
                    sessionId = null,
                    issuedAt = with(connection) { now() },
                    expiration = null,
                )
                val access = AuthAccess(auth)

                // Create phone handler using existing logic
                val handler = with(connection) {
                    createPhoneHandler(
                        connectionId = currentState.connectionId,
                        access = access,
                        conversation = conversation,
                        audioStreamAdapter = audioStreamAdapter
                    )
                }

                phoneHandlers[currentState.connectionId] = handler

                logger.info { "Phone handler initialized for conversation $conversationId" }

                // Start the handler
                handler.handler.onConnect(handler.state) { responseText ->
                    connection.send(WebSocketFrame.Text(responseText))
                }

                // Also forward the Connected event to the handler so it can track streamId
                handler.handler.onMessage(handler.state, text)

                // Trigger initial greeting after a short delay to ensure event handlers are ready
                handler.state.scope.launch {
                    kotlinx.coroutines.delay(100)
                    // Add a system message to trigger the greeting
                    handler.state.session?.addMessage("system", "Voice session started. Greet the user now.")
                    handler.state.session?.createResponse()
                }
            },

            disconnect = {
                logger.info { "Phone audio WebSocket disconnecting: ${currentState.connectionId}" }
                val info = phoneHandlers.remove(currentState.connectionId)
                if (info != null) {
                    info.handler.onDisconnect(info.state)
                }
            }
        )
    }

    private val phoneHandlers = ConcurrentHashMap<String, VoiceHandlerInfo>()

    /**
     * Create a phone handler for the given connection.
     */
    private suspend fun ServerRuntime.createPhoneHandler(
        connectionId: String,
        access: AuthAccess<Subject>,
        conversation: SystemChatConversation,
        audioStreamAdapter: WebsocketAdapter<*, *, *>,
    ): VoiceHandlerInfo {
        // Get tools from LLMChatEndpoints
        val tools = chatEndpoints.tools.values.toList()
        val serializableTools = tools.map {
            it.toSerializableDescriptor(access)
        }

        // Get full prompt from LLMChatEndpoints (includes system message + conversation history)
        val prompt = chatEndpoints.getPrompt(conversation, access)

        // Convert prompt to voice instructions
        val fullInstructions = buildString {
            // Add system messages as base instructions
            prompt.messages.forEach { message ->
                when (message) {
                    is Message.System -> {
                        append(message.content)
                        append("\n\n")
                    }
                    is Message.User -> {
                        append("## Previous User Message\n")
                        append(message.content)
                        append("\n\n")
                    }
                    is Message.Assistant -> {
                        append("## Previous Assistant Message\n")
                        append(message.content)
                        append("\n\n")
                    }
                    else -> {} // Skip other message types
                }
            }
            // Add natural greeting instruction (LLM will generate the actual greeting)
            append("## Session Start\n")
            append("When starting a new voice session, greet the user naturally and ask how you can help.")
        }

        // Build session config
        val sessionConfig = VoiceAgentSessionConfig(
            instructions = fullInstructions,
            voice = voice,
            turnDetection = turnDetection,
            tools = serializableTools,
            inputTranscription = TranscriptionConfig(),
        )

        // Create handler
        @Suppress("UNCHECKED_CAST")
        val handler = PubSubVoiceAgentHandler(
            voiceAgentService = voiceAgent(),
            pubsub = pubsub(),
            audioStreamAdapter = audioStreamAdapter as WebsocketAdapter<AudioStreamStart, AudioStreamEvent, AudioStreamCommand>,
            sessionConfig = sessionConfig,
            toolHandler = { toolName, arguments ->
                executeVoiceTool(this, access, conversation, tools, toolName, arguments)
            },
            onTranscript = { entry ->
                when (entry.role) {
                    TranscriptRole.USER -> saveUserMessage(conversation, entry.text, CHANNEL_PHONE)
                    TranscriptRole.AGENT -> saveAssistantMessage(conversation, entry.text, CHANNEL_PHONE)
                }
            }
        )

        val state = handler.createState()
        return VoiceHandlerInfo(handler, state)
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
            val wsUrl = audioWsHandler.location.resolved().fullUrl()
                .replace("http://", "wss://")
                .replace("https://", "wss://")

            logger.info { "Connecting call ${event.callId} to WebSocket: $wsUrl" }

            // Create instructions to connect to voice agent
            // The LLM will generate the greeting naturally based on session instructions
            createVoiceAgentStreamInstructions(
                websocketUrl = wsUrl,
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

    //
    // Helper methods
    //

    private suspend fun ServerRuntime.saveUserMessage(
        conversation: SystemChatConversation,
        text: String,
        channel: String
    ) {
        val message = SystemChatMessage(
            conversationId = conversation._id,
            subjectId = conversation.subjectId,
            role = SystemChatMessage.Role.User,
            channel = channel,
            content = text,
            createdAt = now(),
            skipAutoResponse = true,
        )
        chatEndpoints.messageInfo.table().insertOne(message)
    }

    private suspend fun ServerRuntime.saveAssistantMessage(
        conversation: SystemChatConversation,
        text: String,
        channel: String
    ) {
        val message = SystemChatMessage(
            conversationId = conversation._id,
            subjectId = conversation.subjectId,
            role = SystemChatMessage.Role.Assistant,
            channel = channel,
            content = text,
            createdAt = now(),
            skipAutoResponse = true,
        )
        chatEndpoints.messageInfo.table().insertOne(message)
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

        // Check if user's last message was a verbal approval - must be exactly a single confirmation word.  Flexible due to mis-transcription issues.
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

        val isVerbalApproval = lastUserMessage?.content?.lowercase()?.replace("please", "")?.replace("thank you", "")?.let { content ->
            val justLetters = content.filter { it.isLetter() }
            val validYes = setOf("yes", "yeah", "yep")
            justLetters in validYes
        } ?: false

        val isVerbalDisapproval = lastUserMessage?.content?.lowercase()?.replace("please", "")?.replace("thank you", "")?.let { content ->
            val justLetters = content.filter { it.isLetter() }
            val validNo = setOf("no", "nope", "nah", "dont", "dontdoit")
            justLetters in validNo
        } ?: false

        // Only check for pending approvals if the user explicitly said exactly a single confirmation/rejection word.  Flexible due to mis-transcription issues.
        val pendingApproval = if (isVerbalApproval || isVerbalDisapproval) {
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
            if (isVerbalDisapproval) {
                // User declined the action
                logger.info { "User declined pending approval for $toolName" }

                val rejection = ToolApproval(
                    approved = false,
                    approvedBy = access.auth.rawId,
                    approvedAt = with(runtime) { now() },
                    reason = "Voice rejection"
                )

                // Mark as rejected
                with(runtime) {
                    chatEndpoints.messageInfo.table().updateOneById(
                        pendingApproval._id,
                        modification {
                            it.tool.notNull.approval assign rejection
                            it.tool.notNull.error assign "User declined the action"
                        }
                    )
                }

                return """{"status": "rejected", "message": "Action cancelled by user"}"""
            }

            // Found a pending approval - the user said "yes", so auto-approve and execute
            logger.info { "Found pending approval for $toolName, auto-approving and executing" }

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

    context(runtime: ServerRuntime)
    private suspend fun findOrCreateConversation(
        access: AuthAccess<Subject>,
        subject: Subject,
    ): SystemChatConversation {
        val existing = chatEndpoints.conversationInfo.table(access)
            .find(
                condition { it.subjectId eq subject._id.toString() },
                orderBy = sort { it.updatedAt.descending() }
            )
            .firstOrNull()

        if (existing != null) return existing

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

    private fun parseSubjectId(idString: String): ID {
        val serializer = principalType.idSerializer

        return try {
            val jsonValue = when {
                serializer.descriptor.serialName.contains("Uuid", ignoreCase = true) ||
                serializer.descriptor.serialName.contains("String", ignoreCase = true) ->
                    "\"$idString\""
                serializer.descriptor.serialName.contains("Int", ignoreCase = true) ||
                serializer.descriptor.serialName.contains("Long", ignoreCase = true) ->
                    idString
                else -> "\"$idString\""
            }
            kotlinx.serialization.json.Json.decodeFromString(serializer, jsonValue)
        } catch (e: Exception) {
            try {
                kotlinx.serialization.json.Json.decodeFromString(serializer, idString)
            } catch (e2: Exception) {
                throw IllegalArgumentException("Cannot parse ID '$idString' for type ${serializer.descriptor.serialName}", e)
            }
        }
    }

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
            .reversed()

        if (messages.isEmpty()) return ""

        return buildString {
            messages.forEach { msg ->
                val role = when (msg.role) {
                    SystemChatMessage.Role.User -> "User"
                    SystemChatMessage.Role.Assistant -> "Assistant"
                    SystemChatMessage.Role.System -> return@forEach
                    SystemChatMessage.Role.Summary -> "Summary"
                    SystemChatMessage.Role.Thinking -> return@forEach
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
// Tool descriptor conversion
//

/**
 * Convert a ChatTool to SerializableToolDescriptor for voice agent.
 */
context(runtime: ServerRuntime)
public suspend fun <Subject : HasId<*>, T> ChatTool<Subject, T>.toSerializableDescriptor(
    auth: AuthAccess<Subject>
): SerializableToolDescriptor {
    val koogDescriptor = koogDescriptor(auth)
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
