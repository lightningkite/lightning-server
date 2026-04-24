# AI Module

The AI module provides intelligent chatbot capabilities for Lightning Server, including LLM integration, database access
through tools, and multi-channel communication (WebSocket, voice, phone, SMS, email).

## Installation

Add the AI module to your dependencies:

```kotlin
dependencies {
    implementation("com.lightningkite.lightningserver:ai:<version>")
}
```

## Architecture Overview

The AI module is built on a layered architecture:

```
┌─────────────────────────────────────────────────────────────┐
│                    Channel Support                           │
│  VoiceChannelSupport  │  ExternalChannelSupport             │
│  (voice, phone)       │  (SMS, email)                       │
├─────────────────────────────────────────────────────────────┤
│                   LLMChatEndpoints                          │
│  (LLM integration, prompt building, tool execution)         │
├─────────────────────────────────────────────────────────────┤
│                  SystemChatEndpoints                        │
│  (conversations, messages, tool approval workflow)          │
├─────────────────────────────────────────────────────────────┤
│                      ChatTool                               │
│  (read tools, write tools, custom tools)                    │
└─────────────────────────────────────────────────────────────┘
```

## Quick Start

### 1. Define Your Chat Endpoints

```kotlin
import com.lightningkite.lightningserver.ai.*
import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.services.ai.koog.LLMClientAndModel
import com.lightningkite.services.ai.koog.LLMClientAndModelSettings

// Define your chat endpoints by extending LLMChatEndpoints
object MyChatBot : LLMChatEndpoints<User>(
    database = Server.database,
    authRequirement = authRequirement<User>(),
    conversationPermissions = {
        // Only allow access to your own conversations
        ModelPermissions.create().also {
            it.create { true }
            it.read { condition { it.subjectId eq auth.rawId.toString() } }
            it.update { condition { it.subjectId eq auth.rawId.toString() } }
            it.delete { condition { it.subjectId eq auth.rawId.toString() } }
        }
    },
    messagePermissions = {
        // Only allow access to messages in your own conversations
        ModelPermissions.create().also {
            it.create { true }
            it.read { condition { it.subjectId eq auth.rawId.toString() } }
            it.update { Condition.Never }
            it.delete { Condition.Never }
        }
    }
) {
    // LLM configuration
    override val defaultLlm = Runtime { Server.llm() }

    // Define tools available to the chatbot
    override val tools: Map<String, ChatTool<User, *>> = buildMap {
        // Add read-only database tools
        Server.usersInfo.readTools(queryLimit = 100).forEach { put(it.name, it) }
        Server.postsInfo.readTools(queryLimit = 100).forEach { put(it.name, it) }
    }

    // System prompt and context
    override suspend fun ServerRuntime.promptPreMessages(
        builder: PromptBuilderAlt,
        auth: AuthAccess<User>,
        conversation: SystemChatConversation
    ) {
        builder.system("""
            You are a helpful assistant with access to user and post data.
            Always be professional and accurate in your responses.
        """.trimIndent())
    }
}

// Include in your server
object Server : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val llm = setting("llm", LLMClientAndModelSettings.openai(
        model = OpenAIModels.Chat.GPT4o,
        apiKey = "\${OPENAI_API_KEY}"
    ))

    val usersInfo = database.modelInfo<User, User, Uuid>(...)
    val postsInfo = database.modelInfo<User, Post, Uuid>(...)

    // Include chat endpoints
    val chat = path.path("chat") include MyChatBot
}
```

### 2. Use the Chat Endpoints

The module automatically provides:

- **REST endpoints** for conversations and messages
- **WebSocket endpoint** for real-time chat
- **Tool approval workflow** for dangerous operations

```
GET  /chat/conversations          - List conversations
POST /chat/conversations          - Create conversation
GET  /chat/conversations/{id}     - Get conversation
GET  /chat/messages               - List messages
POST /chat/messages               - Create message (triggers LLM response)
WS   /chat/simple-chat            - WebSocket for real-time chat
```

## Core Components

### SystemChatEndpoints

Base class providing conversation and message management with tool approval workflow.

Key features:

