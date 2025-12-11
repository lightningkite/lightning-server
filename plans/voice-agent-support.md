# Voice Agent Support for SystemChatEndpoints

## Overview

Add voice agent capabilities to any `SystemChatEndpoints` implementation (including `LLMChatEndpoints`) using the same composition pattern as `ExternalChannelSupport`. This enables:
- Real-time voice conversations using OpenAI Realtime API
- Phone call integration via Twilio audio streaming
- Reuse of existing `ChatTool` infrastructure for tool calling
- Works with both `SystemChatEndpoints` and `LLMChatEndpoints`

## Dependencies Added

### libs.versions.toml

```toml
serviceAbstractionsVoiceagent = { module = "com.lightningkite.services:voiceagent", version = { ref = "serviceAbstractions" } }
serviceAbstractionsVoiceagentOpenai = { module = "com.lightningkite.services:voiceagent-openai", version = { ref = "serviceAbstractions" } }
serviceAbstractionsVoiceagentPhonecall = { module = "com.lightningkite.services:voiceagent-phonecall", version = { ref = "serviceAbstractions" } }
```

### ai/build.gradle.kts

```kotlin
// Voice agent support
api(libs.serviceAbstractionsVoiceagent)
api(libs.serviceAbstractionsVoiceagentOpenai)
api(libs.serviceAbstractionsVoiceagentPhonecall)
api(libs.serviceAbstractionsPhonecall)
api(libs.serviceAbstractionsPubsub)
```

## Architecture

### Pattern: Composition (following ExternalChannelSupport)

Just like `ExternalChannelSupport` adds SMS/Email channels to any `SystemChatEndpoints`, `VoiceChannelSupport` adds voice channels:

```kotlin
class VoiceChannelSupport<Subject : HasId<ID>, ID : Comparable<ID>>(
    private val chatEndpoints: SystemChatEndpoints<Subject>,
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

    // Tool resolution - needed because voice agent uses SerializableToolDescriptor format
    private val resolveTools: (suspend context(ServerRuntime) (AuthAccess<Subject>, SystemChatConversation) -> List<ChatTool<Subject, *>>),

    // Subject resolution for phone calls
    private val resolveSubjectByPhone: (suspend context(ServerRuntime) (PhoneNumber) -> Subject?)? = null,
) : ServerBuilder()
```

### How It Works

1. **WebSocket Voice Endpoint** (`/voice`)
   - Client connects with auth token and optional `conversationId`
   - Creates `VoiceAgentSession` with configured instructions
   - Bidirectional audio streaming
   - Tool calls → `chatEndpoints.processToolCall()` → results back to voice agent
   - Transcriptions → stored as `SystemChatMessage` via `chatEndpoints.messageInfo`

2. **Phone Call Integration** (optional)
   - Incoming call webhook → TwiML to connect audio stream
   - Audio WebSocket → `PubSubVoiceAgentHandler` bridges to voice agent
   - Uses same tool handling and message storage

3. **Message Flow**
   ```
   Voice Input → VoiceAgentSession → InputTranscription event
                                          ↓
                            chatEndpoints.messageInfo.insertOne(User message)

   Voice Agent Response → TextDone event
                              ↓
                chatEndpoints.messageInfo.insertOne(Assistant message)

   Tool Call → ToolCallDone event
                    ↓
        chatEndpoints.processToolCall()
                    ↓
        session.sendToolResult()
   ```

4. **Channel Identifier**
   - Voice messages use `channel = "voice"`
   - Phone messages use `channel = "phone"` with `externalIdentifier = phoneNumber`

## Implementation

### VoiceChannelSupport Class

