package com.lightningkite.lightningserver.ai

import com.lightningkite.EmailAddress
import com.lightningkite.PhoneNumber
import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.definition.ScheduledTask
import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.lightningserver.definition.StartupTask
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.fullUrl
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.location
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.settings.invoke
import com.lightningkite.lightningserver.typed.AuthAccess
import com.lightningkite.lightningserver.typed.auth
import com.lightningkite.services.database.*
import com.lightningkite.services.email.*
import com.lightningkite.services.files.PublicFileSystem
import com.lightningkite.services.files.ServerFile
import com.lightningkite.services.sms.InboundSms
import com.lightningkite.services.sms.SMS
import com.lightningkite.services.sms.SmsInboundService
import com.lightningkite.toPhoneNumber
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Adds SMS and Email channel support to an existing [SystemChatEndpoints] instance.
 *
 * This class uses composition - it references the chat endpoints and registers
 * webhooks + message listeners to handle external channels.
 *
 * When users send SMS or email to your configured numbers/addresses, this class:
 * 1. Resolves the sender to a subject using the provided resolution functions
 * 2. Finds or creates a conversation for that subject
 * 3. Creates a user message in the chat
 * 4. Handles tool approval responses ("YES"/"NO")
 *
 * When the chat system sends responses, this class:
 * 1. Detects messages that should be sent externally
 * 2. Finds the reply destination from the conversation history
 * 3. Sends via SMS or Email as appropriate
 *
 * ## Usage
 *
 * ```kotlin
 * object MyChatBot : LLMChatEndpoints<User>(...) { ... }
 *
 * object MyChatBotChannels : ExternalChannelSupport<User, Uuid>(
 *     chatEndpoints = MyChatBot,
 *     principalType = User,
 *     smsInbound = smsInboundSetting,
 *     smsOutbound = smsSetting,
 *     emailInbound = emailInboundSetting,
 *     emailOutbound = emailSetting,
 *     emailFromAddress = EmailAddressWithName("bot@example.com", "My Bot"),
 *     resolveSubjectByPhone = { phone ->
 *         userTable().findOne(condition { it.phone eq phone.raw })
 *     },
 *     resolveSubjectByEmail = { email ->
 *         userTable().findOne(condition { it.email eq email.raw })
 *     },
 * )
 * ```
 *
 * @param Subject The authenticated user type
 * @param ID The ID type for the subject
 * @param chatEndpoints The SystemChatEndpoints instance to add external channel support to
 * @param principalType The PrincipalType for creating Authentication instances
 * @param smsInbound Optional SMS inbound service settings
 * @param smsOutbound Optional SMS outbound service settings
 * @param emailInbound Optional Email inbound service settings
 * @param emailOutbound Optional Email outbound service settings
 * @param emailFromAddress The "from" address for outbound emails
 * @param files Optional file storage for handling attachments
 * @param resolveSubjectByPhone Function to resolve a subject from a phone number (required if using SMS)
 * @param resolveSubjectByEmail Function to resolve a subject from an email address (required if using Email)
 */