- Conversation and message tables with REST endpoints
- WebSocket support for real-time message streaming
- Tool execution with dynamic approval workflow
- Per-conversation tool authorization
- Distributed locking for response generation

### LLMChatEndpoints

Extends `SystemChatEndpoints` with LLM integration:

```kotlin
abstract class LLMChatEndpoints<Subject : HasId<*>>(
    database: ServerSetting<Database.Settings, Database>,
    authRequirement: AuthRequirement<Subject>,
    conversationPermissions: ...,
    messagePermissions: ...,
) : SystemChatEndpoints<Subject>(...) {

    // Override these to customize your chatbot:

    abstract val tools: Map<String, ChatTool<Subject, *>>
    abstract val defaultLlm: Runtime<LLMClientAndModel>

    // Customize the prompt
    open suspend fun promptPreMessages(builder, auth, conversation) { }
    open suspend fun promptToolInfoMessages(builder, auth, conversation) { }
    open suspend fun promptPostMessages(builder, auth, conversation) { }

    // Control iteration limits
    open val maxIterations: Int = 50
}
```

### ChatTool

Abstract class for defining tools the LLM can call:

```kotlin
abstract class ChatTool<Subject: HasId<*>?, T> {
    abstract val name: String
    abstract val argsSerializer: KSerializer<T>

    // Description for the LLM
    abstract suspend fun description(auth: AuthAccess<Subject>): TotalExplanation

    // Approval check - determines if user approval is needed
    abstract suspend fun checkApproval(
        auth: AuthAccess<Subject>,
        args: T,
        conversationAuthorizations: Set<ToolAuthorization>
    ): ApprovalRequirement

    // Execute the tool
    abstract suspend fun execute(auth: AuthAccess<Subject>, args: T): String

    // Human-readable description for approval UI
    open fun describeCall(args: T): String = name
}
```

Helper base classes:

- `AutoApprovedTool<Subject, T>` - Never requires approval (for read-only operations)
- `AlwaysRequiresApprovalTool<Subject, T>` - Always requires approval (for writes)

## Database Tools

### Read Tools

Add read-only database access to your chatbot:

```kotlin
override val tools: Map<String, ChatTool<User, *>> = buildMap {
    Server.usersInfo.readTools(queryLimit = 100).forEach { put(it.name, it) }
}
```

This creates 4 tools per table:

| Tool                                                | Description                              |
|-----------------------------------------------------|------------------------------------------|
| `get_{table}_by_id(id)`                             | Get a single record by ID                |
| `count_{table}(condition)`                          | Count records matching condition         |
| `query_{table}(condition, orderBy, skip, limit)`    | Advanced queries with sorting/pagination |
| `aggregate_{table}(aggregate, condition, property)` | Aggregate queries (Sum, Average, etc.)   |

### Write Tools

Add database modification capabilities:

```kotlin
override val tools: Map<String, ChatTool<User, *>> = buildMap {
    Server.usersInfo.readTools(queryLimit = 100).forEach { put(it.name, it) }
    Server.usersInfo.writeTools(
        writeLimit = 10,
        modelExamples = listOf(/* example records for LLM context */)
    ).forEach { put(it.name, it) }
}
```

Write tools always require user approval:

| Tool                                | Description                      |
|-------------------------------------|----------------------------------|
| `insert_{table}(records)`           | Insert records (max: writeLimit) |
| `update_{table}(ids, modification)` | Update records by ID             |
| `delete_{table}(ids)`               | Delete records by ID             |

### Query Syntax

Tools use SQL-like condition expressions:

```sql
-- Simple equality
role = 'admin'

-- Comparisons
age > 18
createdAt >= '2024-01-01'

-- String operations
name ICONTAINS 'john'          -- Case-insensitive contains
email CONTAINS '@example.com'  -- Case-sensitive contains
name MATCHES '^John.*'         -- Regex match

-- Set operations
status IN ('active', 'pending')
role NOT IN ('banned', 'suspended')

-- Logical operators
role = 'admin' AND active = true
status = 'active' OR status = 'pending'
NOT (role = 'guest')
(role = 'admin' OR role = 'moderator') AND active = true
```

## Tool Approval Workflow

Tools can require user approval before execution:

