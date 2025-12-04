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
    override val description: String = "Test tool",
    private val onExecute: (String) -> String = { args -> "Executed $name with args: $args" }
) : AutoApprovedTool<TestSubject, String>() {
    override val argsSerializer: KSerializer<String> = String.serializer()

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

    override suspend fun respond(
        serverRuntime: ServerRuntime,
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

    override fun findToolByName(
        serverRuntime: ServerRuntime,
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
}
