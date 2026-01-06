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
import com.lightningkite.lightningserver.ai.models.*
import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.settings.invoke
import com.lightningkite.lightningserver.typed.AuthAccess
import com.lightningkite.lightningserver.typed.auth
import com.lightningkite.lightningserver.typed.sdk.module
import com.lightningkite.lightningserver.websockets.CoroutineWebsocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketConnectRequest
import com.lightningkite.lightningserver.websockets.WebSocketFrame
import com.lightningkite.services.database.*
import com.lightningkite.services.phonecall.AudioStreamEvent
import com.lightningkite.services.phonecall.CallInstructions
import com.lightningkite.services.phonecall.PhoneCallService
import com.lightningkite.services.pubsub.PubSub
import com.lightningkite.services.voiceagent.*
import com.lightningkite.services.voiceagent.phonecall.TranscriptEntry
import com.lightningkite.services.voiceagent.phonecall.TranscriptRole
import com.lightningkite.services.voiceagent.phonecall.createVoiceAgentStreamInstructions
import com.lightningkite.services.voiceagent.phonecall.handleDirectVoiceSession
import com.lightningkite.services.voiceagent.phonecall.handlePhoneVoiceSession
import io.github.oshai.kotlinlogging.KotlinLogging
import io.opentelemetry.api.GlobalOpenTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger("VoiceChannelSupport")

