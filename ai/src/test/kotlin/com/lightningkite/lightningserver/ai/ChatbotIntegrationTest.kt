package com.lightningkite.lightningserver.ai

import ai.koog.prompt.executor.clients.openai.OpenAIModels
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.services.ai.koog.LLMClientAndModelSettings
import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Integration tests for the chatbot with real OpenAI API calls.
 *
 * These tests require an API key in local/openaikey.txt
 */
@Serializable
@GenerateDataClassPaths
data class TestUser(
    override val _id: Uuid = Uuid.random(),
    val name: String,
    val email: String,
    val role: String,
    val active: Boolean = true
) : HasId<Uuid>

@Serializable
@GenerateDataClassPaths
data class TestPost(
    override val _id: Uuid = Uuid.random(),
    val title: String,
    val content: String,
    val authorId: Uuid,
    val viewCount: Int = 0,
    val status: String = "draft"
) : HasId<Uuid>


class ChatbotIntegrationTest {

    private fun getApiKey(): String? {
        val keyFile = File("./local/openaikey.txt")
        return if (keyFile.exists()) {
            keyFile.readText().trim()
        } else {
            null
        }
    }

    @Test
    fun testBasicChatWithoutTools() {
        val apiKey = getApiKey() ?: run {
            println("Skipping integration test - no API key found in local/openaikey.txt")
            return
        }

        val llmSettings = LLMClientAndModelSettings.openai(
            model = OpenAIModels.CostOptimized.GPT4oMini,
            apiKey = apiKey
        )

        // Create a test server
        val testServer = object : ServerBuilder() {
            val database = setting("database", Database.Settings())
            val llm = setting("llm", llmSettings)
        }

        testServer.test(settings = {
            testServer.database set Database.Settings("ram")
        }) {
            // Create a chatbot with no tools
            val chatbot = Chatbot(
                llmClientAndModel = testServer.llm,
                tools = emptyList(),
                systemPrompt = "You are a helpful assistant. Answer concisely."
            )

            val conversationId = Uuid.random()
            val conversationTable = testServer.database().table<ConversationMessage>()

            // Call chat - we're already in ServerRuntime context from test{}
            val response = runBlocking {
                chatbot.chat(conversationId, "What is 2 + 2? Answer with just the number.", conversationTable)
            }

            println("Chatbot response: $response")
            assertTrue(response.contains("4"), "Expected response to contain '4', got: $response")
        }
    }

    // TODO: Add integration test with database tools
    // This requires setting up a proper test server with ModelInfo
    // See demo/src/test for examples of how to properly structure these tests
}