public class ExternalChannelSupport<Subject : HasId<ID>, ID : Comparable<ID>>(
    private val chatEndpoints: SystemChatEndpoints<Subject>,
    private val principalType: PrincipalType<Subject, ID>,

    // SMS settings (both optional)
    private val smsInbound: ServerSetting<SmsInboundService.Settings, SmsInboundService>? = null,
    private val smsOutbound: ServerSetting<SMS.Settings, SMS>? = null,

    // Email settings (both optional)
    private val emailInbound: ServerSetting<EmailInboundService.Settings, EmailInboundService>? = null,
    private val emailOutbound: ServerSetting<EmailService.Settings, EmailService>? = null,
    private val emailFromAddress: EmailAddressWithName? = null,

    // File storage for attachments
    private val files: ServerSetting<PublicFileSystem.Settings, PublicFileSystem>? = null,

    // Subject resolution (required if using that channel)
    private val resolveSubjectByPhone: (suspend context(ServerRuntime) (PhoneNumber) -> Subject?)? = null,
    private val resolveSubjectByEmail: (suspend context(ServerRuntime) (EmailAddress) -> Subject?)? = null,

    // Webhook scheduling frequency
    private val webhookScheduleFrequency: Duration = 1.minutes,
) : ServerBuilder() {

    //
    // SMS Webhook Endpoints
    //

    /**
     * SMS webhook endpoint that receives incoming SMS/MMS messages.
     * Configure your SMS provider to POST to this URL.
     */
    public val smsWebhook: HttpHandler<PathSpec0>? = smsInbound?.let { smsSettingRef ->
        path.path("sms").path("webhook").post bind HttpHandler { request ->
            val inboundSms = smsSettingRef().onReceived.parse(
                queryParameters = request.queryParameters.entries,
                headers = request.headers.normalizedEntries.mapValues { it.value.map { v -> v.toHttpString() } },
                body = request.body ?: throw BadRequestException("Missing request body")
            )

            handleInboundSms(inboundSms)

            // Return success response
            HttpResponse(null, HttpStatus.NoContent)
        }
    }

    /**
     * Startup task to configure the SMS webhook URL with the provider.
     */
    public val smsWebhookSetup: StartupTask? = smsInbound?.let { smsSettingRef ->
        smsWebhook?.let { webhook ->
            path.path("sms").path("webhook-setup") bind StartupTask {
                smsSettingRef().onReceived.configureWebhook(webhook.location.path.resolved().fullUrl())
            }
        }
    }

    /**
     * Scheduled task for SMS provider maintenance (polling, etc.).
     */
    public val smsSchedule: ScheduledTask? = smsInbound?.let { smsSettingRef ->
        path.path("sms").path("schedule") bind ScheduledTask(webhookScheduleFrequency) {
            smsSettingRef().onReceived.onSchedule()
        }
    }

    //
    // Email Webhook Endpoints
    //

    /**
     * Email webhook endpoint that receives incoming emails.
     * Configure your email provider to POST to this URL.
     */
    public val emailWebhook: HttpHandler<PathSpec0>? = emailInbound?.let { emailSettingRef ->
        path.path("email").path("webhook").post bind HttpHandler { request ->
            val receivedEmail = emailSettingRef().onReceived.parse(
                queryParameters = request.queryParameters.entries,
                headers = request.headers.normalizedEntries.mapValues { it.value.map { v -> v.toHttpString() } },
                body = request.body ?: throw BadRequestException("Missing request body")
            )

            handleInboundEmail(receivedEmail)

            // Return success response
            HttpResponse(null, HttpStatus.NoContent)
        }
    }

    /**
     * Startup task to configure the email webhook URL with the provider.
     */
    public val emailWebhookSetup: StartupTask? = emailInbound?.let { emailSettingRef ->
        emailWebhook?.let { webhook ->
            path.path("email").path("webhook-setup") bind StartupTask {
                emailSettingRef().onReceived.configureWebhook(webhook.location.path.resolved().fullUrl())
            }
        }
    }

    /**
     * Scheduled task for email provider maintenance (polling IMAP, etc.).
     */
    public val emailSchedule: ScheduledTask? = emailInbound?.let { emailSettingRef ->
        path.path("email").path("schedule") bind ScheduledTask(webhookScheduleFrequency) {
            emailSettingRef().onReceived.onSchedule()
        }
    }

    //
    // Inbound SMS Handler
    //

    context(runtime: ServerRuntime)
    private suspend fun handleInboundSms(sms: InboundSms) {
        val resolver = resolveSubjectByPhone
            ?: throw IllegalStateException("resolveSubjectByPhone required for SMS support")

        // 1. Resolve subject from phone number
        val subject = resolver(sms.from)
        if (subject == null) {
            // Unknown sender - could log, but we can't process without a subject
            return
        }

        // 2. Create auth access for this subject
        val auth = createAuthForSubject(subject)
        val access = AuthAccess(auth)

        // 3. Find most recent conversation for this subject
        val conversation = findOrCreateConversation(access, subject)

        // 4. Check for tool approval response
        val trimmedBody = sms.body.trim()
        if (tryHandleToolApproval(access, conversation, trimmedBody)) {
            return
        }

        // 5. Download MMS attachments as ServerFiles
        val attachments = downloadMmsAttachments(sms)

        // 6. Create and insert user message
        val message = SystemChatMessage(
            conversationId = conversation._id,
            subjectId = subject._id.toString(),
            role = SystemChatMessage.Role.User,
            channel = CHANNEL_SMS,
            externalIdentifier = sms.from.raw,
            content = sms.body,
            attachments = attachments,
            createdAt = now(),
        )

        chatEndpoints.messageInfo.table(access).insertOne(message)
        // postCreate hook in messageInfo triggers response generation
    }

    //
    // Inbound Email Handler
    //

    context(runtime: ServerRuntime)
    private suspend fun handleInboundEmail(email: ReceivedEmail) {
        val resolver = resolveSubjectByEmail
            ?: throw IllegalStateException("resolveSubjectByEmail required for Email support")

        // 1. Resolve subject from email address
        val subject = resolver(email.from.value)
        if (subject == null) {
            return
        }

        // 2. Create auth access
        val auth = createAuthForSubject(subject)
        val access = AuthAccess(auth)

        // 3. Find most recent conversation
        val conversation = findOrCreateConversation(access, subject)

        // 4. Check for tool approval
        val bodyText = email.plainText
            ?: email.html?.emailApproximatePlainText()
            ?: ""
        if (tryHandleToolApproval(access, conversation, bodyText.trim())) {
            return
        }

        // 5. Convert email attachments to ServerFiles
        val attachments = downloadEmailAttachments(email)

        // 6. Create and insert user message
        val message = SystemChatMessage(
            conversationId = conversation._id,
            subjectId = subject._id.toString(),
            role = SystemChatMessage.Role.User,
            channel = CHANNEL_EMAIL,
            externalIdentifier = email.from.value.raw,
            content = bodyText,
            attachments = attachments,
            createdAt = now(),
        )

        chatEndpoints.messageInfo.table(access).insertOne(message)
    }

    //
    // Outbound Message Handling
    //

    init {
        chatEndpoints.messageInfo.registerChangeListener { changes ->
            changes.changes.forEach { change ->
                change.new?.let { message ->
                    if (shouldSendExternal(message)) {
                        try {
                            trySendExternalMessage(message)
                        } catch (e: Exception) {
                            // Log but don't fail - external send is best-effort
                            e.printStackTrace()
                        }
                    }
                }
            }
        }
    }

    private fun shouldSendExternal(message: SystemChatMessage): Boolean {
        return when (message.role) {
            SystemChatMessage.Role.Assistant -> true
            SystemChatMessage.Role.Error -> true
            SystemChatMessage.Role.ToolRequest -> {
                message.tool?.requiresApproval == true && message.tool?.approval == null
            }
            else -> false
        }
    }

    context(runtime: ServerRuntime)
    private suspend fun trySendExternalMessage(message: SystemChatMessage) {
        // Find the reply destination from most recent inbound external message
        val destination = findReplyDestination(message.conversationId)
            ?: return  // No external channel for this conversation

        val (channel, identifier) = destination

        when (channel) {
            CHANNEL_SMS -> trySendSms(identifier, message)
            CHANNEL_EMAIL -> trySendEmail(identifier, message)
        }
    }

    context(runtime: ServerRuntime)
    private suspend fun findReplyDestination(conversationId: Uuid): Pair<String, String>? {
        // Find most recent message with an external identifier
        val recentExternal = chatEndpoints.messageInfo.table()
            .find(
                condition {
                    (it.conversationId eq conversationId) and
                            (it.externalIdentifier neq null)
                },
                orderBy = sort { it.createdAt.descending() }
            )
            .firstOrNull() ?: return null

        val channel = recentExternal.channel ?: return null
        val identifier = recentExternal.externalIdentifier ?: return null

        return channel to identifier
    }

    //
    // SMS Sending
    //

    context(runtime: ServerRuntime)
    private suspend fun trySendSms(phoneNumber: String, message: SystemChatMessage) {
        val sms = smsOutbound?.invoke() ?: return

        val text = formatMessageForSms(message)
        if (text.isNullOrBlank()) return

        try {
            sms.send(phoneNumber.toPhoneNumber(), text)
        } catch (e: Exception) {
            // Log error - could potentially try fallback channel
            e.printStackTrace()
        }
    }

    private fun formatMessageForSms(message: SystemChatMessage): String? {
        return when (message.role) {
            SystemChatMessage.Role.Assistant ->
                message.content.take(1600)  // Allow multi-part SMS

            SystemChatMessage.Role.Error ->
                "[Error] ${message.content}".take(1600)

            SystemChatMessage.Role.ToolRequest -> {
                val tool = message.tool ?: return null
                if (tool.requiresApproval && tool.approval == null) {
                    val desc = message.content.ifBlank {
                        tool.approvalReason ?: "Execute ${tool.toolName}"
                    }
                    "[Tool Request] $desc\nReply YES to approve or NO to reject.".take(1600)
                } else null
            }

            else -> null
        }
    }

    //
    // Email Sending
    //

    context(runtime: ServerRuntime)
    private suspend fun trySendEmail(emailAddress: String, message: SystemChatMessage) {
        val emailService = emailOutbound?.invoke() ?: return
        val fromAddr = emailFromAddress ?: return

        val (subject, htmlBody) = formatMessageForEmail(message) ?: return

        try {
            emailService.send(
                Email(
                    subject = subject,
                    from = fromAddr,
                    to = listOf(EmailAddressWithName(emailAddress)),
                    html = htmlBody,
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun formatMessageForEmail(message: SystemChatMessage): Pair<String, String>? {
        val subject = "Chat Response"  // Could be smarter about threading

        val html = when (message.role) {
            SystemChatMessage.Role.Assistant ->
                "<p>${message.content.replace("\n", "<br>")}</p>"

            SystemChatMessage.Role.Error ->
                "<p style='color:red'><strong>Error:</strong> ${message.content}</p>"

            SystemChatMessage.Role.ToolRequest -> {
                val tool = message.tool ?: return null
                if (tool.requiresApproval && tool.approval == null) {
                    val desc = message.content.ifBlank {
                        tool.approvalReason ?: "Execute ${tool.toolName}"
                    }
                    """
                    <p><strong>Tool Request:</strong> $desc</p>
                    <p>Reply with <strong>YES</strong> to approve or <strong>NO</strong> to reject.</p>
                    """.trimIndent()
                } else return null
            }

            else -> return null
        }

        return subject to html
    }

    //
    // Helper Methods
    //

    context(runtime: ServerRuntime)
    private suspend fun findOrCreateConversation(
        access: AuthAccess<Subject>,
        subject: Subject
    ): SystemChatConversation {
        // Find most recent conversation
        val existing = chatEndpoints.conversationInfo.table(access)
            .find(
                condition { it.subjectId eq subject._id.toString() },
                orderBy = sort { it.updatedAt.descending() }
            )
            .firstOrNull()

        if (existing != null) return existing

        // Create new conversation
        val newConversation = SystemChatConversation(
            subjectId = subject._id.toString(),
            createdAt = now()
        )
        return chatEndpoints.conversationInfo.table(access).insertOne(newConversation)!!
    }

    context(runtime: ServerRuntime)
    private suspend fun tryHandleToolApproval(
        access: AuthAccess<Subject>,
        conversation: SystemChatConversation,
        text: String
    ): Boolean {
        val isApproval = text.equals("YES", ignoreCase = true)
        val isRejection = text.equals("NO", ignoreCase = true)
                || text.lowercase().startsWith("no:")

        if (!isApproval && !isRejection) return false

        // Find pending tool request
        val pendingRequest = chatEndpoints.messageInfo.table(access)
            .find(
                condition {
                    (it.conversationId eq conversation._id) and
                            (it.role eq SystemChatMessage.Role.ToolRequest)
                },
                orderBy = sort { it.createdAt.descending() }
            )
            .firstOrNull { msg ->
                msg.tool?.requiresApproval == true && msg.tool?.approval == null
            }

        if (pendingRequest == null) return false

        val (approved, reason) = if (isApproval) {
            true to null
        } else {
            val rejectionReason = if (text.lowercase().startsWith("no:")) {
                text.substringAfter(":").trim()
            } else null
            false to rejectionReason
        }

        // Record approval
        val approval = ToolApproval(
            approved = approved,
            approvedBy = access.auth.rawId,
            approvedAt = now(),
            reason = reason
        )

        chatEndpoints.messageInfo.table().updateOneById(
            pendingRequest._id,
            modification { it.tool.notNull.approval assign approval }
        )

        // The message change listener will trigger tool execution or response continuation
        // via the chatEndpoints' internal task system

        return true
    }

    context(runtime: ServerRuntime)
    private suspend fun downloadMmsAttachments(sms: InboundSms): List<ServerFile> {
        val fileService = files?.invoke() ?: return emptyList()

        return sms.mediaUrls.mapIndexedNotNull { index, url ->
            try {
                val contentType = sms.mediaContentTypes.getOrNull(index)
                    ?: "application/octet-stream"

                // Download from MMS URL and store
                // Note: This is a simplified implementation - in practice you may need
                // to handle authentication for MMS URLs (e.g., Twilio requires auth)
                val filename = "mms_${Uuid.random()}_$index"
                val destination = fileService.root.then("chat-attachments/$filename")

                // For now, return empty list - full implementation would download and store
                // TODO: Implement MMS download using HTTP client
                null
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    context(runtime: ServerRuntime)
    private suspend fun downloadEmailAttachments(email: ReceivedEmail): List<ServerFile> {
        val fileService = files?.invoke() ?: return emptyList()

        return email.attachments.mapNotNull { attachment ->
            try {
                val data = attachment.content
                    ?: return@mapNotNull null  // URL-based attachments need HTTP download

                val filename = "email_${Uuid.random()}_${attachment.filename}"
                val destination = fileService.root.then("chat-attachments/$filename")

                // Store the attachment
                destination.put(com.lightningkite.services.data.TypedData(data, attachment.contentType))

                // Return as ServerFile
                ServerFile(destination.signedUrl)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    context(runtime: ServerRuntime)
    private fun createAuthForSubject(subject: Subject): Authentication<Subject> {
        // Create a synthetic authentication for the subject
        // This represents "authenticated via phone/email possession"
        return Authentication(
            principalType = principalType,
            id = subject._id,
            sessionId = null,  // No session for external channel auth
            issuedAt = now(),
            expiration = null,  // No expiration for this synthetic auth
        )
    }

    public companion object {
        public const val CHANNEL_SMS: String = "sms"
        public const val CHANNEL_EMAIL: String = "email"
    }
}
