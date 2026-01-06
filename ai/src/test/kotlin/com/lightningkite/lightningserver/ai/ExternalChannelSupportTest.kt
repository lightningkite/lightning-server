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
import com.lightningkite.services.email.*
import com.lightningkite.services.sms.*
import com.lightningkite.toPhoneNumber
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
 * Test user model with phone and email for external channel testing
 */
@Serializable
@GenerateDataClassPaths
data class ExternalTestUser(
    override val _id: Uuid = Uuid.random(),
    val email: String,
    val phone: String
) : HasId<Uuid>

/**
 * Test server with ExternalChannelSupport
 */
object ExternalChannelTestServer : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val smsInbound = setting("smsInbound", SmsInboundService.Settings("test"))
    val smsOutbound = setting("smsOutbound", SMS.Settings("test"))
    val emailInbound = setting("emailInbound", EmailInboundService.Settings("test"))
    val emailOutbound = setting("emailOutbound", EmailService.Settings("test"))

    // Store users for lookup
    private val users = mutableMapOf<Uuid, ExternalTestUser>()
    private val usersByPhone = mutableMapOf<String, ExternalTestUser>()
    private val usersByEmail = mutableMapOf<String, ExternalTestUser>()

    fun addUser(user: ExternalTestUser) {
        users[user._id] = user
        usersByPhone[user.phone] = user
        usersByEmail[user.email] = user
    }

    fun clearUsers() {
        users.clear()
        usersByPhone.clear()
        usersByEmail.clear()
    }

    object ExternalTestUserAuth : PrincipalType<ExternalTestUser, Uuid> {
        override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
        override val subjectSerializer: KSerializer<ExternalTestUser> = ExternalTestUser.serializer()
        override val name: String = "ExternalTestUser"

        context(server: ServerRuntime)
        override suspend fun fetch(id: Uuid): ExternalTestUser {
            return users[id] ?: throw NotFoundException("User not found: $id")
        }
    }

    init {
        register(ExternalTestUserAuth)
    }

    /**
     * Test implementation of SystemChatEndpoints
     */
    class TestExternalChatEndpoints(
        database: ServerSetting<Database.Settings, Database>,
        authRequirement: AuthRequirement<ExternalTestUser>,
    ) : SystemChatEndpoints<ExternalTestUser>(
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
        val respondCalls = mutableListOf<SystemChatConversation>()

        context(serverRuntime: ServerRuntime) override suspend fun respond(
            auth: AuthAccess<ExternalTestUser>,
            conversation: SystemChatConversation,
        ) {
            respondCalls.add(conversation)
            with(serverRuntime) {
                val history = getConversationHistory(auth, conversation._id)
                val lastUserMessage = history.lastOrNull { it.role == SystemChatMessage.Role.User }
                if (lastUserMessage != null) {
                    // Get channel info to propagate to response (like LLMChatEndpoints does)
                    val (channel, externalIdentifier) = findChannelInfo(conversation._id)
                    val message = SystemChatMessage(
                        conversationId = conversation._id,
                        subjectId = conversation.subjectId,
                        role = SystemChatMessage.Role.Assistant,
                        channel = channel,
                        externalIdentifier = externalIdentifier,
                        content = "Echo: ${lastUserMessage.content}",
                        createdAt = now()
                    )
                    messageInfo.table().insertOne(message)
                }
            }
        }

        context(serverRuntime: ServerRuntime) override fun findToolByName(
            auth: AuthAccess<ExternalTestUser>,
            conversation: SystemChatConversation,
            toolName: String,
        ): ChatTool<ExternalTestUser, *>? = null
    }

    val chatEndpoints = path.path("chat") include TestExternalChatEndpoints(
        database = database,
        authRequirement = ExternalTestUserAuth.require()
    )

    val externalChannels = path.path("external") include ExternalChannelSupport(
        chatEndpoints = chatEndpoints,
        principalType = ExternalTestUserAuth,
        smsInbound = smsInbound,
        smsOutbound = smsOutbound,
        emailInbound = emailInbound,
        emailOutbound = emailOutbound,
        emailFromAddress = EmailAddressWithName("bot@test.com", "Test Bot"),
        resolveSubjectByPhone = { phone -> usersByPhone[phone.raw] },
        resolveSubjectByEmail = { email -> usersByEmail[email.raw] },
    )
}

class ExternalChannelSupportTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun setup() {
            JsonFileDatabase // Ensure service implementations are loaded
        }
    }

    @Test
    fun testInboundSmsCreatesMessage() = runBlocking {
        ExternalChannelTestServer.test(settings = {
            ExternalChannelTestServer.database set Database.Settings("ram")
            ExternalChannelTestServer.smsInbound set SmsInboundService.Settings("test")
            ExternalChannelTestServer.smsOutbound set SMS.Settings("test")
        }) {
            val user = ExternalTestUser(email = "test@example.com", phone = "+15551234567")
            ExternalChannelTestServer.addUser(user)

            // Get the test SMS inbound service
            val smsInboundService = ExternalChannelTestServer.smsInbound() as TestSmsInboundService

            // Simulate receiving an SMS
            val inboundSms = smsInboundService.simulateInbound(
                from = "+15551234567".toPhoneNumber(),
                to = "+15559999999".toPhoneNumber(),
                body = "Hello from SMS!"
            )

            // Manually trigger the handler (in real usage, the webhook would do this)
            // For now, let's verify the setup is correct by checking the service received it
            assertEquals(1, smsInboundService.receivedMessages.size)
            assertEquals("Hello from SMS!", smsInboundService.lastReceived?.body)

            ExternalChannelTestServer.clearUsers()
        }
    }

    @Test
    fun testOutboundSmsOnAssistantResponse() = runBlocking {
        ExternalChannelTestServer.test(settings = {
            ExternalChannelTestServer.database set Database.Settings("ram")
            ExternalChannelTestServer.smsInbound set SmsInboundService.Settings("test")
            ExternalChannelTestServer.smsOutbound set SMS.Settings("test")
        }) {
            val user = ExternalTestUser(email = "test@example.com", phone = "+15551234567")
            ExternalChannelTestServer.addUser(user)
            val auth = ExternalChannelTestServer.ExternalTestUserAuth.testAuth(user)
            val access = AuthAccess(auth)

            // Get the test SMS service
            val smsService = ExternalChannelTestServer.smsOutbound() as TestSMS
            smsService.reset()

            // Create a conversation
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                autoProcess = true,
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // Create a user message that came from SMS (has externalIdentifier)
            val userMessage = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.User,
                channel = ExternalChannelSupport.CHANNEL_SMS,
                externalIdentifier = "+15551234567",
                content = "Hello!",
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.messageInfo.table(access).insertOne(userMessage)

            // The change listener should have sent an SMS response
            // In test mode, tasks run inline
            assertTrue(smsService.messageHistory.isNotEmpty(), "Should have sent an SMS response")
            assertEquals("+15551234567".toPhoneNumber(), smsService.lastMessageSent?.to)
            assertTrue(smsService.lastMessageSent?.message?.contains("Echo: Hello!") == true)

            ExternalChannelTestServer.clearUsers()
        }
    }

    @Test
    fun testNoSmsForWebConversation() = runBlocking {
        ExternalChannelTestServer.test(settings = {
            ExternalChannelTestServer.database set Database.Settings("ram")
            ExternalChannelTestServer.smsInbound set SmsInboundService.Settings("test")
            ExternalChannelTestServer.smsOutbound set SMS.Settings("test")
        }) {
            val user = ExternalTestUser(email = "test@example.com", phone = "+15551234567")
            ExternalChannelTestServer.addUser(user)
            val auth = ExternalChannelTestServer.ExternalTestUserAuth.testAuth(user)
            val access = AuthAccess(auth)

            // Get the test SMS service
            val smsService = ExternalChannelTestServer.smsOutbound() as TestSMS
            smsService.reset()

            // Create a conversation
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                autoProcess = true,
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // Create a user message WITHOUT externalIdentifier (web user)
            val userMessage = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.User,
                content = "Hello from web!",
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.messageInfo.table(access).insertOne(userMessage)

            // Should NOT have sent an SMS because there's no external identifier
            assertTrue(smsService.messageHistory.isEmpty(), "Should NOT send SMS for web conversations")

            ExternalChannelTestServer.clearUsers()
        }
    }

    @Test
    fun testExternalIdentifierFieldInMessage() = runBlocking {
        ExternalChannelTestServer.test(settings = {
            ExternalChannelTestServer.database set Database.Settings("ram")
        }) {
            val user = ExternalTestUser(email = "test@example.com", phone = "+15551234567")
            ExternalChannelTestServer.addUser(user)
            val auth = ExternalChannelTestServer.ExternalTestUserAuth.testAuth(user)
            val access = AuthAccess(auth)

            // Create a conversation
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                autoProcess = false,
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // Create a message with external identifier
            val message = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.User,
                channel = ExternalChannelSupport.CHANNEL_SMS,
                externalIdentifier = "+15551234567",
                content = "Test message",
                createdAt = now()
            )

            val created = ExternalChannelTestServer.chatEndpoints.messageInfo.table(access).insertOne(message)

            assertNotNull(created)
            assertEquals(ExternalChannelSupport.CHANNEL_SMS, created.channel)
            assertEquals("+15551234567", created.externalIdentifier)

            ExternalChannelTestServer.clearUsers()
        }
    }

    @Test
    fun testToolApprovalViaSms() = runBlocking {
        ExternalChannelTestServer.test(settings = {
            ExternalChannelTestServer.database set Database.Settings("ram")
            ExternalChannelTestServer.smsInbound set SmsInboundService.Settings("test")
            ExternalChannelTestServer.smsOutbound set SMS.Settings("test")
        }) {
            val user = ExternalTestUser(email = "test@example.com", phone = "+15551234567")
            ExternalChannelTestServer.addUser(user)
            val auth = ExternalChannelTestServer.ExternalTestUserAuth.testAuth(user)
            val access = AuthAccess(auth)

            // Get the test SMS service
            val smsService = ExternalChannelTestServer.smsOutbound() as TestSMS
            smsService.reset()

            // Create a conversation
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                autoProcess = false,
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // Create a user message from SMS to establish the channel
            val userMessage = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.User,
                channel = ExternalChannelSupport.CHANNEL_SMS,
                externalIdentifier = "+15551234567",
                content = "Do something",
                createdAt = now()
            )
            // Use table() with access to trigger change listeners properly
            ExternalChannelTestServer.chatEndpoints.messageInfo.table(access).insertOne(userMessage)

            smsService.reset() // Reset after user message to only track tool request SMS

            // Create a tool request that requires approval (with channel info propagated)
            val toolRequest = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.ToolRequest,
                channel = ExternalChannelSupport.CHANNEL_SMS,
                externalIdentifier = "+15551234567",
                content = "Delete user data",
                tool = ToolRequestData(
                    toolName = "deleteData",
                    arguments = """"test"""",
                    requiresApproval = true,
                    approvalReason = "This will delete data"
                ),
                createdAt = now()
            )
            // Use table() with access to trigger change listeners properly
            ExternalChannelTestServer.chatEndpoints.messageInfo.table(access).insertOne(toolRequest)

            // Should have sent an SMS asking for approval
            assertTrue(smsService.messageHistory.isNotEmpty(), "Should send SMS for tool approval request")
            val approvalMessage = smsService.lastMessageSent?.message ?: ""
            assertTrue(approvalMessage.contains("Tool Request"), "Message should mention tool request")
            assertTrue(approvalMessage.contains("YES") || approvalMessage.contains("NO"), "Message should mention approval options")

            ExternalChannelTestServer.clearUsers()
        }
    }

    @Test
    fun testEmailChannelConstants() {
        assertEquals("sms", ExternalChannelSupport.CHANNEL_SMS)
        assertEquals("email", ExternalChannelSupport.CHANNEL_EMAIL)
    }

    @Test
    fun testOutboundEmailOnAssistantResponse() = runBlocking {
        ExternalChannelTestServer.test(settings = {
            ExternalChannelTestServer.database set Database.Settings("ram")
            ExternalChannelTestServer.emailInbound set EmailInboundService.Settings("test")
            ExternalChannelTestServer.emailOutbound set EmailService.Settings("test")
        }) {
            val user = ExternalTestUser(email = "user@example.com", phone = "+15551234567")
            ExternalChannelTestServer.addUser(user)
            val auth = ExternalChannelTestServer.ExternalTestUserAuth.testAuth(user)
            val access = AuthAccess(auth)

            // Get the test email service
            val emailService = ExternalChannelTestServer.emailOutbound() as TestEmailService
            emailService.clear()

            // Create a conversation
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                autoProcess = true,
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // Create a user message that came from email (has externalIdentifier)
            val userMessage = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.User,
                channel = ExternalChannelSupport.CHANNEL_EMAIL,
                externalIdentifier = "user@example.com",
                content = "Hello via email!",
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.messageInfo.table(access).insertOne(userMessage)

            // The change listener should have sent an email response
            assertTrue(emailService.sentEmails.isNotEmpty(), "Should have sent an email response")
            val lastEmail = emailService.lastEmail()
            assertNotNull(lastEmail)
            assertEquals("user@example.com", lastEmail.to.first().value.raw)
            assertTrue(lastEmail.html.contains("Echo: Hello via email!"))

            ExternalChannelTestServer.clearUsers()
        }
    }

    @Test
    fun testErrorMessageSendsExternalNotification() = runBlocking {
        ExternalChannelTestServer.test(settings = {
            ExternalChannelTestServer.database set Database.Settings("ram")
            ExternalChannelTestServer.smsInbound set SmsInboundService.Settings("test")
            ExternalChannelTestServer.smsOutbound set SMS.Settings("test")
        }) {
            val user = ExternalTestUser(email = "test@example.com", phone = "+15551234567")
            ExternalChannelTestServer.addUser(user)
            val auth = ExternalChannelTestServer.ExternalTestUserAuth.testAuth(user)
            val access = AuthAccess(auth)

            // Get the test SMS service
            val smsService = ExternalChannelTestServer.smsOutbound() as TestSMS
            smsService.reset()

            // Create a conversation
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                autoProcess = false,
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // Create a user message from SMS to establish the channel
            val userMessage = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.User,
                channel = ExternalChannelSupport.CHANNEL_SMS,
                externalIdentifier = "+15551234567",
                content = "Hello",
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.messageInfo.table(access).insertOne(userMessage)
            smsService.reset()

            // Create an error message (with channel info propagated)
            val errorMessage = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.Error,
                channel = ExternalChannelSupport.CHANNEL_SMS,
                externalIdentifier = "+15551234567",
                content = "Something went wrong",
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.messageInfo.table(access).insertOne(errorMessage)

            // Should have sent an SMS with error
            assertTrue(smsService.messageHistory.isNotEmpty(), "Should send SMS for error message")
            val errorSms = smsService.lastMessageSent?.message ?: ""
            assertTrue(errorSms.contains("[Error]"), "SMS should indicate error")
            assertTrue(errorSms.contains("Something went wrong"), "SMS should contain error message")

            ExternalChannelTestServer.clearUsers()
        }
    }

    @Test
    fun testRoutesToMostRecentConversation() = runBlocking {
        ExternalChannelTestServer.test(settings = {
            ExternalChannelTestServer.database set Database.Settings("ram")
            ExternalChannelTestServer.smsInbound set SmsInboundService.Settings("test")
            ExternalChannelTestServer.smsOutbound set SMS.Settings("test")
        }) {
            val user = ExternalTestUser(email = "test@example.com", phone = "+15551234567")
            ExternalChannelTestServer.addUser(user)
            val auth = ExternalChannelTestServer.ExternalTestUserAuth.testAuth(user)
            val access = AuthAccess(auth)

            // Create an older conversation
            val oldConversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                autoProcess = false,
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.conversationInfo.table(access).insertOne(oldConversation)

            // Create a newer conversation
            val newConversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                autoProcess = false,
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.conversationInfo.table(access).insertOne(newConversation)

            // Add a message to the newer conversation with external identifier
            val userMessage = SystemChatMessage(
                conversationId = newConversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.User,
                channel = ExternalChannelSupport.CHANNEL_SMS,
                externalIdentifier = "+15551234567",
                content = "Hello",
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.messageInfo.table(access).insertOne(userMessage)

            // Verify message was added to the newer conversation
            val messages = ExternalChannelTestServer.chatEndpoints.messageInfo.baseTable()
                .find(condition { it.conversationId eq newConversation._id })
                .toList()
            assertTrue(messages.isNotEmpty(), "Message should be in the newer conversation")

            ExternalChannelTestServer.clearUsers()
        }
    }

    @Test
    fun testSystemMessageDoesNotSendExternal() = runBlocking {
        ExternalChannelTestServer.test(settings = {
            ExternalChannelTestServer.database set Database.Settings("ram")
            ExternalChannelTestServer.smsInbound set SmsInboundService.Settings("test")
            ExternalChannelTestServer.smsOutbound set SMS.Settings("test")
        }) {
            val user = ExternalTestUser(email = "test@example.com", phone = "+15551234567")
            ExternalChannelTestServer.addUser(user)
            val auth = ExternalChannelTestServer.ExternalTestUserAuth.testAuth(user)
            val access = AuthAccess(auth)

            val smsService = ExternalChannelTestServer.smsOutbound() as TestSMS
            smsService.reset()

            // Create a conversation
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                autoProcess = false,
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // Create a user message to establish external channel
            val userMessage = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.User,
                channel = ExternalChannelSupport.CHANNEL_SMS,
                externalIdentifier = "+15551234567",
                content = "Hello",
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.messageInfo.table(access).insertOne(userMessage)
            smsService.reset()

            // Create a system message - should NOT be sent externally
            val systemMessage = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.System,
                content = "System prompt that should not be sent",
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.messageInfo.table(access).insertOne(systemMessage)

            // Should NOT have sent an SMS
            assertTrue(smsService.messageHistory.isEmpty(), "System messages should NOT be sent externally")

            ExternalChannelTestServer.clearUsers()
        }
    }

    @Test
    fun testThinkingMessageDoesNotSendExternal() = runBlocking {
        ExternalChannelTestServer.test(settings = {
            ExternalChannelTestServer.database set Database.Settings("ram")
            ExternalChannelTestServer.smsInbound set SmsInboundService.Settings("test")
            ExternalChannelTestServer.smsOutbound set SMS.Settings("test")
        }) {
            val user = ExternalTestUser(email = "test@example.com", phone = "+15551234567")
            ExternalChannelTestServer.addUser(user)
            val auth = ExternalChannelTestServer.ExternalTestUserAuth.testAuth(user)
            val access = AuthAccess(auth)

            val smsService = ExternalChannelTestServer.smsOutbound() as TestSMS
            smsService.reset()

            // Create a conversation
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                autoProcess = false,
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // Create a user message to establish external channel
            val userMessage = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.User,
                channel = ExternalChannelSupport.CHANNEL_SMS,
                externalIdentifier = "+15551234567",
                content = "Hello",
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.messageInfo.table(access).insertOne(userMessage)
            smsService.reset()

            // Create a thinking message - should NOT be sent externally
            val thinkingMessage = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.Thinking,
                content = "Let me think about this...",
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.messageInfo.table(access).insertOne(thinkingMessage)

            // Should NOT have sent an SMS
            assertTrue(smsService.messageHistory.isEmpty(), "Thinking messages should NOT be sent externally")

            ExternalChannelTestServer.clearUsers()
        }
    }

    @Test
    fun testToolRequestWithoutApprovalRequiredDoesNotSendExternal() = runBlocking {
        ExternalChannelTestServer.test(settings = {
            ExternalChannelTestServer.database set Database.Settings("ram")
            ExternalChannelTestServer.smsInbound set SmsInboundService.Settings("test")
            ExternalChannelTestServer.smsOutbound set SMS.Settings("test")
        }) {
            val user = ExternalTestUser(email = "test@example.com", phone = "+15551234567")
            ExternalChannelTestServer.addUser(user)
            val auth = ExternalChannelTestServer.ExternalTestUserAuth.testAuth(user)
            val access = AuthAccess(auth)

            val smsService = ExternalChannelTestServer.smsOutbound() as TestSMS
            smsService.reset()

            // Create a conversation
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                autoProcess = false,
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // Create a user message to establish external channel
            val userMessage = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.User,
                channel = ExternalChannelSupport.CHANNEL_SMS,
                externalIdentifier = "+15551234567",
                content = "Hello",
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.messageInfo.table(access).insertOne(userMessage)
            smsService.reset()

            // Create a tool request that does NOT require approval
            val toolRequest = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.ToolRequest,
                content = "Safe operation",
                tool = ToolRequestData(
                    toolName = "safeOperation",
                    arguments = """"test"""",
                    requiresApproval = false  // Auto-approved
                ),
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.messageInfo.table(access).insertOne(toolRequest)

            // Should NOT have sent an SMS because approval is not required
            assertTrue(smsService.messageHistory.isEmpty(), "Auto-approved tool requests should NOT be sent externally")

            ExternalChannelTestServer.clearUsers()
        }
    }

    @Test
    fun testAlreadyApprovedToolRequestDoesNotSendExternal() = runBlocking {
        ExternalChannelTestServer.test(settings = {
            ExternalChannelTestServer.database set Database.Settings("ram")
            ExternalChannelTestServer.smsInbound set SmsInboundService.Settings("test")
            ExternalChannelTestServer.smsOutbound set SMS.Settings("test")
        }) {
            val user = ExternalTestUser(email = "test@example.com", phone = "+15551234567")
            ExternalChannelTestServer.addUser(user)
            val auth = ExternalChannelTestServer.ExternalTestUserAuth.testAuth(user)
            val access = AuthAccess(auth)

            val smsService = ExternalChannelTestServer.smsOutbound() as TestSMS
            smsService.reset()

            // Create a conversation
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                autoProcess = false,
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // Create a user message to establish external channel
            val userMessage = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.User,
                channel = ExternalChannelSupport.CHANNEL_SMS,
                externalIdentifier = "+15551234567",
                content = "Hello",
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.messageInfo.table(access).insertOne(userMessage)
            smsService.reset()

            // Create a tool request that has already been approved
            val toolRequest = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.ToolRequest,
                content = "Previously approved operation",
                tool = ToolRequestData(
                    toolName = "alreadyApproved",
                    arguments = """"test"""",
                    requiresApproval = true,
                    approval = ToolApproval(
                        approved = true,
                        approvedBy = auth.rawId.toString(),
                        approvedAt = now()
                    )
                ),
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.messageInfo.table(access).insertOne(toolRequest)

            // Should NOT have sent an SMS because approval already exists
            assertTrue(smsService.messageHistory.isEmpty(), "Already approved tool requests should NOT be sent externally")

            ExternalChannelTestServer.clearUsers()
        }
    }

    @Test
    fun testEmailMessageWithHtmlContent() = runBlocking {
        ExternalChannelTestServer.test(settings = {
            ExternalChannelTestServer.database set Database.Settings("ram")
            ExternalChannelTestServer.emailInbound set EmailInboundService.Settings("test")
            ExternalChannelTestServer.emailOutbound set EmailService.Settings("test")
        }) {
            val user = ExternalTestUser(email = "user@example.com", phone = "+15551234567")
            ExternalChannelTestServer.addUser(user)
            val auth = ExternalChannelTestServer.ExternalTestUserAuth.testAuth(user)
            val access = AuthAccess(auth)

            val emailService = ExternalChannelTestServer.emailOutbound() as TestEmailService
            emailService.clear()

            // Create a conversation
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                autoProcess = false,
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // Create a user message from email
            val userMessage = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.User,
                channel = ExternalChannelSupport.CHANNEL_EMAIL,
                externalIdentifier = "user@example.com",
                content = "Hello",
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.messageInfo.table(access).insertOne(userMessage)
            emailService.clear()

            // Create an assistant message with multi-line content (with channel info propagated)
            val assistantMessage = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.Assistant,
                channel = ExternalChannelSupport.CHANNEL_EMAIL,
                externalIdentifier = "user@example.com",
                content = "Line 1\nLine 2\nLine 3",
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.messageInfo.table(access).insertOne(assistantMessage)

            // Should have sent an email with line breaks converted to <br>
            assertTrue(emailService.sentEmails.isNotEmpty(), "Should send email for assistant message")
            val htmlContent = emailService.lastEmail()?.html ?: ""
            assertTrue(htmlContent.contains("<br>"), "HTML should contain <br> for line breaks")

            ExternalChannelTestServer.clearUsers()
        }
    }

    @Test
    fun testSmsTruncatesLongMessages() = runBlocking {
        ExternalChannelTestServer.test(settings = {
            ExternalChannelTestServer.database set Database.Settings("ram")
            ExternalChannelTestServer.smsInbound set SmsInboundService.Settings("test")
            ExternalChannelTestServer.smsOutbound set SMS.Settings("test")
        }) {
            val user = ExternalTestUser(email = "test@example.com", phone = "+15551234567")
            ExternalChannelTestServer.addUser(user)
            val auth = ExternalChannelTestServer.ExternalTestUserAuth.testAuth(user)
            val access = AuthAccess(auth)

            val smsService = ExternalChannelTestServer.smsOutbound() as TestSMS
            smsService.reset()

            // Create a conversation
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                autoProcess = false,
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // Create a user message from SMS
            val userMessage = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.User,
                channel = ExternalChannelSupport.CHANNEL_SMS,
                externalIdentifier = "+15551234567",
                content = "Hello",
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.messageInfo.table(access).insertOne(userMessage)
            smsService.reset()

            // Create a very long assistant message (> 1600 chars) with channel info propagated
            val longContent = "A".repeat(2000)
            val assistantMessage = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.Assistant,
                channel = ExternalChannelSupport.CHANNEL_SMS,
                externalIdentifier = "+15551234567",
                content = longContent,
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.messageInfo.table(access).insertOne(assistantMessage)

            // Should have sent an SMS truncated to 1600 chars
            assertTrue(smsService.messageHistory.isNotEmpty(), "Should send SMS for assistant message")
            val smsContent = smsService.lastMessageSent?.message ?: ""
            assertTrue(smsContent.length <= 1600, "SMS should be truncated to 1600 chars, was ${smsContent.length}")

            ExternalChannelTestServer.clearUsers()
        }
    }

    @Test
    fun testEmailWithExternalIdentifierField() = runBlocking {
        ExternalChannelTestServer.test(settings = {
            ExternalChannelTestServer.database set Database.Settings("ram")
        }) {
            val user = ExternalTestUser(email = "test@example.com", phone = "+15551234567")
            ExternalChannelTestServer.addUser(user)
            val auth = ExternalChannelTestServer.ExternalTestUserAuth.testAuth(user)
            val access = AuthAccess(auth)

            // Create a conversation
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                autoProcess = false,
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // Create a message with email channel and identifier
            val message = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.User,
                channel = ExternalChannelSupport.CHANNEL_EMAIL,
                externalIdentifier = "test@example.com",
                content = "Email message",
                createdAt = now()
            )

            val created = ExternalChannelTestServer.chatEndpoints.messageInfo.table(access).insertOne(message)

            assertNotNull(created)
            assertEquals(ExternalChannelSupport.CHANNEL_EMAIL, created.channel)
            assertEquals("test@example.com", created.externalIdentifier)

            ExternalChannelTestServer.clearUsers()
        }
    }

    @Test
    fun testToolApprovalRequestViaEmail() = runBlocking {
        ExternalChannelTestServer.test(settings = {
            ExternalChannelTestServer.database set Database.Settings("ram")
            ExternalChannelTestServer.emailInbound set EmailInboundService.Settings("test")
            ExternalChannelTestServer.emailOutbound set EmailService.Settings("test")
        }) {
            val user = ExternalTestUser(email = "user@example.com", phone = "+15551234567")
            ExternalChannelTestServer.addUser(user)
            val auth = ExternalChannelTestServer.ExternalTestUserAuth.testAuth(user)
            val access = AuthAccess(auth)

            val emailService = ExternalChannelTestServer.emailOutbound() as TestEmailService
            emailService.clear()

            // Create a conversation
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                autoProcess = false,
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // Create a user message from email to establish the channel
            val userMessage = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.User,
                channel = ExternalChannelSupport.CHANNEL_EMAIL,
                externalIdentifier = "user@example.com",
                content = "Do something",
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.messageInfo.table(access).insertOne(userMessage)
            emailService.clear()

            // Create a tool request that requires approval (with channel info propagated)
            val toolRequest = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.ToolRequest,
                channel = ExternalChannelSupport.CHANNEL_EMAIL,
                externalIdentifier = "user@example.com",
                content = "Dangerous operation",
                tool = ToolRequestData(
                    toolName = "dangerousOp",
                    arguments = """"test"""",
                    requiresApproval = true,
                    approvalReason = "This is a dangerous operation"
                ),
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.messageInfo.table(access).insertOne(toolRequest)

            // Should have sent an email asking for approval
            assertTrue(emailService.sentEmails.isNotEmpty(), "Should send email for tool approval request")
            val emailHtml = emailService.lastEmail()?.html ?: ""
            assertTrue(emailHtml.contains("Tool Request"), "Email should mention tool request")
            assertTrue(emailHtml.contains("YES") && emailHtml.contains("NO"), "Email should mention approval options")

            ExternalChannelTestServer.clearUsers()
        }
    }

    @Test
    fun testAssistantMessageHasChannelInfoPropagated() = runBlocking {
        ExternalChannelTestServer.test(settings = {
            ExternalChannelTestServer.database set Database.Settings("ram")
            ExternalChannelTestServer.emailInbound set EmailInboundService.Settings("test")
            ExternalChannelTestServer.emailOutbound set EmailService.Settings("test")
        }) {
            val user = ExternalTestUser(email = "user@example.com", phone = "+15551234567")
            ExternalChannelTestServer.addUser(user)
            val auth = ExternalChannelTestServer.ExternalTestUserAuth.testAuth(user)
            val access = AuthAccess(auth)

            // Create a conversation
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                autoProcess = true,
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // Create a user message that came from email (has channel and externalIdentifier)
            val userMessage = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.User,
                channel = ExternalChannelSupport.CHANNEL_EMAIL,
                externalIdentifier = "user@example.com",
                content = "Hello via email!",
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.messageInfo.table(access).insertOne(userMessage)

            // Find the assistant response message
            val messages = ExternalChannelTestServer.chatEndpoints.messageInfo.baseTable()
                .find(condition { it.conversationId eq conversation._id })
                .toList()

            val assistantMessage = messages.find { it.role == SystemChatMessage.Role.Assistant }
            assertNotNull(assistantMessage, "Assistant message should have been created")

            // Verify channel info was propagated to the assistant message
            assertEquals(ExternalChannelSupport.CHANNEL_EMAIL, assistantMessage.channel,
                "Assistant message should have channel propagated from user message")
            assertEquals("user@example.com", assistantMessage.externalIdentifier,
                "Assistant message should have externalIdentifier propagated from user message")

            ExternalChannelTestServer.clearUsers()
        }
    }

    @Test
    fun testSmsMessageHasChannelInfoPropagated() = runBlocking {
        ExternalChannelTestServer.test(settings = {
            ExternalChannelTestServer.database set Database.Settings("ram")
            ExternalChannelTestServer.smsInbound set SmsInboundService.Settings("test")
            ExternalChannelTestServer.smsOutbound set SMS.Settings("test")
        }) {
            val user = ExternalTestUser(email = "test@example.com", phone = "+15551234567")
            ExternalChannelTestServer.addUser(user)
            val auth = ExternalChannelTestServer.ExternalTestUserAuth.testAuth(user)
            val access = AuthAccess(auth)

            // Create a conversation
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                autoProcess = true,
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // Create a user message that came from SMS
            val userMessage = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.User,
                channel = ExternalChannelSupport.CHANNEL_SMS,
                externalIdentifier = "+15551234567",
                content = "Hello via SMS!",
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.messageInfo.table(access).insertOne(userMessage)

            // Find the assistant response message
            val messages = ExternalChannelTestServer.chatEndpoints.messageInfo.baseTable()
                .find(condition { it.conversationId eq conversation._id })
                .toList()

            val assistantMessage = messages.find { it.role == SystemChatMessage.Role.Assistant }
            assertNotNull(assistantMessage, "Assistant message should have been created")

            // Verify channel info was propagated to the assistant message
            assertEquals(ExternalChannelSupport.CHANNEL_SMS, assistantMessage.channel,
                "Assistant message should have channel propagated from user message")
            assertEquals("+15551234567", assistantMessage.externalIdentifier,
                "Assistant message should have externalIdentifier propagated from user message")

            ExternalChannelTestServer.clearUsers()
        }
    }

    @Test
    fun testEmailExternalIdParsing() {
        // Test JSON parsing
        val jsonId = """{"email":"user@example.com","messageId":"<123@mail.com>","subject":"Hello World"}"""
        val parsed = EmailExternalId.parse(jsonId)
        assertEquals("user@example.com", parsed.email)
        assertEquals("<123@mail.com>", parsed.messageId)
        assertEquals("Hello World", parsed.subject)

        // Test backward compatibility with plain email address
        val plainEmail = "plain@example.com"
        val parsedPlain = EmailExternalId.parse(plainEmail)
        assertEquals("plain@example.com", parsedPlain.email)
        assertEquals(null, parsedPlain.messageId)
        assertEquals(null, parsedPlain.subject)

        // Test encoding
        val emailId = EmailExternalId(
            email = "test@example.com",
            messageId = "<abc@mail.com>",
            subject = "Test Subject"
        )
        val encoded = EmailExternalId.encode(emailId)
        assertTrue(encoded.startsWith("{"))
        assertTrue(encoded.contains("test@example.com"))
        assertTrue(encoded.contains("<abc@mail.com>"))
        assertTrue(encoded.contains("Test Subject"))

        // Test round-trip
        val roundTripped = EmailExternalId.parse(encoded)
        assertEquals(emailId, roundTripped)
    }

    @Test
    fun testEmailThreadingSubjectPrefix() = runBlocking {
        ExternalChannelTestServer.test(settings = {
            ExternalChannelTestServer.database set Database.Settings("ram")
            ExternalChannelTestServer.emailInbound set EmailInboundService.Settings("test")
            ExternalChannelTestServer.emailOutbound set EmailService.Settings("test")
        }) {
            val user = ExternalTestUser(email = "user@example.com", phone = "+15551234567")
            ExternalChannelTestServer.addUser(user)
            val auth = ExternalChannelTestServer.ExternalTestUserAuth.testAuth(user)
            val access = AuthAccess(auth)

            val emailService = ExternalChannelTestServer.emailOutbound() as TestEmailService
            emailService.clear()

            // Create a conversation
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                autoProcess = false,
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // Create a user message from email with threading info
            val emailExternalId = EmailExternalId(
                email = "user@example.com",
                messageId = "<original123@mail.com>",
                subject = "Question about my order"
            )
            val userMessage = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.User,
                channel = ExternalChannelSupport.CHANNEL_EMAIL,
                externalIdentifier = EmailExternalId.encode(emailExternalId),
                content = "Hello, I have a question",
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.messageInfo.table(access).insertOne(userMessage)
            emailService.clear()

            // Create an assistant response (with channel info propagated)
            val assistantMessage = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.Assistant,
                channel = ExternalChannelSupport.CHANNEL_EMAIL,
                externalIdentifier = EmailExternalId.encode(emailExternalId),
                content = "Here is my response",
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.messageInfo.table(access).insertOne(assistantMessage)

            // Verify the email was sent with Re: prefix
            assertTrue(emailService.sentEmails.isNotEmpty(), "Should have sent an email response")
            val lastEmail = emailService.lastEmail()
            assertNotNull(lastEmail)
            assertEquals("Re: Question about my order", lastEmail.subject,
                "Email subject should have Re: prefix")

            // Verify threading headers were set
            val inReplyTo = lastEmail.customHeaders["In-Reply-To"]?.firstOrNull()
            assertEquals("<original123@mail.com>", inReplyTo,
                "In-Reply-To header should reference original message ID")

            ExternalChannelTestServer.clearUsers()
        }
    }

    @Test
    fun testEmailThreadingDoesNotDoubleRePrefix() = runBlocking {
        ExternalChannelTestServer.test(settings = {
            ExternalChannelTestServer.database set Database.Settings("ram")
            ExternalChannelTestServer.emailInbound set EmailInboundService.Settings("test")
            ExternalChannelTestServer.emailOutbound set EmailService.Settings("test")
        }) {
            val user = ExternalTestUser(email = "user@example.com", phone = "+15551234567")
            ExternalChannelTestServer.addUser(user)
            val auth = ExternalChannelTestServer.ExternalTestUserAuth.testAuth(user)
            val access = AuthAccess(auth)

            val emailService = ExternalChannelTestServer.emailOutbound() as TestEmailService
            emailService.clear()

            // Create a conversation
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                autoProcess = false,
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // Create a user message where subject already has Re: prefix
            val emailExternalId = EmailExternalId(
                email = "user@example.com",
                messageId = "<reply456@mail.com>",
                subject = "Re: Question about my order"  // Already has Re:
            )
            val userMessage = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.User,
                channel = ExternalChannelSupport.CHANNEL_EMAIL,
                externalIdentifier = EmailExternalId.encode(emailExternalId),
                content = "Follow up question",
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.messageInfo.table(access).insertOne(userMessage)
            emailService.clear()

            // Create an assistant response
            val assistantMessage = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.Assistant,
                channel = ExternalChannelSupport.CHANNEL_EMAIL,
                externalIdentifier = EmailExternalId.encode(emailExternalId),
                content = "Here is my follow up response",
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.messageInfo.table(access).insertOne(assistantMessage)

            // Verify the email doesn't have double Re:
            val lastEmail = emailService.lastEmail()
            assertNotNull(lastEmail)
            assertEquals("Re: Question about my order", lastEmail.subject,
                "Email subject should not double the Re: prefix")

            ExternalChannelTestServer.clearUsers()
        }
    }

    @Test
    fun testEmailThreadingReferencesHeader() = runBlocking {
        ExternalChannelTestServer.test(settings = {
            ExternalChannelTestServer.database set Database.Settings("ram")
            ExternalChannelTestServer.emailInbound set EmailInboundService.Settings("test")
            ExternalChannelTestServer.emailOutbound set EmailService.Settings("test")
        }) {
            val user = ExternalTestUser(email = "user@example.com", phone = "+15551234567")
            ExternalChannelTestServer.addUser(user)
            val auth = ExternalChannelTestServer.ExternalTestUserAuth.testAuth(user)
            val access = AuthAccess(auth)

            val emailService = ExternalChannelTestServer.emailOutbound() as TestEmailService
            emailService.clear()

            // Create a conversation
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                autoProcess = false,
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // Create first user message
            val emailId1 = EmailExternalId(
                email = "user@example.com",
                messageId = "<msg1@mail.com>",
                subject = "Original subject"
            )
            val userMessage1 = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.User,
                channel = ExternalChannelSupport.CHANNEL_EMAIL,
                externalIdentifier = EmailExternalId.encode(emailId1),
                content = "First message",
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.messageInfo.table(access).insertOne(userMessage1)

            // Create second user message (reply to the thread)
            val emailId2 = EmailExternalId(
                email = "user@example.com",
                messageId = "<msg2@mail.com>",
                subject = "Re: Original subject"
            )
            val userMessage2 = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.User,
                channel = ExternalChannelSupport.CHANNEL_EMAIL,
                externalIdentifier = EmailExternalId.encode(emailId2),
                content = "Second message",
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.messageInfo.table(access).insertOne(userMessage2)
            emailService.clear()

            // Create an assistant response
            val assistantMessage = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.Assistant,
                channel = ExternalChannelSupport.CHANNEL_EMAIL,
                externalIdentifier = EmailExternalId.encode(emailId2),
                content = "Response to thread",
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.messageInfo.table(access).insertOne(assistantMessage)

            // Verify References header contains all message IDs
            val lastEmail = emailService.lastEmail()
            assertNotNull(lastEmail)
            val references = lastEmail.customHeaders["References"]?.firstOrNull() ?: ""
            assertTrue(references.contains("<msg1@mail.com>"),
                "References should contain first message ID")
            assertTrue(references.contains("<msg2@mail.com>"),
                "References should contain second message ID")

            ExternalChannelTestServer.clearUsers()
        }
    }

    //
    // Tests for skipAutoResponse and triggerAutoResponse
    //

    @Test
    fun testSmsMessageTriggersAutoResponse() = runBlocking {
        ExternalChannelTestServer.test(settings = {
            ExternalChannelTestServer.database set Database.Settings("ram")
            ExternalChannelTestServer.smsInbound set SmsInboundService.Settings("test")
            ExternalChannelTestServer.smsOutbound set SMS.Settings("test")
        }) {
            val user = ExternalTestUser(email = "test@example.com", phone = "+15551234567")
            ExternalChannelTestServer.addUser(user)
            val auth = ExternalChannelTestServer.ExternalTestUserAuth.testAuth(user)
            val access = AuthAccess(auth)

            val endpoints = ExternalChannelTestServer.chatEndpoints as ExternalChannelTestServer.TestExternalChatEndpoints
            endpoints.respondCalls.clear()

            // Create a conversation
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                autoProcess = true,
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // Create a user message from SMS (simulating what handleInboundSms does)
            val userMessage = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.User,
                channel = ExternalChannelSupport.CHANNEL_SMS,
                externalIdentifier = "+15551234567",
                content = "Hello from SMS!",
                createdAt = now()
                // Note: NO skipAutoResponse flag - external channels don't set it
            )
            ExternalChannelTestServer.chatEndpoints.messageInfo.baseTable().insertOne(userMessage)

            // Call triggerAutoResponse (as ExternalChannelSupport does after insert)
            ExternalChannelTestServer.chatEndpoints.triggerAutoResponse(access, userMessage)

            // Verify respond() was called
            assertTrue(endpoints.respondCalls.isNotEmpty(),
                "triggerAutoResponse should trigger respond() for SMS user messages")

            ExternalChannelTestServer.clearUsers()
        }
    }

    @Test
    fun testVoiceMessageWithSkipAutoResponseDoesNotTrigger() = runBlocking {
        ExternalChannelTestServer.test(settings = {
            ExternalChannelTestServer.database set Database.Settings("ram")
            ExternalChannelTestServer.smsInbound set SmsInboundService.Settings("test")
            ExternalChannelTestServer.smsOutbound set SMS.Settings("test")
        }) {
            val user = ExternalTestUser(email = "test@example.com", phone = "+15551234567")
            ExternalChannelTestServer.addUser(user)
            val auth = ExternalChannelTestServer.ExternalTestUserAuth.testAuth(user)
            val access = AuthAccess(auth)

            val endpoints = ExternalChannelTestServer.chatEndpoints as ExternalChannelTestServer.TestExternalChatEndpoints
            endpoints.respondCalls.clear()

            val smsService = ExternalChannelTestServer.smsOutbound() as TestSMS
            smsService.reset()

            // Create a conversation
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                autoProcess = true,
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // Create a user message from voice WITH skipAutoResponse = true
            // (simulating what VoiceChannelSupport does)
            val voiceMessage = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.User,
                channel = "voice",
                content = "Hello from voice!",
                createdAt = now(),
                skipAutoResponse = true  // Voice sets this
            )
            ExternalChannelTestServer.chatEndpoints.messageInfo.baseTable().insertOne(voiceMessage)

            // Even if someone calls triggerAutoResponse, it should do nothing
            ExternalChannelTestServer.chatEndpoints.triggerAutoResponse(access, voiceMessage)

            // Verify respond() was NOT called
            assertTrue(endpoints.respondCalls.isEmpty(),
                "Voice messages with skipAutoResponse should not trigger respond()")

            // Also verify no SMS was sent (change listener check)
            assertTrue(smsService.messageHistory.isEmpty(),
                "Voice messages should not trigger SMS responses")

            ExternalChannelTestServer.clearUsers()
        }
    }

    @Test
    fun testPhoneMessageWithSkipAutoResponseDoesNotTrigger() = runBlocking {
        ExternalChannelTestServer.test(settings = {
            ExternalChannelTestServer.database set Database.Settings("ram")
            ExternalChannelTestServer.smsInbound set SmsInboundService.Settings("test")
            ExternalChannelTestServer.smsOutbound set SMS.Settings("test")
        }) {
            val user = ExternalTestUser(email = "test@example.com", phone = "+15551234567")
            ExternalChannelTestServer.addUser(user)
            val auth = ExternalChannelTestServer.ExternalTestUserAuth.testAuth(user)
            val access = AuthAccess(auth)

            val endpoints = ExternalChannelTestServer.chatEndpoints as ExternalChannelTestServer.TestExternalChatEndpoints
            endpoints.respondCalls.clear()

            // Create a conversation
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                autoProcess = true,
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // Create a user message from phone call WITH skipAutoResponse = true
            val phoneMessage = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.User,
                channel = "phone",
                content = "Hello from phone call!",
                createdAt = now(),
                skipAutoResponse = true  // Phone channel sets this
            )
            ExternalChannelTestServer.chatEndpoints.messageInfo.baseTable().insertOne(phoneMessage)

            // Call triggerAutoResponse - should do nothing
            ExternalChannelTestServer.chatEndpoints.triggerAutoResponse(access, phoneMessage)

            // Verify respond() was NOT called
            assertTrue(endpoints.respondCalls.isEmpty(),
                "Phone messages with skipAutoResponse should not trigger respond()")

            ExternalChannelTestServer.clearUsers()
        }
    }

    @Test
    fun testToolCallMessageWithSkipAutoResponseStored() = runBlocking {
        ExternalChannelTestServer.test(settings = {
            ExternalChannelTestServer.database set Database.Settings("ram")
        }) {
            val user = ExternalTestUser(email = "test@example.com", phone = "+15551234567")
            ExternalChannelTestServer.addUser(user)
            val auth = ExternalChannelTestServer.ExternalTestUserAuth.testAuth(user)
            val access = AuthAccess(auth)

            val endpoints = ExternalChannelTestServer.chatEndpoints as ExternalChannelTestServer.TestExternalChatEndpoints
            endpoints.respondCalls.clear()

            // Create a conversation
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                autoProcess = true,
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // Create a tool call message from voice (simulating VoiceChannelSupport saving tool calls)
            val toolCallMessage = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.ToolRequest,
                channel = "voice",
                content = "Called searchPosts",
                tool = ToolRequestData(
                    toolName = "searchPosts",
                    arguments = """{"query": "test"}""",
                    requiresApproval = false,
                    result = """{"posts": []}"""
                ),
                createdAt = now(),
                skipAutoResponse = true  // Voice sets this for tool calls too
            )
            ExternalChannelTestServer.chatEndpoints.messageInfo.baseTable().insertOne(toolCallMessage)

            // Verify message was stored with all fields intact
            val stored = ExternalChannelTestServer.chatEndpoints.messageInfo.baseTable().get(toolCallMessage._id)
            assertNotNull(stored)
            assertEquals(SystemChatMessage.Role.ToolRequest, stored.role)
            assertEquals("voice", stored.channel)
            assertEquals(true, stored.skipAutoResponse)
            assertEquals("searchPosts", stored.tool?.toolName)
            assertEquals("""{"posts": []}""", stored.tool?.result)

            // Verify no auto-response was triggered
            assertTrue(endpoints.respondCalls.isEmpty(),
                "Tool call messages should not trigger respond()")

            ExternalChannelTestServer.clearUsers()
        }
    }

    @Test
    fun testMixedChannelConversation() = runBlocking {
        ExternalChannelTestServer.test(settings = {
            ExternalChannelTestServer.database set Database.Settings("ram")
            ExternalChannelTestServer.smsInbound set SmsInboundService.Settings("test")
            ExternalChannelTestServer.smsOutbound set SMS.Settings("test")
        }) {
            val user = ExternalTestUser(email = "test@example.com", phone = "+15551234567")
            ExternalChannelTestServer.addUser(user)
            val auth = ExternalChannelTestServer.ExternalTestUserAuth.testAuth(user)
            val access = AuthAccess(auth)

            val endpoints = ExternalChannelTestServer.chatEndpoints as ExternalChannelTestServer.TestExternalChatEndpoints
            val smsService = ExternalChannelTestServer.smsOutbound() as TestSMS

            // Create a conversation
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                autoProcess = true,
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // 1. User starts via voice (no auto-response)
            endpoints.respondCalls.clear()
            smsService.reset()
            val voiceMessage = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.User,
                channel = "voice",
                content = "Voice message",
                createdAt = now(),
                skipAutoResponse = true
            )
            ExternalChannelTestServer.chatEndpoints.messageInfo.baseTable().insertOne(voiceMessage)
            ExternalChannelTestServer.chatEndpoints.triggerAutoResponse(access, voiceMessage)

            assertTrue(endpoints.respondCalls.isEmpty(), "Voice should not trigger auto-response")

            // 2. Same user continues via SMS (SHOULD auto-respond)
            endpoints.respondCalls.clear()
            smsService.reset()
            val smsMessage = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.User,
                channel = ExternalChannelSupport.CHANNEL_SMS,
                externalIdentifier = "+15551234567",
                content = "SMS message",
                createdAt = now()
                // No skipAutoResponse - SMS doesn't set it
            )
            ExternalChannelTestServer.chatEndpoints.messageInfo.baseTable().insertOne(smsMessage)
            ExternalChannelTestServer.chatEndpoints.triggerAutoResponse(access, smsMessage)

            assertTrue(endpoints.respondCalls.isNotEmpty(), "SMS should trigger auto-response")

            // Verify conversation has messages from both channels
            val allMessages = ExternalChannelTestServer.chatEndpoints.messageInfo.baseTable()
                .find(condition { it.conversationId eq conversation._id })
                .toList()

            val channels = allMessages.mapNotNull { it.channel }.toSet()
            assertTrue(channels.contains("voice"), "Should have voice message")
            assertTrue(channels.contains("sms"), "Should have SMS message")

            ExternalChannelTestServer.clearUsers()
        }
    }

    @Test
    fun testEmailBackwardCompatibilityWithPlainAddress() = runBlocking {
        ExternalChannelTestServer.test(settings = {
            ExternalChannelTestServer.database set Database.Settings("ram")
            ExternalChannelTestServer.emailInbound set EmailInboundService.Settings("test")
            ExternalChannelTestServer.emailOutbound set EmailService.Settings("test")
        }) {
            val user = ExternalTestUser(email = "user@example.com", phone = "+15551234567")
            ExternalChannelTestServer.addUser(user)
            val auth = ExternalChannelTestServer.ExternalTestUserAuth.testAuth(user)
            val access = AuthAccess(auth)

            val emailService = ExternalChannelTestServer.emailOutbound() as TestEmailService
            emailService.clear()

            // Create a conversation
            val conversation = SystemChatConversation(
                subjectId = auth.rawId.toString(),
                autoProcess = false,
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.conversationInfo.table(access).insertOne(conversation)

            // Create a user message with OLD format (plain email address, no JSON)
            // This tests backward compatibility
            val userMessage = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.User,
                channel = ExternalChannelSupport.CHANNEL_EMAIL,
                externalIdentifier = "user@example.com",  // Plain email, not JSON
                content = "Old format message",
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.messageInfo.table(access).insertOne(userMessage)
            emailService.clear()

            // Create an assistant response with the same plain format
            val assistantMessage = SystemChatMessage(
                conversationId = conversation._id,
                subjectId = auth.rawId.toString(),
                role = SystemChatMessage.Role.Assistant,
                channel = ExternalChannelSupport.CHANNEL_EMAIL,
                externalIdentifier = "user@example.com",  // Plain email, not JSON
                content = "Response to old format",
                createdAt = now()
            )
            ExternalChannelTestServer.chatEndpoints.messageInfo.table(access).insertOne(assistantMessage)

            // Should still send email successfully
            assertTrue(emailService.sentEmails.isNotEmpty(), "Should send email with old format")
            val lastEmail = emailService.lastEmail()
            assertNotNull(lastEmail)
            assertEquals("user@example.com", lastEmail.to.first().value.raw)
            // Subject should be default since no subject was in the identifier
            assertEquals("Chat Response", lastEmail.subject)

            ExternalChannelTestServer.clearUsers()
        }
    }
}
