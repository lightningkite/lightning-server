# AI Module Usage Examples

## Basic HTTP Endpoint

```kotlin
import com.lightningkite.lightningserver.ai.*
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.database.*
import com.lightningkite.services.ai.koog.LLMClientAndModelSettings
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

// Define your data models
@Serializable
@GenerateDataClassPaths
data class User(
    override val _id: Uuid = Uuid.random(),
    val name: String,
    val email: String,
    val role: String
) : HasId<Uuid>

@Serializable
@GenerateDataClassPaths
data class Post(
    override val _id: Uuid = Uuid.random(),
    val title: String,
    val content: String,
    val authorId: Uuid,
    val publishedAt: Instant
) : HasId<Uuid>

object Server : ServerBuilder() {
    // Database settings
    val database = setting("database", Database.Settings())

    // LLM settings
    val llm = setting(
        "llm",
        LLMClientAndModelSettings.openai(
            model = OpenAIModels.Chat.GPT4o,
            apiKey = "\${OPENAI_API_KEY}"
        )
    )

    // Define ModelInfo for your tables
    val usersInfo = database.modelInfo<Nothing, User, Uuid>(
        auth = noAuth,
        permissions = { ModelPermissions.all() }
    )

    val postsInfo = database.modelInfo<Nothing, Post, Uuid>(
        auth = noAuth,
        permissions = { ModelPermissions.all() }
    )

    // Create a chat endpoint that has access to database tables
    val chatWithData = path.path("api").path("chat").post.api(
        summary = "Chat with AI assistant about your data",
        description = "Ask questions about users and posts in the database",
        authOptions = noAuth,
        errorCases = listOf(),
        implementation = { input: String ->
            Chatbot(llm())
                .withSystemPrompt("""
                    You are a helpful data analyst assistant. You have access to a database
                    with information about users and posts. When asked questions, use the
                    available tools to query the database and provide accurate answers.

                    Always cite specific data from the database when answering questions.
                """.trimIndent())
                .addModelInfo(usersInfo, this)
                .addModelInfo(postsInfo, this)
                .chat(input)
        }
    )

    // Alternative: Simple chatbot without database access
    val simpleChat = path.path("api").path("simple-chat").post.api(
        summary = "Simple chat without database access",
        description = "General purpose chatbot",
        authOptions = noAuth,
        errorCases = listOf(),
        implementation = { input: String ->
            Chatbot(llm())
                .withSystemPrompt("You are a helpful assistant.")
                .chat(input)
        }
    )
}
```

## WebSocket Chat

```kotlin
import com.lightningkite.lightningserver.websocket.*
import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val user: String,
    val text: String
)

@Serializable
data class ChatResponse(
    val bot: String
)

object Server : ServerBuilder() {
    val llm = setting("llm", LLMClientAndModelSettings.openai(
        model = OpenAIModels.Chat.GPT4o,
        apiKey = "\${OPENAI_API_KEY}"
    ))

    val usersInfo = database.modelInfo<Nothing, User, Uuid>(
        auth = noAuth,
        permissions = { ModelPermissions.all() }
    )

    // WebSocket chat endpoint
    val chatWs = path.path("ws").path("chat").webSocket(
        summary = "Real-time chat via WebSocket",
        authOptions = noAuth,
        inputType = ChatMessage.serializer(),
        outputType = ChatResponse.serializer(),
        connect = { user ->
            // Create a chatbot instance for this connection
            val chatbot = Chatbot(llm())
                .withSystemPrompt("You are a helpful assistant.")
                .addModelInfo(usersInfo, user)

            // Store chatbot in connection context if needed
            user.set("chatbot", chatbot)
        },
        message = { user, message ->
            val chatbot = user.get<Chatbot>("chatbot")!!
            val response = chatbot.chat(message.text)
            user.send(ChatResponse(bot = response))
        },
        disconnect = { user ->
            // Cleanup if needed
        }
    )
}
```

## Example Questions

With database access enabled, users can ask:
- "How many users are in the database?"
- "Show me the 5 most recent posts"
- "Find the user with email 'john@example.com'"
- "What roles do we have in the user table?"
- "How many posts were published today?"
- "Find all active administrators"
- "Show me users with admin or moderator roles"

The chatbot will automatically call the appropriate database tools to answer these questions.

## Settings Configuration

### settings.json
```json
{
  "database": "json-file://./local/data",
  "llm": "openai://gpt-4o?apiKey=${OPENAI_API_KEY}"
}
```

### Environment Variables
Set `OPENAI_API_KEY` in your environment or use it in the settings URL.

## Available LLM Providers

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

## Generated Database Tools

For each ModelInfo you add, the chatbot gets **4 tools**:

1. **`count_{table}()`** - Count total records
2. **`get_{table}_by_id(id: String)`** - Get a specific record
3. **`list_{table}(limit: Int)`** - List recent records
4. **`query_{table}(condition: String, limit: Int?, sortBy: String?, descending: Boolean?)`** - Advanced queries using Condition format

The LLM automatically chooses which tools to use based on the user's question.
