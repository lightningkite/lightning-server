# Lightning Server AI Module

This module provides AI capabilities for Lightning Server, including chatbots with database access through ModelInfo.

## Features

- **LLM Integration**: Support for OpenAI, Anthropic, Google, Ollama, and other LLM providers via Koog
- **Database Tools**: Give chatbots read and or write access to database tables through ModelInfo
- **Type-Safe**: Fully type-safe chatbot configuration

## Installation

Add the `ai` module to your Lightning Server project dependencies:

```kotlin
dependencies {
    implementation("com.lightningkite.lightningserver:ai:<Lastest_Version>")
}
```

## Basic Usage

### Creating a Simple Chatbot

```kotlin
import com.lightningkite.lightningserver.ai.Chatbot
import com.lightningkite.services.ai.koog.LLMClientAndModelSettings
import ai.koog.prompt.executor.clients.openai.OpenAIModels

object Server : ServerBuilder() {
    val llm = setting(
        "llm",
        LLMClientAndModelSettings.openai(
            model = OpenAIModels.Chat.GPT4o,
            apiKey = "\${OPENAI_API_KEY}"
        )
    )

    val chatEndpoint = path.path("chat").post bind ApiHttpHandler(
        summary = "Chat with AI",
        authOptions = noAuth,
        errorCases = listOf(),
        implementation = { input: String ->
            Chatbot(llm()).chat(input)
        }
    )
}
```

### Chatbot with Database Access

Give your chatbot read-only access to database tables through ModelInfo:

```kotlin
val chatEndpoint = path.path("chat").post.api(
    summary = "Chat about database",
    authOptions = noAuth,
    errorCases = listOf(),
    implementation = { input: String ->
        Chatbot(llm())
            .withSystemPrompt("You are a helpful assistant with access to user and post data.")
            .addModelInfo(usersInfo, this, 100, serverRuntime)
            .addModelInfo(postsInfo, this, 100, serverRuntime)
            .chat(input)
    }
)
// When user asks "How many users do we have?", the chatbot will automatically call the count_user tool
```

Each read ModelInfo you add provides four tools:
- `get_{table}_by_id(id: ID)` - Get a single record by ID
- `count_{table}(condition: Condition)` - Count records in the table that match the condition
- `query_{table}(condition: Condition, orderBy: List<SortPart>, skip: Int, limit: Int)` - Advanced queries
- `aggregate_query_{table}(aggregate: Aggregate, condition: Condition, property: DataClassPathPartial)` - Aggregate Queries

The chatbot automatically chooses which tools to use based on the user's question.

### Advanced Queries

The `query_` tool uses Lightning Server's Condition serialization format:

```json
// Simple equality
{"status": {"Equal": "active"}}

// Greater than
{"age": {"GreaterThan": 18}}

// Multiple conditions with AND
{"And": [
  {"role": {"Equal": "admin"}},
  {"active": {"Equal": true}}
]}

// Complex nested query
{"And": [
  {"Or": [
    {"role": {"Equal": "admin"}},
    {"role": {"Equal": "moderator"}}
  ]},
  {"active": {"Equal": true}}
]}
```

Available operators: `Equal`, `NotEqual`, `GreaterThan`, `LessThan`, `GreaterThanOrEqual`, `LessThanOrEqual`, `Inside`, `NotInside`, `StringContains`, `RegexMatches`.

The LLM can generate these queries to answer complex questions like "Show me all active administrators" or "Find posts with more than 100 views".

### Chatbot with Write Capabilities

**⚠️ WARNING**: This gives the LLM the ability to INSERT, UPDATE, and DELETE data. Only use with trusted LLMs and appropriate safeguards!

```kotlin
val adminEndpoint = path.path("admin-chat").post.api(
    summary = "Admin chat with database modification rights",
    authOptions = authOptions<AdminUser>(), // Require authentication!
    errorCases = listOf(),
    implementation = { input: String ->
        Chatbot(llm())
            .withSystemPrompt("""
                You are an admin assistant with database modification capabilities.
                IMPORTANT: Always confirm with the user before making any changes.
                Be extremely careful with delete and update operations.
            """.trimIndent())
            .addModelInfo(usersInfo, this, serverRuntime)
            .addModelInfoWithWrites(usersInfo, this, 100, listOf(/*Put model examples in here*/), serverRuntime) // Write-capable!
            .chat(input)
    }
)
```

Write-capable chatbots get **3 minimal tools per table**:
- `insert_{table}(records)` - Insert a set of records
- `update_{table}(ids, Modification)` - Update a set of records by _id
- `delete_{table}(ids)` - Delete a set of records by _id

Example interactions:
- User: "Add a new user named John with email john@example.com"
- User: "Update all draft posts to published status"
- User: "Delete inactive users who haven't logged in since 2020"

The LLM will automatically use the appropriate tools and provide confirmation.

## Configuration

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

### Ollama (Local Models)

```kotlin
LLMClientAndModelSettings("ollama://llama3.2?baseUrl=http://localhost:11434")
```

## Additional Examples

See [docs/USAGE_EXAMPLES.md](docs/USAGE_EXAMPLES.md) for:
- Complete HTTP endpoint examples
- WebSocket chat implementation
- Multi-provider LLM configuration

## Dependencies

This module uses:
- `serviceAbstractionsAiKoog` - Koog AI library for LLM interactions
- `serviceAbstractionsAiKoogAwsOpensearch` - AWS OpenSearch integration for RAG (future)
- `typed` - Lightning Server typed endpoints and ModelInfo

## Roadmap

- [x] Basic chatbot settings and configuration
- [x] LLM provider integration (OpenAI, Anthropic, Google, Ollama)
- [x] ModelInfo tool descriptor implementation
- [x] Read-only database query tools (count, get by ID, query, aggregate)
- [x] Advanced query tools using Condition serialization
  - [x] All Condition operators (Equal, GreaterThan, LessThan, StringContains, etc.)
  - [x] Multiple conditions with And/Or logic
  - [x] Optional sorting and pagination
  - [x] Direct Condition deserialization
- [x] Limited database write tools (Insert, Update, Delete)
  - [x] Hard limits to the amount of changes that can be made at once
- [ ] RAG (Retrieval-Augmented Generation) support
- [ ] Vector storage integration for semantic search
- [ ] Streaming chat responses
- [ ] Conversation history/memory

