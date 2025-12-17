package com.lightningkite.lightningserver.ai

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.ai.koog.LLMClientAndModel
import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.*
import com.lightningkite.services.database.jsonfile.JsonFileDatabase
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import org.junit.BeforeClass
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Test user model for LLMChatEndpoints testing
 */
@Serializable
@GenerateDataClassPaths
data class LLMTestSubject(
    override val _id: Uuid = Uuid.random(),
    val email: String
) : HasId<Uuid>

/**
 * Test implementation of LLMChatEndpoints that simulates LLM responses
 * by directly calling the handle() method with controlled responses.
 */
class TestLLMChatEndpoints(
    database: ServerSetting<Database.Settings, Database>,
    authRequirement: AuthRequirement<LLMTestSubject>,
) : LLMChatEndpoints<LLMTestSubject>(
    database = database,
    authRequirement = authRequirement,
    conversationPermissions = {
        ModelPermissions(
            create = condition { it.subjectId eq this.auth.rawId.toString() },
            read = condition { it.subjectId eq this.auth.rawId.toString() },
            update = condition { it.subjectId eq this.auth.rawId.toString() },
            delete = condition { it.subjectId eq this.auth.rawId.toString() },
        )
    },
    messagePermissions = {
        ModelPermissions(
            create = condition { it.subjectId eq this.auth.rawId.toString() },
            read = condition { it.subjectId eq this.auth.rawId.toString() },
            update = condition { it.subjectId eq this.auth.rawId.toString() },
            delete = condition { it.subjectId eq this.auth.rawId.toString() },
        )
    }
) {
    override val tools: Map<String, ChatTool<LLMTestSubject, *>> = emptyMap()

    // Dummy LLM - we override respond() so this is never actually used
    override val defaultLlm: Runtime<LLMClientAndModel> = Runtime {
        throw NotImplementedError("Tests should use simulatedResponses instead")
    }

    /**
     * Queue of responses that will be returned by the simulated LLM.
     * Each call to respond() pops one response from this queue.
     */
    val simulatedResponses = mutableListOf<List<Message.Response>>()

    /**
     * Counter for how many times the LLM would have been invoked.
     */
    var llmInvocationCount = 0
        private set

    fun reset() {
        simulatedResponses.clear()
        llmInvocationCount = 0
    }

    fun addResponse(vararg messages: Message.Response) {
        simulatedResponses.add(messages.toList())
    }

    /**
     * Override respond() to use our simulated responses instead of calling a real LLM.
     * This mimics the behavior of the real respond() but with controlled inputs.
     */
    context(serverRuntime: ServerRuntime)
    override suspend fun respond(
        auth: AuthAccess<LLMTestSubject>,
        conversation: SystemChatConversation
    ) {
        with(serverRuntime) {
            try {
                repeat(maxIterations) {
                    llmInvocationCount++

                    // Get next simulated response, or default to assistant message
                    val response = if (simulatedResponses.isNotEmpty()) {
                        simulatedResponses.removeAt(0)
                    } else {
                        listOf(createAssistantMessage("Default response $llmInvocationCount"))
                    }

                    // Process through the real handle() method
                    response.handle(conversation, auth)
                }
            } catch (_: StopProcessing) {
                // Expected - this is how the loop terminates
            }
        }
    }

    companion object {
        private val clock = kotlin.time.Clock.System

        fun createAssistantMessage(content: String): Message.Assistant {
            return Message.Assistant(content, metaInfo = ResponseMetaInfo.create(clock))
        }

        fun createReasoningMessage(content: String): Message.Reasoning {
            return Message.Reasoning(content = content, metaInfo = ResponseMetaInfo.create(clock))
        }
    }
}

/**
 * Test server for LLMChatEndpoints
 */
object LLMTestServer : ServerBuilder() {
    val database = setting("database", Database.Settings())

    object LLMTestSubjectAuth : PrincipalType<LLMTestSubject, Uuid> {
        override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
        override val subjectSerializer: KSerializer<LLMTestSubject> = LLMTestSubject.serializer()
        override val name: String = "LLMTestSubject"

        private val testSubjects = mutableMapOf<Uuid, LLMTestSubject>()

        fun addSubject(subject: LLMTestSubject) {
            testSubjects[subject._id] = subject
        }

        fun clearSubjects() {
            testSubjects.clear()
        }

        context(server: ServerRuntime)
        override suspend fun fetch(id: Uuid): LLMTestSubject {
            return testSubjects[id] ?: throw NotFoundException("LLMTestSubject not found: $id")
        }
    }

    init {
        register(LLMTestSubjectAuth)
    }

    val chatEndpoints = path.path("chat") include TestLLMChatEndpoints(
        database = database,
        authRequirement = LLMTestSubjectAuth.require(),
    )
}

class LLMChatEndpointsTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun setup() {
            JsonFileDatabase // Ensure service implementations are loaded
        }
    }

    @Test
    fun testResponseLoopTerminatesAfterAssistantMessage() = runBlocking {
        LLMTestServer.test(settings = { LLMTestServer.database set Database.Settings("ram") }) {
            val subject = LLMTestSubject(email = "test@example.com")
            LLMTestServer.LLMTestSubjectAuth.addSubject(subject)
            val auth = LLMTestServer.LLMTestSubjectAuth.testAuth(subject)
            val access = AuthAccess(auth)
            val endpoints = LLMTestServer.chatEndpoints as TestLLMChatEndpoints
            endpoints.reset()

            // Configure simulated LLM to return a single assistant message
            endpoints.addResponse(TestLLMChatEndpoints.createAssistantMessage("Hello! How can I help you?"))

            // Create a conversation
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                autoProcess = true,
                createdAt = now()
            )
            LLMTestServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // Create a user message - this triggers the response loop
            val userMessage = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.User,
                content = "Hello",
                createdAt = now()
            )
            LLMTestServer.chatEndpoints.messageInfo.table(access).insertOne(userMessage)

            // The LLM should only be called ONCE - the loop should terminate after the assistant message
            assertEquals(1, endpoints.llmInvocationCount,
                "LLM should only be called once when it returns an assistant message without tool calls")

            // Verify only one assistant message was created
            val messages = LLMTestServer.chatEndpoints.messageInfo.baseTable()
                .find(condition { it.conversationId eq conversation._id })
                .toList()

            val assistantMessages = messages.filter { it.role == SystemChatMessage.Role.Assistant }
            assertEquals(1, assistantMessages.size,
                "Should have exactly one assistant message, got ${assistantMessages.size}")
            assertEquals("Hello! How can I help you?", assistantMessages.first().content)

            LLMTestServer.LLMTestSubjectAuth.clearSubjects()
        }
    }

    @Test
    fun testResponseLoopDoesNotRunAwayWithMultipleResponses() = runBlocking {
        LLMTestServer.test(settings = { LLMTestServer.database set Database.Settings("ram") }) {
            val subject = LLMTestSubject(email = "test@example.com")
            LLMTestServer.LLMTestSubjectAuth.addSubject(subject)
            val auth = LLMTestServer.LLMTestSubjectAuth.testAuth(subject)
            val access = AuthAccess(auth)
            val endpoints = LLMTestServer.chatEndpoints as TestLLMChatEndpoints
            endpoints.reset()

            // Don't add any specific responses - mock will return default assistant messages
            // If the loop doesn't terminate, it will keep calling execute()

            // Create a conversation
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                autoProcess = true,
                createdAt = now()
            )
            LLMTestServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // Create a user message
            val userMessage = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.User,
                content = "Hello",
                createdAt = now()
            )
            LLMTestServer.chatEndpoints.messageInfo.table(access).insertOne(userMessage)

            // The LLM should only be called once - not 50 times (maxIterations)
            assertTrue(endpoints.llmInvocationCount <= 1,
                "LLM should not be called multiple times for a simple response. " +
                "Called ${endpoints.llmInvocationCount} times - loop may not be terminating!")

            // Verify we don't have a flood of assistant messages
            val messages = LLMTestServer.chatEndpoints.messageInfo.baseTable()
                .find(condition { it.conversationId eq conversation._id })
                .toList()

            val assistantMessages = messages.filter { it.role == SystemChatMessage.Role.Assistant }
            assertTrue(assistantMessages.size <= 1,
                "Should not have multiple assistant messages from a single user message. " +
                "Got ${assistantMessages.size} assistant messages - loop may not be terminating!")

            LLMTestServer.LLMTestSubjectAuth.clearSubjects()
        }
    }

    @Test
    fun testReasoningMessageDoesNotStopLoop() = runBlocking {
        LLMTestServer.test(settings = { LLMTestServer.database set Database.Settings("ram") }) {
            val subject = LLMTestSubject(email = "test@example.com")
            LLMTestServer.LLMTestSubjectAuth.addSubject(subject)
            val auth = LLMTestServer.LLMTestSubjectAuth.testAuth(subject)
            val access = AuthAccess(auth)
            val endpoints = LLMTestServer.chatEndpoints as TestLLMChatEndpoints
            endpoints.reset()

            // First response: reasoning only (should NOT stop the loop)
            endpoints.addResponse(TestLLMChatEndpoints.createReasoningMessage("Let me think about this..."))
            // Second response: assistant message (should stop the loop)
            endpoints.addResponse(TestLLMChatEndpoints.createAssistantMessage("Here's my answer!"))

            // Create a conversation
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                autoProcess = true,
                createdAt = now()
            )
            LLMTestServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // Create a user message
            val userMessage = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.User,
                content = "What is 2+2?",
                createdAt = now()
            )
            LLMTestServer.chatEndpoints.messageInfo.table(access).insertOne(userMessage)

            // Should be called twice: once for reasoning, once for final answer
            assertEquals(2, endpoints.llmInvocationCount,
                "LLM should be called twice - reasoning doesn't stop the loop, but assistant does")

            // Verify messages were created
            val messages = LLMTestServer.chatEndpoints.messageInfo.baseTable()
                .find(condition { it.conversationId eq conversation._id })
                .toList()

            val thinkingMessages = messages.filter { it.role == SystemChatMessage.Role.Thinking }
            val assistantMessages = messages.filter { it.role == SystemChatMessage.Role.Assistant }

            assertEquals(1, thinkingMessages.size, "Should have one thinking message")
            assertEquals(1, assistantMessages.size, "Should have one assistant message")

            LLMTestServer.LLMTestSubjectAuth.clearSubjects()
        }
    }
}