/**
 * Adds voice and phone call channel support to an existing [LLMChatEndpoints] instance.
 *
 * This class provides WebSocket endpoints for real-time voice conversations with the AI agent,
 * supporting both direct voice connections and phone call integration via Twilio.
 *
 * ## Features
 *
 * - **Direct Voice WebSocket**: Clients can connect directly with audio streaming
 * - **Phone Call Integration**: Incoming calls via Twilio connect to voice agent
 * - **Transcript Storage**: Voice transcriptions are stored as SystemChatMessage entries
 * - **Tool Calling**: Voice agent can use the same tools as text chat
 * - **LLM-Generated Greetings**: The LLM generates greetings naturally in its voice
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
        Now that we're starting a new voice session, greet the user naturally and ask how you can help. Since this is a voice conversation, ensure you are brief but clear in your dialog.
    """.trimIndent(),
    private val historyMessageLimit: Int = 20,
    private val onUsage: (suspend context(ServerRuntime) (conversationId: Uuid, channel: String, usage: UsageStats) -> Unit)? = null,
    /**
     * The path where this VoiceChannelSupport is mounted (e.g., "/assistant-voice").
     * Required for phone call support to construct WebSocket URLs.
     */
    private val basePath: String = "",
    /**
     * Size of the jitter buffer in milliseconds for phone audio.
     * Higher values add latency but smooth out irregular audio delivery
     * (e.g., from DynamoDB PubSub polling). Set to 0 to disable.
     * Default is 150ms.
     */
    private val jitterBufferMs: Long = 150L,
) : ServerBuilder() {

    private val tracer = try {
        GlobalOpenTelemetry.getTracer("VoiceChannelSupport")
    } catch (e: Exception) {
        null
    }

    //
    // Direct Voice WebSocket (browser/app connection)
    //

    private inner class DirectVoiceWebSocketHandler : CoroutineWebsocketHandler() {
        override val pubSub: Runtime<PubSub> = this@VoiceChannelSupport.pubsub

        @OptIn(ExperimentalEncodingApi::class)
        context(serverRuntime: ServerRuntime)
        override suspend fun handle(
            request: WebSocketConnectRequest<PathSpec0>,
            waitForFullConnect: suspend () -> Unit,
            incoming: Flow<WebSocketFrame>,
            send: suspend (WebSocketFrame) -> Unit
        ) {
            // Authenticate
            val auth = request.auth(authRequirement)
                ?: throw UnauthorizedException("Authentication required")
            val access = AuthAccess(auth)
            val subjectId = auth.rawId.toString()

            // Get or create conversation
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

            // Wait for WebSocket to be fully connected
            waitForFullConnect()
            logger.info { "Direct voice WebSocket connected for conversation ${conversation._id}" }

            // Build session config
            val sessionConfig = buildSessionConfig(access, conversation, CHANNEL_VOICE, includeToolSchemas = true)

            // Parse incoming audio frames (JSON with base64 audio)
            val incomingAudio = incoming.map { frame ->
                when (frame) {
                    is WebSocketFrame.Text -> {
                        // Parse JSON: {"audio": "base64..."}
                        val json = Json.parseToJsonElement(frame.content)
                        json.jsonObject["audio"]?.jsonPrimitive?.content ?: ""
                    }

                    is WebSocketFrame.Binary -> Base64.encode(frame.content)
                }
            }

            // Run voice session
            handleDirectVoiceSession(
                voiceAgentService = voiceAgent(),
                sessionConfig = sessionConfig,
                incomingAudio = incomingAudio,
                sendAudio = { base64Audio ->
                    send(WebSocketFrame.Text("""{"audio":"$base64Audio"}"""))
                },
                sendClear = {
                    send(WebSocketFrame.Text("""{"clear":true}"""))
                },
                toolHandler = { toolName, arguments ->
                    executeVoiceTool(access, conversation, toolName, arguments)
                },
                onTranscript = { entry ->
                    saveTranscript(conversation, entry, CHANNEL_VOICE)
                },
                onSessionReady = { session ->
                    logger.info { "Voice session ready, triggering greeting" }
                    session.addMessage(VoiceAgentSession.MessageRole.User, voiceInstructions)
                    session.createResponse()
                },
                tracer = tracer,
            )

            logger.info { "Direct voice session ended for conversation ${conversation._id}" }
        }
    }

    private val directVoiceHandler = DirectVoiceWebSocketHandler()

    init {
        path.path("voice").include(directVoiceHandler)
    }

    /** Direct voice WebSocket handler for browser/app connections */
    public val voiceWebSocket: CoroutineWebsocketHandler get() = directVoiceHandler

    //
    // Phone Call Integration (optional)
    //
    /** Phone audio WebSocket handler (null if phone support not configured) */
    public val phoneAudioWebSocket: CoroutineWebsocketHandler? = phoneCall?.let { phoneCall ->
        path.path("phone").path("audio").include(object : CoroutineWebsocketHandler() {
            override val pubSub: Runtime<PubSub> = this@VoiceChannelSupport.pubsub

            @OptIn(ExperimentalEncodingApi::class)
            context(serverRuntime: ServerRuntime)
            override suspend fun handle(
                request: WebSocketConnectRequest<PathSpec0>,
                waitForFullConnect: suspend () -> Unit,
                incoming: Flow<WebSocketFrame>,
                send: suspend (WebSocketFrame) -> Unit
            ) {
                // Wait for WebSocket to be fully connected
                logger.info { "Phone audio WebSocket connected, setting up..." }

                // Get audio stream adapter from phone service
                val phoneService = phoneCall()
                val audioStreamAdapter = phoneService.audioStream
                    ?: throw BadRequestException("Phone service doesn't support audio streaming")

                // Parse incoming frames as Twilio events and buffer them
                val eventBuffer = MutableSharedFlow<AudioStreamEvent>(replay = 100, extraBufferCapacity = 1000)

                // Parse frames and collect into buffer
                val phoneEvents = incoming.map { frame ->
                    when (frame) {
                        is WebSocketFrame.Text -> audioStreamAdapter.parse(
                            com.lightningkite.services.data.WebsocketAdapter.Frame.Text(frame.content)
                        )

                        is WebSocketFrame.Binary -> throw BadRequestException("Binary frames not supported for phone")
                    }
                }

                // Launch buffer collector in background
                val bufferJob = CoroutineScope(currentCoroutineContext()).launch {
                    phoneEvents.collect { eventBuffer.emit(it) }
                }

                logger.info { "Phone audio WebSocket connected, waiting for stream start..." }
                waitForFullConnect()

                try {
                    // Wait for Connected event which contains our custom parameters
                    val connectedEvent = eventBuffer
                        .filterIsInstance<AudioStreamEvent.Connected>()
                        .filter { it.streamId.isNotEmpty() }
                        .first()

                    // Extract conversation and subject from custom parameters
                    val conversationIdStr = connectedEvent.customParameters["conversationId"]
                        ?: throw BadRequestException("conversationId not found in stream parameters")
                    val subjectIdStr = connectedEvent.customParameters["subjectId"]
                        ?: throw BadRequestException("subjectId not found in stream parameters")

                    val conversationId = Uuid.parse(conversationIdStr)
                    val subjectId = parseSubjectId(subjectIdStr)

                    logger.info { "Phone stream connected for conversation $conversationId" }

                    val auth = Authentication(
                        principalType = principalType,
                        id = subjectId,
                        sessionId = null,
                    )
                    val access = AuthAccess(auth)

                    val conversation = chatEndpoints.conversationInfo.table().get(conversationId)
                        ?: throw NotFoundException("Conversation not found")

                    // Build session config (without tool schemas - OpenAI Realtime gets tools directly)
                    val sessionConfig =
                        buildSessionConfig(access, conversation, CHANNEL_PHONE, includeToolSchemas = false)

                    val meta = MutableStateFlow<Flow<AudioStreamEvent>>(emptyFlow())

                    // Run phone voice session using fresh events only
                    handlePhoneVoiceSession(
                        voiceAgentService = voiceAgent(),
                        sessionConfig = sessionConfig,
                        streamId = connectedEvent.streamId,
                        callId = connectedEvent.callId,
                        phoneAudioEvents = meta.flatMapLatest { it },
                        sendToPhone = { cmd ->
                            val frame = audioStreamAdapter.render(cmd)
                            when (frame) {
                                is com.lightningkite.services.data.WebsocketAdapter.Frame.Text ->
                                    send(WebSocketFrame.Text(frame.text))

                                is com.lightningkite.services.data.WebsocketAdapter.Frame.Binary ->
                                    send(WebSocketFrame.Binary(frame.bytes))
                            }
                        },
                        toolHandler = { toolName, arguments ->
                            executeVoiceTool(access, conversation, toolName, arguments)
                        },
                        onTranscript = { entry ->
                            saveTranscript(conversation, entry, CHANNEL_PHONE)
                        },
                        onStreamConnected = { session ->
                            // We don't start shipping off audio events until the voice agent is fully connected.
                            // Otherwise, the agent gets overloaded with back-work.
                            meta.value = eventBuffer
                            logger.info { "Voice agent session ready, triggering greeting" }
                            session.addMessage(VoiceAgentSession.MessageRole.User, voiceInstructions)
                            session.createResponse()
                        },
                        jitterBufferMs = jitterBufferMs,
                        tracer = tracer,
                    )

                    logger.info { "Phone voice session ended for conversation $conversationId" }
                } finally {
                    bufferJob.cancel()
                }
            }
        })
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

            // Build WebSocket URL using basePath parameter
            val wsBaseUrl = generalSettings().wsUrl
            val audioPath = "${voiceSupport.basePath}/phone/audio"
            val wsUrl = "$wsBaseUrl$audioPath"

            logger.info { "Connecting call ${event.callId} to WebSocket: $wsUrl" }

            // Create instructions to connect to voice agent
            // Note: Query params must be passed via customParameters, not in the URL
            createVoiceAgentStreamInstructions(
                websocketUrl = wsUrl,
                customParameters = mapOf(
                    "callId" to event.callId,
                    "conversationId" to conversation._id.toString(),
                    "subjectId" to subject._id.toString(),
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
    // Session Config Building
    //

    context(serverRuntime: ServerRuntime)
    private suspend fun buildSessionConfig(
        access: AuthAccess<Subject>,
        conversation: SystemChatConversation,
        channel: String,
        includeToolSchemas: Boolean,
    ): VoiceAgentSessionConfig {
        // Get tools
        val tools = chatEndpoints.tools.values.toList()
        val serializableTools = tools.map { it.toSerializableDescriptor(access) }

        // Get previous messages (excluding current session)
        val sessionStartTime = now()
        val previousChat = chatEndpoints.messagesForPrompt(conversation)
            .filter { it.channel != channel || it.createdAt < sessionStartTime }

        // Build prompt
        val prompt = promptAlt(Prompt.Empty) {
            chatEndpoints.promptPreMessages(this, access, conversation)
            if (includeToolSchemas) {
                chatEndpoints.promptToolInfoMessages(this, access, conversation)
            }
            appendMessagesWithCompression(this, access, conversation, previousChat, channel)
            chatEndpoints.promptPostMessages(this, access, conversation)
        }

        // Convert to instructions string
        val fullInstructions = buildInstructionsString(prompt)

        logger.info { "$channel session: ${fullInstructions.length} chars (~${fullInstructions.length / 4} tokens), ${serializableTools.size} tools" }

        return VoiceAgentSessionConfig(
            instructions = fullInstructions,
            voice = voice,
            turnDetection = turnDetection,
            tools = serializableTools,
            inputTranscription = TranscriptionConfig(),
        )
    }

    private fun buildInstructionsString(prompt: Prompt): String = buildString {
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

                else -> {}
            }
        }
        append("## Voice Session Instructions\n")
        append(voiceInstructions)
        append("\n\n")
        append("When starting a new voice session, greet the user naturally and ask how you can help.")
    }

    //
    // Message Compression
    //

    context(serverRuntime: ServerRuntime)
    private suspend fun appendMessagesWithCompression(
        promptBuilder: PromptBuilderAlt,
        access: AuthAccess<Subject>,
        conversation: SystemChatConversation,
        previousChat: List<SystemChatMessage>,
        channel: String,
        useLlmCompression: Boolean = false,
    ) {
        logger.info { "$channel: appendMessagesWithCompression with ${previousChat.size} messages, limit=$historyMessageLimit" }
        with(promptBuilder) {
            if (previousChat.size <= historyMessageLimit) {
                append(previousChat)
            } else if (useLlmCompression) {
                // Use LLM to summarize older messages (slower but preserves more context)
                val toCompress = previousChat.dropLast(historyMessageLimit * 3 / 4)
                val toNotCompress = previousChat.takeLast(historyMessageLimit * 3 / 4)
                logger.info { "$channel: compressing ${toCompress.size} messages, keeping ${toNotCompress.size}" }

                val compressed = chatEndpoints.defaultLlm().execute(
                    promptAlt(existing = Prompt.Empty) {
                        chatEndpoints.promptPreMessages(this, access, conversation)
                        append(toCompress)
                        user {
                            markdown {
                                +"Summarize this conversation in 2-4 sentences maximum."
                                +"Focus only on: what the user wants, what was done, and current status."
                                +"Omit greetings, pleasantries, and details that won't affect next steps."
                            }
                        }
                    },
                    listOf()
                )
                system("=== Conversation Summary ===\n$compressed\n=== Recent Messages ===")
                append(toNotCompress)
                system("=== Right now ===\n$voiceInstructions")
            } else {
                // Simple truncation (fast, for voice where instant response matters)
                val toKeep = previousChat.takeLast(historyMessageLimit)
                val droppedCount = previousChat.size - historyMessageLimit
                logger.info { "$channel: truncating to $historyMessageLimit messages (dropped $droppedCount older messages)" }

                if (droppedCount > 0) {
                    system("(${droppedCount} earlier messages omitted for brevity)")
                }
                append(toKeep)
            }
        }
    }

    //
    // Transcript & Message Saving
    //

    context(serverRuntime: ServerRuntime)
    private suspend fun saveTranscript(
        conversation: SystemChatConversation,
        entry: TranscriptEntry,
        channel: String
    ) {
        when (entry.role) {
            TranscriptRole.USER -> saveUserMessage(conversation, entry.text, channel)
            TranscriptRole.AGENT -> saveAssistantMessage(conversation, entry.text, channel)
        }
    }

    context(serverRuntime: ServerRuntime)
    private suspend fun saveUserMessage(
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

    context(serverRuntime: ServerRuntime)
    private suspend fun saveAssistantMessage(
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

    //
    // Tool Execution
    //

    context(serverRuntime: ServerRuntime)
    private suspend fun executeVoiceTool(
        access: AuthAccess<Subject>,
        conversation: SystemChatConversation,
        toolName: String,
        arguments: String,
    ): String {
        val tools = chatEndpoints.tools.values.toList()
        val tool = tools.find { it.name == toolName }
            ?: return """{"error": "Tool '$toolName' not found"}"""

        // Check for verbal approval/disapproval
        val lastUserMessage = chatEndpoints.messageInfo.table()
            .find(
                condition {
                    (it.conversationId eq conversation._id) and
                            (it.role eq SystemChatMessage.Role.User)
                },
                orderBy = sort { it.createdAt.descending() }
            )
            .firstOrNull()

        val isVerbalApproval = lastUserMessage?.content?.lowercase()
            ?.replace("please", "")?.replace("thank you", "")?.let { content ->
                val justLetters = content.filter { it.isLetter() }
                justLetters in setOf("yes", "yeah", "yep")
            } ?: false

        val isVerbalDisapproval = lastUserMessage?.content?.lowercase()
            ?.replace("please", "")?.replace("thank you", "")?.let { content ->
                val justLetters = content.filter { it.isLetter() }
                justLetters in setOf("no", "nope", "nah", "dont", "dontdoit")
            } ?: false

        // Check for pending approval
        val pendingApproval = if (isVerbalApproval || isVerbalDisapproval) {
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
        } else null

        if (pendingApproval != null) {
            if (isVerbalDisapproval) {
                logger.info { "User declined pending approval for $toolName" }
                val rejection = ToolApproval(
                    approved = false,
                    approvedBy = access.auth.rawId,
                    approvedAt = now(),
                    reason = "Voice rejection"
                )
                chatEndpoints.messageInfo.table().updateOneById(
                    pendingApproval._id,
                    modification {
                        it.tool.notNull.approval assign rejection
                        it.tool.notNull.error assign "User declined the action"
                    }
                )
                return """{"status": "rejected", "message": "Action cancelled by user"}"""
            }

            // Auto-approve and execute
            logger.info { "Found pending approval for $toolName, auto-approving" }
            val approval = ToolApproval(
                approved = true,
                approvedBy = access.auth.rawId,
                approvedAt = now(),
                reason = "Voice approval"
            )

            val result = try {
                @Suppress("UNCHECKED_CAST")
                val typedTool = tool as ChatTool<Subject, Any?>
                val args = chatEndpoints.parseToolArg(typedTool, pendingApproval.tool!!.arguments)
                typedTool.execute(access, args)
            } catch (e: Exception) {
                logger.error(e) { "Error executing approved tool $toolName" }
                chatEndpoints.messageInfo.table().updateOneById(
                    pendingApproval._id,
                    modification {
                        it.tool.notNull.approval assign approval
                        it.tool.notNull.error assign e.message
                    }
                )
                return """{"error": "${e.message}"}"""
            }

            chatEndpoints.messageInfo.table().updateOneById(
                pendingApproval._id,
                modification {
                    it.tool.notNull.approval assign approval
                    it.tool.notNull.result assign result
                }
            )
            return result
        }

        // No pending approval - process normally
        return when (val result = chatEndpoints.processToolCall(access, conversation, tool, arguments)) {
            is SystemChatEndpoints.ToolCallResult.Executed -> result.result
            is SystemChatEndpoints.ToolCallResult.Error -> """{"error": "${result.error}"}"""
            is SystemChatEndpoints.ToolCallResult.WaitingForApproval -> {
                val actionDescription = try {
                    @Suppress("UNCHECKED_CAST")
                    val typedTool = tool as ChatTool<Subject, Any?>
                    val ser = typedTool.koogSerializer(serverRuntime.externalSerialization.json.serializersModule)
                    val args = typedTool.koogArgParse(Json.decodeFromString(ser, arguments))
                    typedTool.describeCall(args).lowercase()
                } catch (e: Exception) {
                    "perform this action"
                }
                """{"status": "pending_approval", "action": "${tool.name}", "message": "I need your permission to $actionDescription. To approve, say exactly the word 'Yes'. To decline, say 'No'."}"""
            }
        }
    }

    //
    // Helper Methods
    //

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
    @Suppress("UNCHECKED_CAST")
    private fun createAuthForSubject(subject: Subject): Authentication<Subject> {
        return Authentication(
            principalType = principalType,
            id = subject._id as ID,
            sessionId = null,
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
            Json.decodeFromString(serializer, jsonValue)
        } catch (e: Exception) {
            try {
                Json.decodeFromString(serializer, idString)
            } catch (e2: Exception) {
                throw IllegalArgumentException(
                    "Cannot parse ID '$idString' for type ${serializer.descriptor.serialName}",
                    e
                )
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

context(runtime: ServerRuntime)
public suspend fun <Subject : HasId<*>, T> ChatTool<Subject, T>.toSerializableDescriptor(
    auth: AuthAccess<Subject>
): SerializableToolDescriptor {
    val koogDescriptor = koogDescriptor(auth)
    return koogDescriptor.toSerializable()
}

public fun ToolDescriptor.toSerializable(): SerializableToolDescriptor {
    return SerializableToolDescriptor(
        name = name,
        description = description,
        requiredParameters = requiredParameters.map { it.toSerializable() },
        optionalParameters = optionalParameters.map { it.toSerializable() },
    )
}

public fun ToolParameterDescriptor.toSerializable(): SerializableToolParameterDescriptor {
    return SerializableToolParameterDescriptor(
        name = name,
        description = description,
        type = type.toSerializable(),
    )
}

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