```kotlin
public class VoiceChannelSupport<Subject : HasId<ID>, ID : Comparable<ID>>(
    private val chatEndpoints: SystemChatEndpoints<Subject>,
    private val principalType: PrincipalType<Subject, ID>,

    private val voiceAgent: ServerSetting<VoiceAgentService.Settings, VoiceAgentService>,
    private val pubsub: ServerSetting<PubSub.Settings, PubSub>,
    private val phoneCall: ServerSetting<PhoneCallService.Settings, PhoneCallService>? = null,

    private val voiceInstructions: String,
    private val voice: VoiceConfig = VoiceConfig(name = "alloy"),
    private val turnDetection: TurnDetection = TurnDetection.ServerVAD(),

    private val resolveTools: suspend context(ServerRuntime) (AuthAccess<Subject>, SystemChatConversation) -> List<ChatTool<Subject, *>>,
    private val resolveSubjectByPhone: (suspend context(ServerRuntime) (PhoneNumber) -> Subject?)? = null,
) : ServerBuilder() {

    //
    // Voice WebSocket (direct client connection)
    //

    @Serializable
    data class VoiceWebSocketState(
        val conversationId: Uuid,
        val subjectId: String,
        val connectionId: String = Uuid.random().toString(),
    )

    val voiceWebSocket = path.path("voice") bind WebSocketHandler(
        willConnect = { request ->
            // Authenticate and setup conversation
            val auth = request.auth(chatEndpoints.authRequirement)
                ?: throw UnauthorizedException()
            val access = AuthAccess(auth)

            val conversationId = request.queryParameters["conversationId"]
                ?.let { Uuid.parse(it) }

            val conversation = if (conversationId != null) {
                chatEndpoints.conversationInfo.table(access).get(conversationId)
                    ?: throw NotFoundException("Conversation not found")
            } else {
                chatEndpoints.conversationInfo.table(access).insertOne(
                    SystemChatConversation(subjectId = auth.rawId.toString(), createdAt = now())
                )!!
            }

            VoiceWebSocketState(conversation._id, auth.rawId.toString())
        },

        didConnect = {
            // Create voice session and start handling
            val handler = createVoiceHandler(currentState)
            handler.onConnect(currentState) { send(it) }
        },

        messageFromClient = { frame ->
            // Route audio to voice session
            handler.onMessage(currentState, frame.text)
        },

        disconnect = {
            handler.onDisconnect(currentState)
        }
    )

    //
    // Phone Call Integration (optional)
    //

    val phoneWebhooks = phoneCall?.let { phoneCallSetting ->
        path.path("phone") module object : ServerBuilder() {
            val incoming = path.path("incoming").post bind HttpHandler {
                // Parse incoming call, return TwiML to connect to audio stream
            }

            val status = path.path("status").post bind HttpHandler {
                // Track call status
            }

            val audioStream = path.path("audio") bind WebSocketHandler(
                // PubSubVoiceAgentHandler for Lambda-compatible audio streaming
            )
        }
    }

    //
    // Tool bridging
    //

    private fun chatToolsToSerializable(
        tools: List<ChatTool<Subject, *>>,
        module: SerializersModule
    ): List<SerializableToolDescriptor> {
        return tools.map { tool ->
            tool.koogDescriptor(module).toSerializable()
        }
    }

    private suspend fun executeVoiceTool(
        auth: AuthAccess<Subject>,
        conversation: SystemChatConversation,
        toolName: String,
        arguments: String
    ): String {
        val tools = resolveTools(auth, conversation)
        val tool = tools.find { it.name == toolName }
            ?: return """{"error": "Tool '$toolName' not found"}"""

        // Use chatEndpoints.processToolCall for approval workflow
        return when (val result = chatEndpoints.processToolCall(auth, conversation, tool, arguments)) {
            is ToolCallResult.Executed -> result.result
            is ToolCallResult.Error -> """{"error": "${result.error}"}"""
            is ToolCallResult.WaitingForApproval -> {
                // Voice-specific handling - could prompt user verbally
                """{"status": "pending_approval", "message": "This action requires your approval. Please confirm."}"""
            }
        }
    }
}
```

### Key Differences from ExternalChannelSupport

