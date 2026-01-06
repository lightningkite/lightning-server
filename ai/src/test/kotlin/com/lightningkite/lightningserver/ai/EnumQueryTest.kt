package com.lightningkite.lightningserver.ai

import com.lightningkite.lightningserver.ai.models.*
import com.lightningkite.lightningserver.auth.testAuth
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.typed.AuthAccess
import com.lightningkite.services.database.*
import com.lightningkite.services.database.jsonfile.JsonFileDatabase
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.BeforeClass
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests for querying and modifying enum fields in database models.
 * Specifically tests SystemChatMessage.Role enum operations.
 *
 * These tests verify that enum values can be properly used in:
 * - Query conditions (eq, neq, inside)
 * - Modifications (assign)
 * - Combined conditions with nested fields
 */
class EnumQueryTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun setup() {
            // Ensure database implementations are loaded
            JsonFileDatabase
        }
    }

    @Test
    fun `query messages by role enum using eq`() = runBlocking {
        TestChatServer.test(settings = { TestChatServer.database set Database.Settings("ram") }) {
            val subject = TestSubject(email = "test@example.com")
            TestChatServer.TestSubjectAuth.addSubject(subject)
            val auth = TestChatServer.TestSubjectAuth.testAuth(subject)
            val access = AuthAccess(auth)

            // Create test conversation
            val conversation = TestChatServer.chatEndpoints.conversationInfo.table(access).insertOne(
                SystemChatConversation(
                    subjectId = auth.rawId.toString(),
                    createdAt = now()
                )
            )!!

            val table = TestChatServer.chatEndpoints.messageInfo.table(access)

            // Insert messages with different roles
            val userMsg = table.insertOne(
                SystemChatMessage(
                    conversationId = conversation._id,
                    subjectId = auth.rawId.toString(),
                    role = SystemChatMessage.Role.User,
                    content = "User message",
                    createdAt = now(),
                    skipAutoResponse = true
                )
            )!!

            val assistantMsg = table.insertOne(
                SystemChatMessage(
                    conversationId = conversation._id,
                    subjectId = auth.rawId.toString(),
                    role = SystemChatMessage.Role.Assistant,
                    content = "Assistant message",
                    createdAt = now(),
                    skipAutoResponse = true
                )
            )!!

            table.insertOne(
                SystemChatMessage(
                    conversationId = conversation._id,
                    subjectId = auth.rawId.toString(),
                    role = SystemChatMessage.Role.System,
                    content = "System message",
                    createdAt = now(),
                    skipAutoResponse = true
                )
            )

            // Query for User messages only
            val userMessages = table.find(
                condition { it.role eq SystemChatMessage.Role.User }
            ).toList()

            assertEquals(1, userMessages.size, "Should find exactly 1 User message")
            assertEquals(userMsg._id, userMessages[0]._id)
            assertEquals(SystemChatMessage.Role.User, userMessages[0].role)

            // Query for Assistant messages only
            val assistantMessages = table.find(
                condition { it.role eq SystemChatMessage.Role.Assistant }
            ).toList()

            assertEquals(1, assistantMessages.size, "Should find exactly 1 Assistant message")
            assertEquals(assistantMsg._id, assistantMessages[0]._id)
            assertEquals(SystemChatMessage.Role.Assistant, assistantMessages[0].role)

            TestChatServer.TestSubjectAuth.clearSubjects()
        }
    }

    @Test
    fun `query messages by role enum with combined conditions`() = runBlocking {
        TestChatServer.test(settings = { TestChatServer.database set Database.Settings("ram") }) {
            val subject = TestSubject(email = "test@example.com")
            TestChatServer.TestSubjectAuth.addSubject(subject)
            val auth = TestChatServer.TestSubjectAuth.testAuth(subject)
            val access = AuthAccess(auth)

            val conversation = TestChatServer.chatEndpoints.conversationInfo.table(access).insertOne(
                SystemChatConversation(
                    subjectId = auth.rawId.toString(),
                    createdAt = now()
                )
            )!!

            val table = TestChatServer.chatEndpoints.messageInfo.table(access)

            table.insertOne(
                SystemChatMessage(
                    conversationId = conversation._id,
                    subjectId = auth.rawId.toString(),
                    role = SystemChatMessage.Role.User,
                    content = "User message",
                    createdAt = now(),
                    skipAutoResponse = true
                )
            )

            val toolRequest = table.insertOne(
                SystemChatMessage(
                    conversationId = conversation._id,
                    subjectId = auth.rawId.toString(),
                    role = SystemChatMessage.Role.ToolRequest,
                    content = "Tool request",
                    createdAt = now(),
                    skipAutoResponse = true,
                    tool = ToolRequestData(
                        toolName = "test_tool",
                        arguments = "{}",
                        requiresApproval = true
                    )
                )
            )!!

            // Query for ToolRequest with requiresApproval = true
            val pendingApprovals = table.find(
                condition {
                    (it.conversationId eq conversation._id) and
                    (it.role eq SystemChatMessage.Role.ToolRequest) and
                    (it.tool.notNull.requiresApproval eq true)
                }
            ).toList()

            assertEquals(1, pendingApprovals.size, "Should find exactly 1 pending approval")
            assertEquals(toolRequest._id, pendingApprovals[0]._id)
            assertEquals(SystemChatMessage.Role.ToolRequest, pendingApprovals[0].role)
            assertNotNull(pendingApprovals[0].tool)
            assertEquals(true, pendingApprovals[0].tool!!.requiresApproval)

            TestChatServer.TestSubjectAuth.clearSubjects()
        }
    }

    @Test
    fun `modify message role enum`() = runBlocking {
        TestChatServer.test(settings = { TestChatServer.database set Database.Settings("ram") }) {
            val subject = TestSubject(email = "test@example.com")
            TestChatServer.TestSubjectAuth.addSubject(subject)
            val auth = TestChatServer.TestSubjectAuth.testAuth(subject)
            val access = AuthAccess(auth)

            val conversation = TestChatServer.chatEndpoints.conversationInfo.table(access).insertOne(
                SystemChatConversation(
                    subjectId = auth.rawId.toString(),
                    createdAt = now()
                )
            )!!

            val table = TestChatServer.chatEndpoints.messageInfo.table(access)

            // Insert a User message
            val message = table.insertOne(
                SystemChatMessage(
                    conversationId = conversation._id,
                    subjectId = auth.rawId.toString(),
                    role = SystemChatMessage.Role.User,
                    content = "Original message",
                    createdAt = now(),
                    skipAutoResponse = true
                )
            )!!

            // Verify initial role
            assertEquals(SystemChatMessage.Role.User, message.role)

            // Change role to System
            table.updateOneById(
                message._id,
                modification {
                    it.role assign SystemChatMessage.Role.System
                }
            )

            // Verify role was changed
            val updated = table.get(message._id)
            assertNotNull(updated)
            assertEquals(SystemChatMessage.Role.System, updated.role)

            TestChatServer.TestSubjectAuth.clearSubjects()
        }
    }

    @Test
    fun `query with role enum using inside condition`() = runBlocking {
        TestChatServer.test(settings = { TestChatServer.database set Database.Settings("ram") }) {
            val subject = TestSubject(email = "test@example.com")
            TestChatServer.TestSubjectAuth.addSubject(subject)
            val auth = TestChatServer.TestSubjectAuth.testAuth(subject)
            val access = AuthAccess(auth)

            val conversation = TestChatServer.chatEndpoints.conversationInfo.table(access).insertOne(
                SystemChatConversation(
                    subjectId = auth.rawId.toString(),
                    createdAt = now()
                )
            )!!

            val table = TestChatServer.chatEndpoints.messageInfo.table(access)

            // Insert messages with different roles
            table.insertOne(
                SystemChatMessage(
                    conversationId = conversation._id,
                    subjectId = auth.rawId.toString(),
                    role = SystemChatMessage.Role.User,
                    content = "User message",
                    createdAt = now(),
                    skipAutoResponse = true
                )
            )

            table.insertOne(
                SystemChatMessage(
                    conversationId = conversation._id,
                    subjectId = auth.rawId.toString(),
                    role = SystemChatMessage.Role.Assistant,
                    content = "Assistant message",
                    createdAt = now(),
                    skipAutoResponse = true
                )
            )

            table.insertOne(
                SystemChatMessage(
                    conversationId = conversation._id,
                    subjectId = auth.rawId.toString(),
                    role = SystemChatMessage.Role.System,
                    content = "System message",
                    createdAt = now(),
                    skipAutoResponse = true
                )
            )

            table.insertOne(
                SystemChatMessage(
                    conversationId = conversation._id,
                    subjectId = auth.rawId.toString(),
                    role = SystemChatMessage.Role.ToolRequest,
                    content = "Tool request",
                    createdAt = now(),
                    skipAutoResponse = true
                )
            )

            // Query for messages with role in [User, Assistant]
            val conversationMessages = table.find(
                condition {
                    (it.conversationId eq conversation._id) and
                    (it.role inside listOf(SystemChatMessage.Role.User, SystemChatMessage.Role.Assistant))
                }
            ).toList()

            assertEquals(2, conversationMessages.size, "Should find 2 messages (User + Assistant)")
            assertEquals(
                setOf(SystemChatMessage.Role.User, SystemChatMessage.Role.Assistant),
                conversationMessages.map { it.role }.toSet()
            )

            TestChatServer.TestSubjectAuth.clearSubjects()
        }
    }

    @Test
    fun `query with role enum not equal`() = runBlocking {
        TestChatServer.test(settings = { TestChatServer.database set Database.Settings("ram") }) {
            val subject = TestSubject(email = "test@example.com")
            TestChatServer.TestSubjectAuth.addSubject(subject)
            val auth = TestChatServer.TestSubjectAuth.testAuth(subject)
            val access = AuthAccess(auth)

            val conversation = TestChatServer.chatEndpoints.conversationInfo.table(access).insertOne(
                SystemChatConversation(
                    subjectId = auth.rawId.toString(),
                    createdAt = now()
                )
            )!!

            val table = TestChatServer.chatEndpoints.messageInfo.table(access)

            val userMsg = table.insertOne(
                SystemChatMessage(
                    conversationId = conversation._id,
                    subjectId = auth.rawId.toString(),
                    role = SystemChatMessage.Role.User,
                    content = "User message",
                    createdAt = now(),
                    skipAutoResponse = true
                )
            )!!

            table.insertOne(
                SystemChatMessage(
                    conversationId = conversation._id,
                    subjectId = auth.rawId.toString(),
                    role = SystemChatMessage.Role.ToolRequest,
                    content = "Tool request",
                    createdAt = now(),
                    skipAutoResponse = true
                )
            )

            // Query for non-ToolRequest messages
            val nonToolMessages = table.find(
                condition {
                    (it.conversationId eq conversation._id) and
                    (it.role neq SystemChatMessage.Role.ToolRequest)
                }
            ).toList()

            assertEquals(1, nonToolMessages.size, "Should find 1 non-ToolRequest message")
            assertEquals(userMsg._id, nonToolMessages[0]._id)
            assertEquals(SystemChatMessage.Role.User, nonToolMessages[0].role)

            TestChatServer.TestSubjectAuth.clearSubjects()
        }
    }
}
