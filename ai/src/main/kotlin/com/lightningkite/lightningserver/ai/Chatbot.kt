package com.lightningkite.lightningserver.ai

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeExecuteTool
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResult
import ai.koog.agents.core.dsl.extension.onAssistantMessage
import ai.koog.agents.core.dsl.extension.onToolCall
import ai.koog.agents.core.dsl.extension.replaceHistoryWithTLDR
import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.ext.agent.chatAgentStrategy
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.serialization.parse
import com.lightningkite.services.ai.koog.LLMClientAndModel
import com.lightningkite.services.database.*
import com.lightningkite.services.files.FileObject
import kotlinx.coroutines.flow.toList

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
    public val llmClientAndModel: LLMClientAndModel,
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
     * @param userMessage The user's message
     * @param conversationTable The database table storing conversation history
     * @param maxIterations How many iterations the bot can make.
     * @return The chatbot's response
     */
    context(runtime: ServerRuntime)
    public suspend fun chat(
        userMessage: ConversationMessage,
        conversationTable: Table<ConversationMessage>,
        maxIterations: Int = 10,
    ): ConversationMessage {

        // Load conversation history
        val history = conversationTable.find(
            condition { it.conversationId.eq(userMessage.conversationId) },
            orderBy = sort { it.createdAt.ascending() }
        )
            .toList()

        // Create strategy that manages conversation history using sessions
        val chatStrategy = strategy("chat-with-history") {
            val nodeCallLLM by node<String, String> { input ->
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

                        user(input)
                    }

//                    replaceHistoryWithTLDR()

                    val response = if(this@Chatbot.tools.isNotEmpty()){
                        requestLLM()
                    } else {
                        requestLLMWithoutTools()
                    }
                    response.content
                }
            }

            val nodeExecuteTool by nodeExecuteTool("nodeExecuteTool")
            val nodeSendToolResult by nodeLLMSendToolResult("nodeSendToolResult")

            edge(nodeStart forwardTo nodeCallLLM)

            edge(nodeCallLLM forwardTo nodeFinish onAssistantMessage { true })

            edge(nodeCallLLM forwardTo nodeExecuteTool onToolCall { true })
            edge(nodeExecuteTool forwardTo nodeSendToolResult)

            edge(nodeSendToolResult forwardTo nodeFinish onAssistantMessage { true })
            edge(
                nodeSendToolResult forwardTo nodeFinish
                        onToolCall { tc -> tc.tool == "__exit__" }
                        transformed { "Chat finished" }
            )
            edge(nodeSendToolResult forwardTo nodeExecuteTool onToolCall { true })
        }


        val agent = AIAgent(
            promptExecutor = SingleLLMPromptExecutor(llmClientAndModel.client),
            llmModel = llmClientAndModel.model,
            strategy = chatStrategy,
            toolRegistry = ToolRegistry {
                tools.forEach { tool(it) }
            },
            maxIterations = maxIterations,
        )

        // Store the user's message
        conversationTable.insertOne(userMessage)

        // Run the agent with full conversation history
        val response = agent.run(userMessage.content)

        // Store the assistant's response
        val assistantMessage = ConversationMessage(
            conversationId = userMessage.conversationId,
            subjectId = userMessage.subjectId,
            role = ConversationMessage.Role.Assistant,
            content = response,
            createdAt = now()
        )
        conversationTable.insertOne(assistantMessage)

        return assistantMessage
    }
}

