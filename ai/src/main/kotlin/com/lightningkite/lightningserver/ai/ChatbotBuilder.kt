package com.lightningkite.lightningserver.ai

import ai.koog.agents.core.tools.SimpleTool
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.AuthAccess
import com.lightningkite.lightningserver.typed.ModelInfo
import com.lightningkite.services.ai.koog.LLMClientAndModel
import com.lightningkite.services.database.HasId

/**
 * Builder for creating Chatbot instances with database access tools.
 *
 * Example:
 * ```kotlin
 * val chatbot = chatbot(llm, "You are a helpful assistant") {
 *     addModelInfo(usersInfo, runtime)
 *     addModelInfo(postsInfo, runtime)
 * }
 * ```
 */
public class ChatbotBuilder(
    private val llmClientAndModel: LLMClientAndModel,
    private val systemPrompt: String,
) {
    private val tools = mutableListOf<SimpleTool<*>>()

    /**
     * Adds READ-ONLY database tools for the given ModelInfo.
     *
     * Creates 4 tools: count, get by ID, list, and query
     */
    public fun <SUBJECT : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>> addModelInfo(
        modelInfo: ModelInfo<SUBJECT, T, ID>,
        authAccess: AuthAccess<SUBJECT>,
        queryLimit: Int,
        runtime: ServerRuntime,
    ) {
        tools.addAll(createModelInfoTools(modelInfo, authAccess, queryLimit, runtime))
    }

    /**
     * Adds READ AND WRITE database tools for the given ModelInfo.
     *
     * **WARNING**: This gives the LLM the ability to INSERT, UPDATE, and DELETE records.
     *
     * Creates 4 tools: query, insert, update, delete
     */
    public fun <SUBJECT : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>> addModelInfoWithWrites(
        modelInfo: ModelInfo<SUBJECT, T, ID>,
        authAccess: AuthAccess<SUBJECT>,
        limit: Int,
        modelExamples: List<T>,
        runtime: ServerRuntime,
    ) {
        tools.addAll(createModelInfoToolsWithWrites(modelInfo, authAccess, limit, modelExamples, runtime))
    }

    /**
     * Adds a custom tool to the chatbot.
     */
    public fun addTool(tool: SimpleTool<*>) {
        tools.add(tool)
    }

    /**
     * Builds the immutable Chatbot instance.
     */
    public fun build(): Chatbot = Chatbot(
        llmClientAndModel = llmClientAndModel,
        tools = tools.toList(),
        systemPrompt = systemPrompt
    )
}

/**
 * Creates a Chatbot with the given configuration.
 *
 * Example:
 * ```kotlin
 * val chatbot = chatbot(llm, "You are a helpful assistant") {
 *     addModelInfo(usersInfo, runtime)
 *     addModelInfo(postsInfo, runtime)
 * }
 * ```
 */
public fun chatbot(
    llmClientAndModel: LLMClientAndModel,
    systemPrompt: String = "You are a helpful assistant with access to database information.",
    configure: ChatbotBuilder.() -> Unit = {},
): Chatbot {
    return ChatbotBuilder(llmClientAndModel, systemPrompt)
        .apply(configure)
        .build()
}

/**
 * Creates a Chatbot with read-only access to a single ModelInfo.
 */
public fun <SUBJECT : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>> chatbotFor(
    llmClientAndModel: LLMClientAndModel,
    modelInfo: ModelInfo<SUBJECT, T, ID>,
    queryLimit:Int,
    authAccess: AuthAccess<SUBJECT>,
    runtime: ServerRuntime,
    systemPrompt: String = "You are a helpful assistant with access to database information.",
): Chatbot = Chatbot(
    llmClientAndModel = llmClientAndModel,
    tools = createModelInfoTools(modelInfo, authAccess, queryLimit, runtime),
    systemPrompt = systemPrompt
)

/**
 * Creates a Chatbot with write access to a single ModelInfo.
 *
 * **WARNING**: This gives the LLM the ability to INSERT, UPDATE, and DELETE records.
 */
public fun <SUBJECT : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>> chatbotForWithWrites(
    llmClientAndModel: LLMClientAndModel,
    modelInfo: ModelInfo<SUBJECT, T, ID>,
    authAccess: AuthAccess<SUBJECT>,
    limit: Int,
    modelExamples: List<T>,
    runtime: ServerRuntime,
    systemPrompt: String = "You are an admin assistant. Always confirm before making changes.",
): Chatbot = Chatbot(
    llmClientAndModel = llmClientAndModel,
    tools = createModelInfoTools(modelInfo, authAccess, limit, runtime) + createModelInfoToolsWithWrites(modelInfo, authAccess, limit, modelExamples, runtime),
    systemPrompt = systemPrompt
)