```kotlin
class MyDangerousTool : AlwaysRequiresApprovalTool<User, MyArgs>(
    approvalReason = "This operation modifies critical data"
) {
    override val name = "dangerous_operation"

    override suspend fun execute(auth: AuthAccess<User>, args: MyArgs): String {
        // Implementation
    }
}
```

When a tool requires approval:

1. LLM requests to call the tool
2. A `ToolRequest` message is created with `requiresApproval = true`
3. User is notified via their channel (WebSocket, SMS, email, etc.)
4. User replies with "YES" or "NO"
5. If approved, tool executes and LLM continues

Users can also pre-authorize tools for a conversation:

```http
POST /chat/conversations/{id}/authorize-tool
{
    "toolName": "delete_post",
    "durationSeconds": 3600  // Optional, null = permanent
}
```

## Multi-Channel Support

### WebSocket Chat

Built-in WebSocket endpoint for real-time chat:

```
WS /chat/simple-chat?conversationId={optional}
```

- Automatically creates conversation if none specified
- Streams assistant messages in real-time
- Handles tool approval via "YES"/"NO" messages

### Voice Channel Support

Add real-time voice conversation capabilities:

```kotlin
object MyVoiceSupport : VoiceChannelSupport<User, Uuid>(
    chatEndpoints = MyChatBot,
    authRequirement = authRequirement<User>(),
    principalType = User,
    voiceAgent = Server.voiceAgent,
    pubsub = Server.pubsub,
    phoneCall = Server.phoneCall,  // Optional: for phone integration
    voice = VoiceConfig(name = "alloy"),
    turnDetection = TurnDetection.ServerVAD(),
    resolveSubjectByPhone = { phone ->
        // Resolve user from phone number for incoming calls
        Server.usersTable().findOne(condition { it.phone eq phone.raw })
    },
    voiceInstructions = "Greet the user and ask how you can help.",
    historyMessageLimit = 20,
)

// Include in server
val voice = path.path("voice") include MyVoiceSupport
```

This provides:

- `WS /voice/voice` - Direct voice WebSocket
- `WS /voice/phone/audio/{conversationId}/{subjectId}` - Phone call audio
- `POST /voice/phone/incoming` - Twilio incoming call webhook
- `POST /voice/phone/status` - Call status webhook

### External Channel Support (SMS/Email)

Add SMS and email communication:

```kotlin
object MyExternalChannels : ExternalChannelSupport<User, Uuid>(
    chatEndpoints = MyChatBot,
    principalType = User,
    smsInbound = Server.smsInbound,
    smsOutbound = Server.smsOutbound,
    emailInbound = Server.emailInbound,
    emailOutbound = Server.emailOutbound,
    emailFromAddress = EmailAddressWithName("bot@example.com", "My Bot"),
    resolveSubjectByPhone = { phone ->
        Server.usersTable().findOne(condition { it.phone eq phone.raw })
    },
    resolveSubjectByEmail = { email ->
        Server.usersTable().findOne(condition { it.email eq email.raw })
    },
)

// Include in server
val channels = path.path("channels") include MyExternalChannels
```

When users send SMS or email:

1. System resolves sender to a user
2. Creates or finds existing conversation
3. Inserts user message
4. LLM generates response
5. Response is sent back via same channel

Tool approvals work via reply: "YES" approves, "NO" or "NO: reason" rejects.

## Data Models

### SystemChatConversation

```kotlin
data class SystemChatConversation(
    val _id: Uuid = Uuid.random(),
    val subjectId: String,           // Owner's ID
    val name: String = "",           // Display name
    val autoProcess: Boolean = true, // Auto-trigger LLM responses
    val processingLock: ProcessingLock? = null,
    val toolAuthorizations: List<ToolAuthorization> = emptyList(),
    val summaryUpTo: Instant? = null, // Context compression point
    val createdAt: Instant,
    val updatedAt: Instant = createdAt,
)
```

### SystemChatMessage

