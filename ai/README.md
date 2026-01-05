# Lightning Server AI Module

AI-powered chatbot capabilities with LLM integration, database access, multi-channel support (WebSocket, voice, phone, SMS, email), and tool approval workflows.

**Full Documentation**: See [docs/ai.md](../docs/ai.md)

## Features

- **LLM Integration**: OpenAI, Anthropic, Google, Ollama via Koog
- **Database Tools**: Read/write access to tables via ModelInfo
- **Multi-Channel**: WebSocket, real-time voice, phone calls, SMS, email
- **Tool Approval Workflow**: User confirmation for dangerous operations
- **Context Compression**: Automatic summarization for long conversations
- **Type-Safe**: Fully type-safe configuration and tooling

## Quick Start

```kotlin
// 1. Define your chat endpoints
object MyChatBot : LLMChatEndpoints<User>(
    database = Server.database,
    authRequirement = authRequirement<User>(),
    conversationPermissions = { ... },
    messagePermissions = { ... }
) {
    override val defaultLlm = Runtime { Server.llm() }

    override val tools: Map<String, ChatTool<User, *>> = buildMap {
        Server.usersInfo.readTools(100).forEach { put(it.name, it) }
        Server.postsInfo.readTools(100).forEach { put(it.name, it) }
    }

    override suspend fun ServerRuntime.promptPreMessages(
        builder: PromptBuilderAlt,
        auth: AuthAccess<User>,
        conversation: SystemChatConversation
    ) {
        builder.system("You are a helpful data assistant.")
    }
}

// 2. Include in your server
val chat = path.path("chat") include MyChatBot
```

This provides:
- REST endpoints for conversations and messages
- WebSocket for real-time chat
- Automatic tool execution with approval workflow

## Database Tools

```kotlin
// Read tools (auto-approved)
Server.usersInfo.readTools(queryLimit = 100)
// Creates: get_user_by_id, count_user, query_user, aggregate_user

// Write tools (require approval)
Server.usersInfo.writeTools(writeLimit = 10, modelExamples = listOf(...))
// Creates: insert_user, update_user, delete_user
```

## LLM Providers

```kotlin
// OpenAI
LLMClientAndModelSettings.openai(model = OpenAIModels.Chat.GPT4o, apiKey = "...")

// Anthropic
LLMClientAndModelSettings.anthropic(model = AnthropicModels.Sonnet_4, apiKey = "...")

// Google
LLMClientAndModelSettings.google(model = GoogleModels.Gemini2_5Pro, apiKey = "...")

// Ollama (local)
LLMClientAndModelSettings("ollama://llama3.2?baseUrl=http://localhost:11434")
```

## Multi-Channel Support

### Voice & Phone

```kotlin
val voice = path.path("voice") include VoiceChannelSupport(
    chatEndpoints = MyChatBot,
    voiceAgent = Server.voiceAgent,
    pubsub = Server.pubsub,
    phoneCall = Server.phoneCall,  // Optional
    voice = VoiceConfig(name = "alloy"),
    resolveSubjectByPhone = { phone -> ... },
)
```

### SMS & Email

```kotlin
val channels = path.path("channels") include ExternalChannelSupport(
    chatEndpoints = MyChatBot,
    smsInbound = Server.smsInbound,
    smsOutbound = Server.smsOutbound,
    emailInbound = Server.emailInbound,
    emailOutbound = Server.emailOutbound,
    resolveSubjectByPhone = { phone -> ... },
    resolveSubjectByEmail = { email -> ... },
)
```

## Documentation

- **Full Guide**: [docs/ai.md](../docs/ai.md)
- **Usage Examples**: [docs/USAGE_EXAMPLES.md](docs/USAGE_EXAMPLES.md)

## Dependencies

- `serviceAbstractionsAiKoog` - Koog AI library
- `typed` - Lightning Server typed endpoints and ModelInfo

