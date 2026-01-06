package com.lightningkite.lightningserver.ai

import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.*
import com.lightningkite.services.database.insertOne
import com.lightningkite.services.database.jsonfile.JsonFileDatabase
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import org.junit.BeforeClass
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import com.lightningkite.lightningserver.ai.models.*

/**
 * Test user model for authentication testing
 */
@Serializable
@GenerateDataClassPaths
data class TestSubject(
    override val _id: Uuid = Uuid.random(),
    val email: String
) : HasId<Uuid>

/**
 * A simple test tool for verification
 */
class TestChatTool(
    override val name: String,
    private val descriptionText: String = "Test tool",
    private val onExecute: (String) -> String = { args -> "Executed $name with args: $args" }
) : AutoApprovedTool<TestSubject, String>() {
    override val argsSerializer: KSerializer<String> = String.serializer()

    context(serverRuntime: ServerRuntime) override suspend fun description(
        auth: AuthAccess<TestSubject>,
    ): TotalExplanation = TotalExplanation(unique = descriptionText)

    context(serverRuntime: ServerRuntime) override suspend fun execute(
        auth: AuthAccess<TestSubject>,
        args: String,
    ): String = onExecute(args)
}

/**
 * Concrete implementation of SystemChatEndpoints for testing
 */
class TestSystemChatEndpoints(
    database: ServerSetting<Database.Settings, Database>,
    authRequirement: AuthRequirement<TestSubject>,
) : SystemChatEndpoints<TestSubject>(
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

    // Track responses for testing
    val respondCalls = mutableListOf<SystemChatConversation>()
    val toolExecutions = mutableListOf<String>()

    // Available tools for testing
    val availableTools = mutableMapOf<String, ChatTool<TestSubject, *>>()

    context(serverRuntime: ServerRuntime) override suspend fun respond(
        auth: AuthAccess<TestSubject>,
        conversation: SystemChatConversation,
    ) {
        respondCalls.add(conversation)

        // Get the last user message for echo response
        with(serverRuntime) {
            val history = getConversationHistory(auth, conversation._id)
            val lastUserMessage = history.lastOrNull { it.role == SystemChatMessage.Role.User }

            if (lastUserMessage != null) {
                val message = SystemChatMessage(
                    conversationId = conversation._id,
                    subjectId = conversation.subjectId,
                    role = SystemChatMessage.Role.Assistant,
                    content = "Echo: ${lastUserMessage.content}",
                    createdAt = now()
                )
                messageInfo.table().insertOne(message)
            }
        }
    }

    context(serverRuntime: ServerRuntime) override fun findToolByName(
        auth: AuthAccess<TestSubject>,
        conversation: SystemChatConversation,
        toolName: String,
    ): ChatTool<TestSubject, *>? {
        toolExecutions.add(toolName)
        return availableTools[toolName]
    }
}

/**
 * Test server setup
 */
object TestChatServer : ServerBuilder() {
    val database = setting("database", Database.Settings())

    object TestSubjectAuth : PrincipalType<TestSubject, Uuid> {
        override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
        override val subjectSerializer: KSerializer<TestSubject> = TestSubject.serializer()
        override val name: String = "TestSubject"

        // Store test subjects for lookup
        private val testSubjects = mutableMapOf<Uuid, TestSubject>()

        fun addSubject(subject: TestSubject) {
            testSubjects[subject._id] = subject
        }

        fun clearSubjects() {
            testSubjects.clear()
        }

        context(server: ServerRuntime)
        override suspend fun fetch(id: Uuid): TestSubject {
            return testSubjects[id] ?: throw NotFoundException("TestSubject not found: $id")
        }
    }

    init {
        register(TestSubjectAuth)
    }

    val chatEndpoints = path.path("chat") include TestSystemChatEndpoints(
        database = database,
        authRequirement = TestSubjectAuth.require()
    )
}

class SystemChatEndpointsTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun setup() {
            JsonFileDatabase // Ensure service implementations are loaded
        }
    }

    @Test
    fun testCreateConversation() = runBlocking {
        TestChatServer.test(settings = { TestChatServer.database set Database.Settings("ram") }) {
            val subject = TestSubject(email = "test@example.com")
            TestChatServer.TestSubjectAuth.addSubject(subject)
            val auth = TestChatServer.TestSubjectAuth.testAuth(subject)
            val access = AuthAccess(auth)

            // Create a conversation
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                createdAt = now()
            )

            val created = TestChatServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            assertNotNull(created)
            assertEquals(conversation._id, created._id)
            assertEquals(auth.rawId.toString(), created.subjectId)

            TestChatServer.TestSubjectAuth.clearSubjects()
        }
    }

    @Test
    fun testCreateMessage() = runBlocking {
        TestChatServer.test(settings = { TestChatServer.database set Database.Settings("ram") }) {
            val subject = TestSubject(email = "test@example.com")
            TestChatServer.TestSubjectAuth.addSubject(subject)
            val auth = TestChatServer.TestSubjectAuth.testAuth(subject)
            val access = AuthAccess(auth)

            // Create a conversation first
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                createdAt = now()
            )
            TestChatServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // Create a user message
            val message = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.User,
                content = "Hello, world!",
                createdAt = now()
            )

            val created = TestChatServer.chatEndpoints.messageInfo.table(access).insertOne(message)

            assertNotNull(created)
            assertEquals("Hello, world!", created.content)
            assertEquals(SystemChatMessage.Role.User, created.role)

            TestChatServer.TestSubjectAuth.clearSubjects()
        }
    }

    @Test
    fun testUserMessageTriggersResponse() = runBlocking {
        TestChatServer.test(settings = { TestChatServer.database set Database.Settings("ram") }) {
            val subject = TestSubject(email = "test@example.com")
            TestChatServer.TestSubjectAuth.addSubject(subject)
            val auth = TestChatServer.TestSubjectAuth.testAuth(subject)
            val access = AuthAccess(auth)
            val endpoints = TestChatServer.chatEndpoints as TestSystemChatEndpoints
            endpoints.respondCalls.clear()

            // Create a conversation
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                autoProcess = true,
                createdAt = now()
            )
            TestChatServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // Create a user message - this should trigger respond()
            val message = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.User,
                content = "Test message",
                createdAt = now()
            )
            TestChatServer.chatEndpoints.messageInfo.table(access).insertOne(message)

            // In test mode, tasks run inline, so respond should have been called
            assertTrue(endpoints.respondCalls.isNotEmpty(), "respond() should have been called")
            assertEquals(conversation._id, endpoints.respondCalls.first()._id)

            // Check that assistant message was created
            val messages = TestChatServer.chatEndpoints.messageInfo.baseTable()
                .find(condition { it.conversationId eq conversation._id })
                .toList()

            assertTrue(messages.size >= 2, "Should have at least user and assistant messages")
            val assistantMessage = messages.find { it.role == SystemChatMessage.Role.Assistant }
            assertNotNull(assistantMessage)
            assertEquals("Echo: Test message", assistantMessage.content)

            TestChatServer.TestSubjectAuth.clearSubjects()
        }
    }

    @Test
    fun testAutoProcessDisabled() = runBlocking {
        TestChatServer.test(settings = { TestChatServer.database set Database.Settings("ram") }) {
            val subject = TestSubject(email = "test@example.com")
            TestChatServer.TestSubjectAuth.addSubject(subject)
            val auth = TestChatServer.TestSubjectAuth.testAuth(subject)
            val access = AuthAccess(auth)
            val endpoints = TestChatServer.chatEndpoints as TestSystemChatEndpoints
            endpoints.respondCalls.clear()

            // Create a conversation with autoProcess = false
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                autoProcess = false,
                createdAt = now()
            )
            TestChatServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // Create a user message
            val message = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.User,
                content = "Test message",
                createdAt = now()
            )
            TestChatServer.chatEndpoints.messageInfo.table(access).insertOne(message)

            // respond() should NOT have been called because autoProcess is false
            assertTrue(endpoints.respondCalls.isEmpty(), "respond() should NOT have been called when autoProcess=false")

            TestChatServer.TestSubjectAuth.clearSubjects()
        }
    }

    @Test
    fun testToolRequestWithApproval() = runBlocking {
        TestChatServer.test(settings = { TestChatServer.database set Database.Settings("ram") }) {
            val subject = TestSubject(email = "test@example.com")
            TestChatServer.TestSubjectAuth.addSubject(subject)
            val auth = TestChatServer.TestSubjectAuth.testAuth(subject)
            val access = AuthAccess(auth)
            val endpoints = TestChatServer.chatEndpoints as TestSystemChatEndpoints
            endpoints.toolExecutions.clear()

            // Register a test tool
            endpoints.availableTools["deleteData"] = TestChatTool("deleteData")

            // Create a conversation
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                createdAt = now()
            )
            TestChatServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // Create a tool request that requires approval
            val toolRequest = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.ToolRequest,
                content = "Delete user data",
                tool = ToolRequestData(
                    toolName = "deleteData",
                    arguments = """"test-args"""",
                    requiresApproval = true
                ),
                createdAt = now()
            )
            TestChatServer.chatEndpoints.messageInfo.baseTable().insertOne(toolRequest)

            // Tool should NOT have been executed yet
            assertTrue(endpoints.toolExecutions.isEmpty(), "Tool should not execute without approval")

            // Now approve it
            val approvalResult = TestChatServer.chatEndpoints.approveToolRequest.test(
                toolRequest._id,
                auth,
                ToolApprovalRequest(approved = true)
            )

            assertNotNull(approvalResult)
            assertNotNull(approvalResult.tool?.approval)
            assertTrue(approvalResult.tool!!.approval!!.approved)

            // findToolByName should have been called
            assertTrue(endpoints.toolExecutions.contains("deleteData"), "findToolByName should have been called after approval")

            TestChatServer.TestSubjectAuth.clearSubjects()
        }
    }

    @Test
    fun testToolRequestRejection() = runBlocking {
        TestChatServer.test(settings = { TestChatServer.database set Database.Settings("ram") }) {
            val subject = TestSubject(email = "test@example.com")
            TestChatServer.TestSubjectAuth.addSubject(subject)
            val auth = TestChatServer.TestSubjectAuth.testAuth(subject)
            val access = AuthAccess(auth)
            val endpoints = TestChatServer.chatEndpoints as TestSystemChatEndpoints
            endpoints.toolExecutions.clear()
            endpoints.respondCalls.clear()

            // Create a conversation
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                createdAt = now()
            )
            TestChatServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // Create a tool request that requires approval
            val toolRequest = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.ToolRequest,
                content = "Delete user data",
                tool = ToolRequestData(
                    toolName = "deleteData",
                    arguments = """"test-args"""",
                    requiresApproval = true
                ),
                createdAt = now()
            )
            TestChatServer.chatEndpoints.messageInfo.baseTable().insertOne(toolRequest)

            // Reject the tool request
            val rejectionResult = TestChatServer.chatEndpoints.approveToolRequest.test(
                toolRequest._id,
                auth,
                ToolApprovalRequest(approved = false, reason = "Too dangerous")
            )

            assertNotNull(rejectionResult)
            assertNotNull(rejectionResult.tool?.approval)
            assertEquals(false, rejectionResult.tool!!.approval!!.approved)
            assertEquals("Too dangerous", rejectionResult.tool!!.approval!!.reason)

            // Tool should NOT have been executed (findToolByName not called)
            assertTrue(endpoints.toolExecutions.isEmpty(), "Tool should not execute when rejected")

            // But respond() should have been called for continuation
            assertTrue(endpoints.respondCalls.isNotEmpty(), "respond() should be called after rejection for continuation")

            TestChatServer.TestSubjectAuth.clearSubjects()
        }
    }

    @Test
    fun testToolAuthorizationEndpoint() = runBlocking {
        TestChatServer.test(settings = { TestChatServer.database set Database.Settings("ram") }) {
            val subject = TestSubject(email = "test@example.com")
            TestChatServer.TestSubjectAuth.addSubject(subject)
            val auth = TestChatServer.TestSubjectAuth.testAuth(subject)
            val access = AuthAccess(auth)

            // Create a conversation
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                createdAt = now()
            )
            TestChatServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // Authorize a tool for the conversation
            val updatedConversation = TestChatServer.chatEndpoints.authorizeTool.test(
                conversation._id,
                auth,
                AuthorizeToolRequest(toolName = "myTool", durationSeconds = 3600)
            )

            assertNotNull(updatedConversation)
            assertTrue(updatedConversation.toolAuthorizations.isNotEmpty())
            assertEquals("myTool", updatedConversation.toolAuthorizations.first().toolName)
            assertNotNull(updatedConversation.toolAuthorizations.first().expiresAt)

            TestChatServer.TestSubjectAuth.clearSubjects()
        }
    }

    @Test
    fun testWildcardToolAuthorization() = runBlocking {
        TestChatServer.test(settings = { TestChatServer.database set Database.Settings("ram") }) {
            val subject = TestSubject(email = "test@example.com")
            TestChatServer.TestSubjectAuth.addSubject(subject)
            val auth = TestChatServer.TestSubjectAuth.testAuth(subject)
            val access = AuthAccess(auth)

            // Create a conversation
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                createdAt = now()
            )
            TestChatServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // Authorize all tools with wildcard
            val updatedConversation = TestChatServer.chatEndpoints.authorizeTool.test(
                conversation._id,
                auth,
                AuthorizeToolRequest(toolName = "*")
            )

            assertNotNull(updatedConversation)
            assertTrue(updatedConversation.toolAuthorizations.isNotEmpty())
            assertEquals("*", updatedConversation.toolAuthorizations.first().toolName)

            TestChatServer.TestSubjectAuth.clearSubjects()
        }
    }

    @Test
    fun testMessageRoleTypes() = runBlocking {
        TestChatServer.test(settings = { TestChatServer.database set Database.Settings("ram") }) {
            val subject = TestSubject(email = "test@example.com")
            TestChatServer.TestSubjectAuth.addSubject(subject)
            val auth = TestChatServer.TestSubjectAuth.testAuth(subject)
            val access = AuthAccess(auth)

            // Create a conversation
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                autoProcess = false, // Disable auto-processing for this test
                createdAt = now()
            )
            TestChatServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // Test all message role types
            val roles = listOf(
                SystemChatMessage.Role.User,
                SystemChatMessage.Role.Assistant,
                SystemChatMessage.Role.System,
                SystemChatMessage.Role.Thinking,
                SystemChatMessage.Role.Error
            )

            for (role in roles) {
                val message = SystemChatMessage(
                    conversationId = conversation._id,
                    subjectId = auth.rawId.toString(),
                    role = role,
                    content = "Test message for role: $role",
                    createdAt = now()
                )
                val created = TestChatServer.chatEndpoints.messageInfo.table(access).insertOne(message)!!
                assertEquals(role, created.role)
            }

            // Verify all messages were created
            val allMessages = TestChatServer.chatEndpoints.messageInfo.baseTable()
                .find(condition { it.conversationId eq conversation._id })
                .toList()

            assertEquals(roles.size, allMessages.size)

            TestChatServer.TestSubjectAuth.clearSubjects()
        }
    }

    @Test
    fun testProcessingLockPreventsDoubleProcessing() = runBlocking {
        TestChatServer.test(settings = { TestChatServer.database set Database.Settings("ram") }) {
            val subject = TestSubject(email = "test@example.com")
            TestChatServer.TestSubjectAuth.addSubject(subject)
            val auth = TestChatServer.TestSubjectAuth.testAuth(subject)
            val access = AuthAccess(auth)

            // Create a conversation with a lock already held
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                autoProcess = true,
                processingLock = ProcessingLock(
                    holderId = "some-other-process",
                    acquiredAt = now()
                ),
                createdAt = now()
            )
            TestChatServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            val endpoints = TestChatServer.chatEndpoints as TestSystemChatEndpoints
            endpoints.respondCalls.clear()

            // Create a user message
            val message = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.User,
                content = "Test message",
                createdAt = now()
            )
            TestChatServer.chatEndpoints.messageInfo.table(access).insertOne(message)

            // respond() should NOT have been called because lock is held
            assertTrue(endpoints.respondCalls.isEmpty(), "respond() should not be called when lock is held")

            TestChatServer.TestSubjectAuth.clearSubjects()
        }
    }

    //
    // Tests for skipAutoResponse field
    //

    @Test
    fun testSkipAutoResponsePreventsAutoResponse() = runBlocking {
        TestChatServer.test(settings = { TestChatServer.database set Database.Settings("ram") }) {
            val subject = TestSubject(email = "test@example.com")
            TestChatServer.TestSubjectAuth.addSubject(subject)
            val auth = TestChatServer.TestSubjectAuth.testAuth(subject)
            val access = AuthAccess(auth)
            val endpoints = TestChatServer.chatEndpoints as TestSystemChatEndpoints
            endpoints.respondCalls.clear()

            // Create a conversation with autoProcess = true
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                autoProcess = true,
                createdAt = now()
            )
            TestChatServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // Create a user message WITH skipAutoResponse = true (simulating voice/phone channel)
            val message = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.User,
                channel = "voice",
                content = "Test message from voice",
                createdAt = now(),
                skipAutoResponse = true  // Key: voice channel sets this
            )

            // Use direct table insert to simulate what VoiceChannelSupport does
            TestChatServer.chatEndpoints.messageInfo.baseTable().insertOne(message)

            // respond() should NOT have been called because skipAutoResponse = true
            assertTrue(endpoints.respondCalls.isEmpty(),
                "respond() should NOT be called when skipAutoResponse=true, even with autoProcess=true")

            // Verify message was stored correctly
            val storedMessage = TestChatServer.chatEndpoints.messageInfo.baseTable().get(message._id)
            assertNotNull(storedMessage)
            assertEquals(true, storedMessage.skipAutoResponse)
            assertEquals("voice", storedMessage.channel)

            TestChatServer.TestSubjectAuth.clearSubjects()
        }
    }

    @Test
    fun testTriggerAutoResponseWithSkipFlagDoesNothing() = runBlocking {
        TestChatServer.test(settings = { TestChatServer.database set Database.Settings("ram") }) {
            val subject = TestSubject(email = "test@example.com")
            TestChatServer.TestSubjectAuth.addSubject(subject)
            val auth = TestChatServer.TestSubjectAuth.testAuth(subject)
            val access = AuthAccess(auth)
            val endpoints = TestChatServer.chatEndpoints as TestSystemChatEndpoints
            endpoints.respondCalls.clear()

            // Create a conversation
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                autoProcess = true,
                createdAt = now()
            )
            TestChatServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // Create a message with skipAutoResponse = true
            val message = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.User,
                content = "Test message",
                createdAt = now(),
                skipAutoResponse = true
            )
            TestChatServer.chatEndpoints.messageInfo.baseTable().insertOne(message)

            // Explicitly call triggerAutoResponse - should do nothing due to skipAutoResponse
            TestChatServer.chatEndpoints.triggerAutoResponse(access, message)

            assertTrue(endpoints.respondCalls.isEmpty(),
                "triggerAutoResponse should do nothing when skipAutoResponse=true")

            TestChatServer.TestSubjectAuth.clearSubjects()
        }
    }

    @Test
    fun testTriggerAutoResponseWithoutSkipFlagWorks() = runBlocking {
        TestChatServer.test(settings = { TestChatServer.database set Database.Settings("ram") }) {
            val subject = TestSubject(email = "test@example.com")
            TestChatServer.TestSubjectAuth.addSubject(subject)
            val auth = TestChatServer.TestSubjectAuth.testAuth(subject)
            val access = AuthAccess(auth)
            val endpoints = TestChatServer.chatEndpoints as TestSystemChatEndpoints
            endpoints.respondCalls.clear()

            // Create a conversation
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                autoProcess = true,
                createdAt = now()
            )
            TestChatServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // Create a message WITHOUT skipAutoResponse (default = false)
            val message = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.User,
                content = "Test message",
                createdAt = now()
                // skipAutoResponse defaults to false
            )
            TestChatServer.chatEndpoints.messageInfo.baseTable().insertOne(message)

            // Explicitly call triggerAutoResponse - should trigger response
            TestChatServer.chatEndpoints.triggerAutoResponse(access, message)

            assertTrue(endpoints.respondCalls.isNotEmpty(),
                "triggerAutoResponse should trigger respond() when skipAutoResponse=false")

            TestChatServer.TestSubjectAuth.clearSubjects()
        }
    }

    @Test
    fun testTriggerAutoResponseIgnoresNonUserMessages() = runBlocking {
        TestChatServer.test(settings = { TestChatServer.database set Database.Settings("ram") }) {
            val subject = TestSubject(email = "test@example.com")
            TestChatServer.TestSubjectAuth.addSubject(subject)
            val auth = TestChatServer.TestSubjectAuth.testAuth(subject)
            val access = AuthAccess(auth)
            val endpoints = TestChatServer.chatEndpoints as TestSystemChatEndpoints
            endpoints.respondCalls.clear()

            // Create a conversation
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                autoProcess = true,
                createdAt = now()
            )
            TestChatServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // Try to trigger on an Assistant message (should be ignored)
            val assistantMessage = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.Assistant,
                content = "I am the assistant",
                createdAt = now()
            )
            TestChatServer.chatEndpoints.messageInfo.baseTable().insertOne(assistantMessage)
            TestChatServer.chatEndpoints.triggerAutoResponse(access, assistantMessage)

            assertTrue(endpoints.respondCalls.isEmpty(),
                "triggerAutoResponse should ignore non-User messages")

            // Try to trigger on a ToolRequest message (should be ignored)
            val toolMessage = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.ToolRequest,
                content = "Tool call",
                tool = ToolRequestData(toolName = "test", arguments = "{}"),
                createdAt = now()
            )
            TestChatServer.chatEndpoints.messageInfo.baseTable().insertOne(toolMessage)
            TestChatServer.chatEndpoints.triggerAutoResponse(access, toolMessage)

            assertTrue(endpoints.respondCalls.isEmpty(),
                "triggerAutoResponse should ignore ToolRequest messages")

            TestChatServer.TestSubjectAuth.clearSubjects()
        }
    }

    @Test
    fun testToolRequestMessageWithSkipAutoResponse() = runBlocking {
        TestChatServer.test(settings = { TestChatServer.database set Database.Settings("ram") }) {
            val subject = TestSubject(email = "test@example.com")
            TestChatServer.TestSubjectAuth.addSubject(subject)
            val auth = TestChatServer.TestSubjectAuth.testAuth(subject)
            val access = AuthAccess(auth)
            val endpoints = TestChatServer.chatEndpoints as TestSystemChatEndpoints
            endpoints.respondCalls.clear()
            endpoints.toolExecutions.clear()

            // Create a conversation
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                autoProcess = true,
                createdAt = now()
            )
            TestChatServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // Create a tool request message with skipAutoResponse = true
            // This simulates a voice channel saving its tool calls
            val toolMessage = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.ToolRequest,
                channel = "voice",
                content = "Called testTool",
                tool = ToolRequestData(
                    toolName = "testTool",
                    arguments = """{"arg": "value"}""",
                    requiresApproval = false,
                    result = "Tool executed successfully"
                ),
                createdAt = now(),
                skipAutoResponse = true
            )

            // Direct insert (simulating VoiceChannelSupport)
            TestChatServer.chatEndpoints.messageInfo.baseTable().insertOne(toolMessage)

            // No auto-response should be triggered
            assertTrue(endpoints.respondCalls.isEmpty(),
                "Tool message with skipAutoResponse should not trigger respond()")

            // Verify message was stored
            val storedMessage = TestChatServer.chatEndpoints.messageInfo.baseTable().get(toolMessage._id)
            assertNotNull(storedMessage)
            assertEquals(SystemChatMessage.Role.ToolRequest, storedMessage.role)
            assertEquals("voice", storedMessage.channel)
            assertEquals(true, storedMessage.skipAutoResponse)
            assertEquals("Tool executed successfully", storedMessage.tool?.result)

            TestChatServer.TestSubjectAuth.clearSubjects()
        }
    }

    @Test
    fun testTriggerToolExecutionMethod() = runBlocking {
        TestChatServer.test(settings = { TestChatServer.database set Database.Settings("ram") }) {
            val subject = TestSubject(email = "test@example.com")
            TestChatServer.TestSubjectAuth.addSubject(subject)
            val auth = TestChatServer.TestSubjectAuth.testAuth(subject)
            val access = AuthAccess(auth)
            val endpoints = TestChatServer.chatEndpoints as TestSystemChatEndpoints
            endpoints.toolExecutions.clear()

            // Register a test tool
            endpoints.availableTools["externalTool"] = TestChatTool("externalTool")

            // Create a conversation
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                createdAt = now()
            )
            TestChatServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // Create an approved tool request message
            val toolMessage = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.ToolRequest,
                content = "Execute external tool",
                tool = ToolRequestData(
                    toolName = "externalTool",
                    arguments = """{"data": "test"}""",
                    requiresApproval = true,
                    approval = ToolApproval(
                        approved = true,
                        approvedBy = auth.rawId,
                        approvedAt = now()
                    )
                ),
                createdAt = now()
            )
            TestChatServer.chatEndpoints.messageInfo.baseTable().insertOne(toolMessage)

            // Call triggerToolExecution (simulating what ExternalChannelSupport does after approval)
            TestChatServer.chatEndpoints.triggerToolExecution(access, toolMessage)

            // Verify findToolByName was called
            assertTrue(endpoints.toolExecutions.contains("externalTool"),
                "triggerToolExecution should trigger tool lookup and execution")

            TestChatServer.TestSubjectAuth.clearSubjects()
        }
    }

    @Test
    fun testTriggerToolExecutionIgnoresRejectedRequests() = runBlocking {
        TestChatServer.test(settings = { TestChatServer.database set Database.Settings("ram") }) {
            val subject = TestSubject(email = "test@example.com")
            TestChatServer.TestSubjectAuth.addSubject(subject)
            val auth = TestChatServer.TestSubjectAuth.testAuth(subject)
            val access = AuthAccess(auth)
            val endpoints = TestChatServer.chatEndpoints as TestSystemChatEndpoints
            endpoints.toolExecutions.clear()

            // Register a test tool
            endpoints.availableTools["externalTool"] = TestChatTool("externalTool")

            // Create a conversation
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                createdAt = now()
            )
            TestChatServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // Create a REJECTED tool request message
            val toolMessage = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.ToolRequest,
                content = "Execute external tool",
                tool = ToolRequestData(
                    toolName = "externalTool",
                    arguments = """{"data": "test"}""",
                    requiresApproval = true,
                    approval = ToolApproval(
                        approved = false,  // Rejected
                        approvedBy = auth.rawId,
                        approvedAt = now()
                    )
                ),
                createdAt = now()
            )
            TestChatServer.chatEndpoints.messageInfo.baseTable().insertOne(toolMessage)

            // Call triggerToolExecution - should do nothing for rejected request
            TestChatServer.chatEndpoints.triggerToolExecution(access, toolMessage)

            assertTrue(endpoints.toolExecutions.isEmpty(),
                "triggerToolExecution should not execute rejected tool requests")

            TestChatServer.TestSubjectAuth.clearSubjects()
        }
    }

    @Test
    fun testTriggerContinueResponseMethod() = runBlocking {
        TestChatServer.test(settings = { TestChatServer.database set Database.Settings("ram") }) {
            val subject = TestSubject(email = "test@example.com")
            TestChatServer.TestSubjectAuth.addSubject(subject)
            val auth = TestChatServer.TestSubjectAuth.testAuth(subject)
            val access = AuthAccess(auth)
            val endpoints = TestChatServer.chatEndpoints as TestSystemChatEndpoints
            endpoints.respondCalls.clear()

            // Create a conversation
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                autoProcess = true,
                createdAt = now()
            )
            TestChatServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // Create a rejected tool request message
            val toolMessage = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.ToolRequest,
                content = "Execute tool",
                tool = ToolRequestData(
                    toolName = "someTool",
                    arguments = "{}",
                    requiresApproval = true,
                    approval = ToolApproval(
                        approved = false,
                        approvedBy = auth.rawId,
                        approvedAt = now(),
                        reason = "Too dangerous"
                    )
                ),
                createdAt = now()
            )
            TestChatServer.chatEndpoints.messageInfo.baseTable().insertOne(toolMessage)

            // Call triggerContinueResponse (simulating ExternalChannelSupport after rejection)
            TestChatServer.chatEndpoints.triggerContinueResponse(access, toolMessage)

            // respond() should be called to continue the conversation
            assertTrue(endpoints.respondCalls.isNotEmpty(),
                "triggerContinueResponse should trigger respond() for rejected tools")

            TestChatServer.TestSubjectAuth.clearSubjects()
        }
    }

    @Test
    fun testChannelFieldStoredCorrectly() = runBlocking {
        TestChatServer.test(settings = { TestChatServer.database set Database.Settings("ram") }) {
            val subject = TestSubject(email = "test@example.com")
            TestChatServer.TestSubjectAuth.addSubject(subject)
            val auth = TestChatServer.TestSubjectAuth.testAuth(subject)
            val access = AuthAccess(auth)

            // Create a conversation
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                autoProcess = false,
                createdAt = now()
            )
            TestChatServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // Test different channel types
            val channels = listOf("sms", "email", "voice", "phone", null)

            for (channel in channels) {
                val message = SystemChatMessage(
                    conversationId = conversation._id,
                    subjectId = auth.rawId.toString(),
                    role = SystemChatMessage.Role.User,
                    channel = channel,
                    content = "Test message via $channel",
                    createdAt = now()
                )
                val created = TestChatServer.chatEndpoints.messageInfo.baseTable().insertOne(message)!!
                assertEquals(channel, created.channel, "Channel should be stored correctly: $channel")
            }

            TestChatServer.TestSubjectAuth.clearSubjects()
        }
    }
}