```kotlin
data class SystemChatMessage(
    val _id: Uuid = Uuid.random(),
    val conversationId: Uuid,
    val subjectId: String,
    val role: Role,                  // User, Assistant, System, ToolRequest, etc.
    val channel: String? = null,     // "sms", "email", "voice", "phone"
    val externalIdentifier: String? = null,
    val content: String,
    val attachments: List<ServerFile> = emptyList(),
    val createdAt: Instant,
    val tool: ToolRequestData? = null, // For ToolRequest messages
    val skipAutoResponse: Boolean = false,
)

enum class Role {
    User,        // User messages
    Assistant,   // AI responses
    System,      // System prompts
    ToolRequest, // Tool call with approval workflow
    Thinking,    // LLM reasoning (not shown to user)
    Error,       // Error messages
    Summary,     // Compressed conversation history
}
```

### ToolRequestData

```kotlin
data class ToolRequestData(
    val toolName: String,
    val arguments: String,           // JSON arguments
    val requiresApproval: Boolean = false,
    val approvalReason: String? = null,
    val approval: ToolApproval? = null,
    val executionLock: ToolExecutionLock? = null,
    val result: String? = null,
    val error: String? = null,
)
```

## LLM Provider Configuration

### OpenAI

```kotlin
LLMClientAndModelSettings.openai(
    model = OpenAIModels.Chat.GPT4o,
    apiKey = "\${OPENAI_API_KEY}"
)
```

### Anthropic (Claude)

```kotlin
LLMClientAndModelSettings.anthropic(
    model = AnthropicModels.Sonnet_4,
    apiKey = "\${ANTHROPIC_API_KEY}"
)
```

### Google (Gemini)

```kotlin
LLMClientAndModelSettings.google(
    model = GoogleModels.Gemini2_5Pro,
    apiKey = "\${GOOGLE_API_KEY}"
)
```

### Ollama (Local)

```kotlin
LLMClientAndModelSettings("ollama://llama3.2?baseUrl=http://localhost:11434")
```

## Context Compression

Long conversations are automatically compressed to manage token limits:

1. When context exceeds `compressAfter` tokens (default: half of model context)
2. Older messages are summarized into a `Summary` message
3. `conversation.summaryUpTo` is updated
4. Only messages after this point are loaded for LLM context

Override `compressAfter` to customize:

```kotlin
override val compressAfter: Long get() = defaultLlm().model.contextLength / 3
```

## Custom Tools

Create custom tools for your specific use cases:

```kotlin
class WeatherTool : AutoApprovedTool<User, WeatherArgs>() {
    override val name = "get_weather"

    @Serializable
    data class WeatherArgs(
        val city: String,
        @Description("Temperature unit: celsius or fahrenheit")
        val unit: String = "celsius"
    )

    override val argsSerializer = WeatherArgs.serializer()

    override suspend fun description(auth: AuthAccess<User>) = TotalExplanation(
        unique = "Get current weather for a city"
    )

    override suspend fun execute(auth: AuthAccess<User>, args: WeatherArgs): String {
        val weather = weatherService.getWeather(args.city, args.unit)
        return "Weather in ${args.city}: ${weather.temp}° ${args.unit}, ${weather.description}"
    }
}
```

Add to your tools map:

```kotlin
override val tools: Map<String, ChatTool<User, *>> = buildMap {
    put("get_weather", WeatherTool())
    // ... other tools
}
```

## Best Practices

### Security

1. **Validate tool permissions** - Use `AlwaysRequiresApprovalTool` for destructive operations
2. **Limit query results** - Set reasonable `queryLimit` and `writeLimit` values
3. **Authenticate all channels** - External channels create synthetic auth from phone/email
4. **Use per-user conversations** - Enforce `subjectId` checks in permissions

### Performance

1. **Set appropriate limits** - Balance query limits with context token usage
2. **Enable context compression** - Configure `compressAfter` for long conversations
3. **Use `historyMessageLimit`** - Limit message history for voice channels

### User Experience

1. **Customize prompts** - Use `promptPreMessages` for personality and instructions
2. **Override `describeCall`** - Provide clear approval messages
3. **Handle errors gracefully** - LLM will see error messages and can explain to users

## Example: Full Implementation

See `demo/src/main/kotlin/com/lightningkite/lightningserver/demo/Server.kt` for a complete working example.
