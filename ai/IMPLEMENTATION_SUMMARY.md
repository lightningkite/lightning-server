# AI Module Implementation Summary

## Overview

The AI module for Lightning Server provides chatbot capabilities with intelligent database access through ModelInfo. The chatbots can query database tables to answer questions about your data using natural language.

## ✅ Completed Features

### 1. Core Infrastructure

**Files Created:**
- `ChatbotSettings.kt` - Settings wrapper for LLM configuration
- `Chatbot.kt` - Main chatbot class with tool integration
- `DatabaseTableTool.kt` - Utility for read-only table access
- `ModelInfoTools.kt` - Tool creation for database tables

### 2. Advanced Query System

**Implementation Approach:**
Uses Lightning Server's built-in `Condition<T>` serialization format directly, avoiding custom query parsing. The Condition format is:
- More concise (fewer tokens for LLMs)
- Type-safe with automatic deserialization
- Supports all Condition operators automatically
- Already tested and maintained

**Supported Query Operations:**
All Lightning Server Condition operators are supported:
- **Comparisons**: Equal, NotEqual, GreaterThan, LessThan, GreaterThanOrEqual, LessThanOrEqual
- **String Operations**: StringContains, RegexMatches
- **Set Operations**: Inside, NotInside
- **Logic**: And, Or for combining conditions
- **Special**: Always (match all), Never (match none), Not (negation)
- **Sorting**: Optional sortBy field with ascending/descending
- **Pagination**: limit parameter (max 100)

**Example Queries:**
```json
// Simple equality
{"status": {"Equal": "active"}}

// Greater than comparison
{"viewCount": {"GreaterThan": 100}}

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

// String contains (case-insensitive)
{"name": {"StringContains": {"value": "John", "ignoreCase": true}}}
```

### 3. Database Tools Per Table

When you add a ModelInfo to a chatbot, it gets **4 tools**:

1. `get_{table}_by_id(id: ID)` - Get a single record by ID
2. `count_{table}(condition: Condition)` - Count records in the table that match the condition
3. `query_{table}(condition: Condition, orderBy: List<SortPart>, skip: Int, limit: Int)` - Advanced queries
4. `aggregate_query_{table}(aggregate: Aggregate, condition: Condition, property: DataClassPathPartial)` - Aggregate Queries

### 4. How It Works

```
User Question: "How many active admins do we have?"
      ↓
LLM analyzes question
      ↓
LLM decides to call: query_user(...)
      ↓
Tool receives Condition JSON:
{
  "And": [
    {"role": {"Equal": "admin"}},
    {"active": {"Equal": true}}
  ]
}
      ↓
Direct deserialization to Condition<T>
      ↓
Table.find(condition) executes
      ↓
Results returned as JSON to LLM
      ↓
LLM formulates natural language response:
"There are 5 active administrators in the system."
```

### 5. Safety Features

- **Read-only access**: Tools can only query, not modify data
- **Type-safe deserialization**: kotlinx.serialization validates Condition structure
- **Field validation**: Field names validated via SerializableProperty lookup
- **Injection prevention**: Typed Condition format prevents SQL/NoSQL injection
- **Limit enforcement**: Query results are capped at the provided query limit
- **Error handling**: Invalid Conditions return clear error messages to LLM

## Usage Examples

### Basic Setup

```kotlin
object Server : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val chatbot = setting("chatbot", ChatbotSettings.openai(
        model = OpenAIModels.Chat.GPT4o,
        apiKey = "\${OPENAI_API_KEY}"
    ))

    val usersInfo = database.modelInfo<User>(...)
    val postsInfo = database.modelInfo<Post>(...)

    val chatEndpoint = path.path("chat").post.api(
        summary = "Chat about data",
        implementation = { input: String ->
            chatbot()
                .addModelInfo(usersInfo, this)
                .addModelInfo(postsInfo, this)
                .withSystemPrompt("You are a data analyst.")
                .chat(input)
        }
    )
}
```

### Example Conversations

**Count Query:**
```
User: "How many users do we have?"
Bot: "There are 42 users in the system."
```

**Filtered Query:**
```
User: "Show me the 5 most popular posts"
Bot: "Here are the 5 most popular posts:
1. 'Getting Started' - 1,543 views
2. 'Advanced Features' - 892 views
..."
```

**Complex Query:**
```
User: "Find all active admin users"
Bot: "I found 3 active administrators:
- Alice Johnson (alice@example.com)
- Bob Smith (bob@example.com)
- Carol Williams (carol@example.com)"
```

## Architecture

### Query Flow

1. **User Input** → Chatbot.chat()
2. **LLM Analysis** → Determines which tool(s) to call
3. **Tool Execution** → QueryTableTool.doExecute()
4. **Database Query** → Table.find(condition)
5. **Result Formatting** → JSON serialization
6. **LLM Response** → Natural language answer

### Type Safety

The system maintains type safety throughout:
- JSON → `Condition<T>` (direct deserialization)
- `Condition<T>` → Database query (backend-specific)
- Results → `T` (your model type)
- `T` → JSON (for LLM)

##  Testing

### Integration Tests

`ChatbotIntegrationTest.kt` includes:
- Basic chat without tools (tests LLM connectivity)
- Chat with database tools (tests full integration) - TODO

**Running Tests:**
```bash
# Place API key in local/openaikey.txt
echo "sk-..." > local/openaikey.txt

# Run tests
./gradlew :ai:test
```

Tests are skipped if no API key is found.

##  Dependencies

- `serviceAbstractionsAiKoog` - Koog AI framework
- `serviceAbstractionsAiKoogAwsOpensearch` - AWS OpenSearch (future RAG)
- `typed` - Lightning Server ModelInfo and typed endpoints
- `database` - Database abstraction layer

##  Future Enhancements

### Planned Features

1. **RAG (Retrieval-Augmented Generation)**:
   - Vector embeddings for semantic search
   - AWS OpenSearch integration
   - Document storage and retrieval
2. **Streaming responses**: Real-time token streaming
3. **Conversation memory**: Multi-turn conversations with context
4. **Audit logging**: Track what data the chatbot accessed

### Possible Improvements

- Query result caching
- Rate limiting per user/session
- Custom system prompts per table

