package com.lightningkite.lightningserver.ai

/**
 * Example usage of the AI module with chatbots.
 *
 * ## Basic Chatbot Setup
 *
 * ```kotlin
 * import com.lightningkite.lightningserver.ai.ChatbotSettings
 * import ai.koog.prompt.executor.clients.openai.OpenAIModels
 * import com.lightningkite.lightningserver.definition.builder.ServerBuilder
 *
 * object Server : ServerBuilder() {
 *     val chatbot = setting(
 *         "chatbot",
 *         ChatbotSettings.openai(
 *             model = OpenAIModels.Chat.GPT4o,
 *             apiKey = "\${OPENAI_API_KEY}"
 *         )
 *     )
 *
 *     val chatEndpoint = path.path("api").path("chat").post.api(
 *         summary = "Chat with AI assistant",
 *         description = "Send a message to the AI assistant and get a response",
 *         authOptions = noAuth,
 *         errorCases = listOf(),
 *         implementation = { input: String ->
 *             val bot = chatbot()
 *             bot.chat(input)
 *         }
 *     )
 * }
 * ```
 *
 * ## Configuration Options
 *
 * ### OpenAI
 * ```kotlin
 * ChatbotSettings.openai(
 *     model = OpenAIModels.Chat.GPT4o,
 *     apiKey = "\${OPENAI_API_KEY}"
 * )
 * ```
 *
 * ### Anthropic (Claude)
 * ```kotlin
 * ChatbotSettings.anthropic(
 *     model = AnthropicModels.Sonnet_4,
 *     apiKey = "\${ANTHROPIC_API_KEY}"
 * )
 * ```
 *
 * ### Google (Gemini)
 * ```kotlin
 * ChatbotSettings.google(
 *     model = GoogleModels.Gemini2_5Pro,
 *     apiKey = "\${GOOGLE_API_KEY}"
 * )
 * ```
 *
 * ### Ollama (Local Models)
 * ```kotlin
 * ChatbotSettings.ollama(
 *     model = OllamaModels.Meta.LLAMA_3_2,
 *     baseUrl = "http://localhost:11434"
 * )
 * ```
 *
 * ## Advanced: Database Access Tools
 *
 * Give your chatbot read-only access to database tables:
 *
 * ```kotlin
 * // In your endpoint implementation
 * val bot = chatbot()
 *     .withSystemPrompt("You are a data analyst assistant.")
 *     .addModelInfo(Server.usersInfo, runtime)
 *     .addModelInfo(Server.postsInfo, runtime)
 *
 * // The chatbot can now answer questions about your data
 * val response = bot.chat("How many active users do we have?")
 * // The chatbot will automatically call the count_user tool
 *
 * // Or you can manually use DatabaseTableTool
 * val userTableTool = DatabaseTableTool(Server.usersInfo, runtime)
 * val count = userTableTool.count()
 * val user = userTableTool.findById(userId)
 * val recentUsers = userTableTool.listRecent(limit = 5)
 * ```
 *
 * ## Settings File Configuration
 *
 * In your `settings.json`:
 * ```json
 * {
 *   "chatbot": "openai://gpt-4o?apiKey=${OPENAI_API_KEY}"
 * }
 * ```
 *
 * Or using environment variables directly:
 * - Set `OPENAI_API_KEY` in your environment
 * - The chatbot will automatically use it if not specified in the URL
 */
public object Example
