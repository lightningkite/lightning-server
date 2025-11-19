package com.lightningkite.lightningserver.ai

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.strategy.node
import ai.koog.agents.core.agent.strategy.strategy
import ai.koog.agents.core.config.AIAgentConfig
import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.core.appendPrompt
import ai.koog.prompt.core.prompt.assistant
import ai.koog.prompt.core.prompt.system
import ai.koog.prompt.core.prompt.user
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import ai.koog.prompt.executor.requestLLM
import ai.koog.prompt.executor.writeSession
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.ai.koog.LLMClientAndModel
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.SerializableProperty
import com.lightningkite.services.database.Table
import com.lightningkite.services.database.serializableProperties
import kotlinx.coroutines.flow.toList
import kotlin.uuid.Uuid

/**
 * An immutable chatbot configuration with LLM and tool access.
 *
 * The chatbot maintains conversation history in the database and uses tools
 * (such as database query tools) to answer questions.
 *
 * Example usage:
 * ```kotlin
 * val chatbot = Chatbot(
 *     llmClientAndModel = llm,
 *     tools = createModelInfoTools(usersInfo, runtime),
 *     systemPrompt = "You are a helpful assistant."
 * )
 *
 * // Later, in a context with ServerRuntime:
 * val response = with(runtime) {
 *     chatbot.chat(conversationId, "How many users do we have?")
 * }
 * ```
 *
 * @property llmClientAndModel Runtime provider for the LLM client and model
 * @property tools List of tools the chatbot can use
 * @property systemPrompt System prompt that guides the chatbot's behavior
 */
public class Chatbot(
    public val llmClientAndModel: Runtime<LLMClientAndModel>,
    public val tools: List<SimpleTool<*>> = emptyList(),
    public val systemPrompt: String = "You are a helpful assistant with access to database information.",
) {
    /**
     * Sends a message to the chatbot and gets a response.
     *
     * This function loads the conversation history from the database, sends the message
     * to the LLM with access to tools, and stores the new messages back to the database.
     *
     * @param conversationId The unique ID for this conversation
     * @param message The user's message
     * @param conversationTable The database table storing conversation history
     * @return The chatbot's response
     */
    context(runtime: ServerRuntime)
    public suspend fun chat(
        conversationId: Uuid,
        message: String,
        conversationTable: Table<ConversationMessage>
    ): String {
        // Load conversation history
        val conversationIdProp = ConversationMessage.serializer().serializableProperties!!
            .first { it.name == "conversationId" }
        @Suppress("UNCHECKED_CAST")
        val history = conversationTable.find(
            Condition.OnField(
                conversationIdProp as SerializableProperty<ConversationMessage, Uuid>,
                Condition.Equal(conversationId)
            )
        ).toList().sortedBy { it.timestamp }

        // Create tool registry
        val toolRegistry = ToolRegistry {
            tools.forEach { tool(it) }
        }

        // Create strategy that manages conversation history using sessions
        val chatStrategy = strategy("chat-with-history") {
            val chatNode by node<String, String> { userMessage ->
                // Within node context, we have access to llm
                llm.writeSession {
                    // Add conversation history to the prompt
                    appendPrompt {
                        system(systemPrompt)
                        history.forEach { msg ->
                            when (msg.role) {
                                ConversationMessage.Role.User -> user(msg.content)
                                ConversationMessage.Role.Assistant -> assistant(msg.content)
                                ConversationMessage.Role.System -> system(msg.content)
                            }
                        }
                        user(userMessage) // Add the new user message
                    }

                    // Request LLM response with tools
                    val response = requestLLM()
                    response.text
                }
            }

            // Simple linear flow: start -> chat -> finish
            edge(nodeStart forwardTo chatNode)
            edge(chatNode forwardTo nodeFinish)
        }

        // Create agent with strategy
        val llm = llmClientAndModel()
        val agentConfig = AIAgentConfig(
            model = llm.model,
            maxAgentIterations = 10
        )

        val agent = AIAgent(
            promptExecutor = SingleLLMPromptExecutor(llm.client),
            toolRegistry = toolRegistry,
            strategy = chatStrategy,
            agentConfig = agentConfig
        )

        // Store the user's message
        val userMessage = ConversationMessage(
            conversationId = conversationId,
            role = ConversationMessage.Role.User,
            content = message
        )
        conversationTable.insert(listOf(userMessage))

        // Run the agent with full conversation history
        val response = agent.run(message)

        // Store the assistant's response
        val assistantMessage = ConversationMessage(
            conversationId = conversationId,
            role = ConversationMessage.Role.Assistant,
            content = response
        )
        conversationTable.insert(listOf(assistantMessage))

        return response
    }
}