| Aspect | ExternalChannelSupport | VoiceChannelSupport |
|--------|------------------------|---------------------|
| Input | Text (SMS/Email body) | Audio stream |
| Output | Text (SMS/Email) | Audio stream |
| Real-time | No (webhook-based) | Yes (WebSocket) |
| Tool Format | Uses chatEndpoints directly | Needs ChatTool → SerializableToolDescriptor conversion |
| State | Stateless per message | Stateful session |
| Response | Via message listener | Direct to voice session |

### Voice Tool Descriptor Conversion

Need to convert `ChatTool.koogDescriptor()` (Koog's `ToolDescriptor`) to `SerializableToolDescriptor`:

```kotlin
fun ToolDescriptor.toSerializable(): SerializableToolDescriptor {
    return SerializableToolDescriptor(
        name = name,
        description = description,
        requiredParameters = parameters.filter { it.required }.map { it.toSerializable() },
        optionalParameters = parameters.filter { !it.required }.map { it.toSerializable() },
    )
}
```

## Usage Example

```kotlin
// Define your chat bot
object BlogAssistantChat : LLMChatEndpoints<AuthUser>(...) {
    override val tools = mapOf(
        "list_posts" to listPostsTool,
        "create_post" to createPostTool,
    )
}

// Add voice support via composition
object BlogAssistantVoice : VoiceChannelSupport<AuthUser, Uuid>(
    chatEndpoints = BlogAssistantChat,
    principalType = AuthUser,

    voiceAgent = Server.voiceAgent,
    pubsub = Server.pubsub,
    phoneCall = Server.phoneCall, // Optional

    voiceInstructions = """
        You are a helpful blog assistant. Help users manage their blog posts.
        Be conversational and concise since this is a voice interface.
    """.trimIndent(),

    resolveTools = { auth, conversation ->
        listOf(listPostsTool, createPostTool)
    },

    resolveSubjectByPhone = { phone ->
        userTable().findOne(condition { it.phone eq phone.raw })
    },
)

// Include both in your server
object Server : ServerBuilder() {
    val voiceAgent = setting("voiceAgent", VoiceAgentService.Settings())
    val pubsub = setting("pubsub", PubSub.Settings())
    val phoneCall = setting("phoneCall", PhoneCallService.Settings())

    val chat = path.path("chat") include BlogAssistantChat
    val chatChannels = path.path("chat") include BlogAssistantChannels // SMS/Email
    val chatVoice = path.path("chat") include BlogAssistantVoice // Voice/Phone
}
```

## Settings Example

```json
{
  "voiceAgent": {
    "url": "openai-realtime://gpt-4o-realtime-preview?apiKey=${OPENAI_API_KEY}"
  },
  "pubsub": {
    "url": "redis://localhost:6379"
  },
  "phoneCall": {
    "url": "twilio://ACCOUNT_SID:AUTH_TOKEN@+1XXXXXXXXXX"
  }
}
```

## Key Considerations

### 1. Tool Approval in Voice Context
- Voice is real-time, so approval workflow needs adaptation
- Options:
  a. Auto-approve read operations (same as text chat)
  b. For write operations, voice agent asks "Are you sure?" and waits for verbal confirmation
  c. Return pending status and let voice agent explain the hold

### 2. Transcript Storage
- User speech → `SystemChatMessage` with `role = User`, `channel = "voice"`
- Agent responses → `SystemChatMessage` with `role = Assistant`
- Enables conversation history continuity between voice and text

### 3. Conversation Continuity
- Voice sessions can reference existing text conversations
- Load history via `chatEndpoints.getConversationHistory()`
- Inject context via `session.addMessage()`

### 4. Session State Management
- Direct WebSocket: state managed in memory during connection
- Phone/Lambda: `PubSubVoiceAgentHandler` uses PubSub for cross-instance state

### 5. Error Handling
- Provide audio feedback for errors
- Don't leave user in silence - say something went wrong

### 6. Cost Tracking
- OpenAI Realtime API charges per minute
- Track `UsageStats` from `VoiceAgentEvent.ResponseDone`
