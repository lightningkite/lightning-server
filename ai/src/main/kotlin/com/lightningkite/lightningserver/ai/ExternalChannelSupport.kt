package com.lightningkite.lightningserver.ai

import com.lightningkite.EmailAddress
import com.lightningkite.lightningserver.ai.models.*
import com.lightningkite.PhoneNumber
import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.ai.*
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.definition.Runtime
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
import com.lightningkite.lightningserver.typed.sdk.module
import com.lightningkite.services.data.TypedData
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
import kotlinx.html.*
import kotlinx.html.stream.createHTML
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Encoded email threading information stored in [SystemChatMessage.externalIdentifier].
 *
 * For email messages, the externalIdentifier contains a JSON-encoded [EmailExternalId]
 * that includes the email address plus threading metadata (Message-ID, subject).
 * This enables proper email threading when sending replies.
 *
 * @property email The email address
 * @property messageId The email's Message-ID header (for threading)
 * @property subject The original email subject (for Re: prefixing)
 */
@Serializable
public data class EmailExternalId(
    val email: String,
    val messageId: String? = null,
    val subject: String? = null,
) {
    public companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Parse an externalIdentifier string into an [EmailExternalId].
         * If the string is JSON (starts with '{'), parse it.
         * Otherwise, treat it as a plain email address for backward compatibility.
         */
        public fun parse(externalIdentifier: String): EmailExternalId {
            return if (externalIdentifier.startsWith("{")) {
                try {
                    json.decodeFromString<EmailExternalId>(externalIdentifier)
                } catch (e: Exception) {
                    // Fallback to plain email if JSON parsing fails
                    EmailExternalId(email = externalIdentifier)
                }
            } else {
                EmailExternalId(email = externalIdentifier)
            }
        }

        /**
         * Encode an [EmailExternalId] to a JSON string for storage in externalIdentifier.
         */
        public fun encode(emailExternalId: EmailExternalId): String {
            return json.encodeToString(emailExternalId)
        }
    }
}

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
 * ## Security Considerations
 *
 * **Authentication is based solely on phone number or email address possession.**
 *
 * This class creates synthetic [Authentication] instances based on the sender's phone number
 * or email address. This authentication model relies entirely on the security guarantees
 * provided by your inbound SMS/email service provider (e.g., Twilio, Amazon SES).
 *
 * **Important security implications:**
 * - SMS sender IDs can potentially be spoofed depending on carrier and region
 * - Email sender addresses can be spoofed if your email provider doesn't enforce SPF/DKIM/DMARC
 * - The synthetic authentication has no expiration
 *
 * **Recommendations:**
 * - Use a reputable inbound service provider with sender verification
 * - For SMS, prefer providers that validate sender identity (Twilio does this)
 * - For email, ensure your inbound service validates SPF/DKIM/DMARC
 * - Consider implementing additional verification for sensitive tool operations
 * - Do not expose highly destructive tools through external channels without additional safeguards
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

    public val sms: ServerBuilder? = smsInbound?.let { smsSettingRef ->
        path.path("sms") module Runtime { smsSettingRef().onReceived }.invoke(webhookScheduleFrequency) {
            handleInboundSms(it)
        }
    }

    public val email: ServerBuilder? = emailInbound?.let { emailInbound ->
        path.path("email") module Runtime { emailInbound().onReceived }.invoke(webhookScheduleFrequency) {
            handleInboundEmail(it)
        }
    }

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

        // Trigger LLM to respond to the user message
        chatEndpoints.triggerAutoResponse(access, message)
    }

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
        val rawBodyText = email.plainText
            ?: email.html?.emailApproximatePlainText()
            ?: ""

        // Strip quoted reply content to avoid context bloat
        val bodyText = stripQuotedReplies(rawBodyText)

        if (tryHandleToolApproval(access, conversation, bodyText.trim())) {
            return
        }

        // 5. Convert email attachments to ServerFiles
        val attachments = downloadEmailAttachments(email)

        // 6. Create external identifier with threading info
        val emailExternalId = EmailExternalId(
            email = email.from.value.raw,
            messageId = email.messageId,
            subject = email.subject,
        )

        // 7. Create and insert user message
        val message = SystemChatMessage(
            conversationId = conversation._id,
            subjectId = subject._id.toString(),
            role = SystemChatMessage.Role.User,
            channel = CHANNEL_EMAIL,
            externalIdentifier = EmailExternalId.encode(emailExternalId),
            content = bodyText,
            attachments = attachments,
            createdAt = now(),
        )

        chatEndpoints.messageInfo.table(access).insertOne(message)

        // Trigger LLM to respond to the user message
        chatEndpoints.triggerAutoResponse(access, message)
    }

    /**
     * Strip quoted reply content from email body to reduce context bloat.
     *
     * Detects and removes:
     * - Lines starting with ">" (traditional quote markers)
     * - "On [date], [person] wrote:" style headers and everything after
     * - Outlook-style "From: ... Sent: ... To: ... Subject: ..." blocks
     * - Gmail forwarded message markers
     * - Common separator lines (dashes, underscores)
     */
    private fun stripQuotedReplies(text: String): String {
        val lines = text.lines()
        val result = mutableListOf<String>()

        for (line in lines) {
            val trimmed = line.trim()

            // Stop at common reply headers
            if (trimmed.matches(Regex("^On .+ wrote:$", RegexOption.IGNORE_CASE))) break
            if (trimmed.matches(Regex("^On .+, .+ wrote:$", RegexOption.IGNORE_CASE))) break
            if (trimmed.startsWith("---------- Forwarded message")) break
            if (trimmed.startsWith("-----Original Message-----")) break
            if (trimmed.matches(Regex("^-{5,}$"))) break  // Line of dashes
            if (trimmed.matches(Regex("^_{5,}$"))) break  // Line of underscores

            // Outlook-style header block detection
            if (trimmed.startsWith("From:") && lines.indexOf(line).let { idx ->
                    idx + 3 < lines.size &&
                    lines.getOrNull(idx + 1)?.trim()?.startsWith("Sent:") == true &&
                    lines.getOrNull(idx + 2)?.trim()?.startsWith("To:") == true
                }) break

            // Skip lines that start with quote markers (but don't stop entirely)
            if (trimmed.startsWith(">")) continue

            result.add(line)
        }

        return result.joinToString("\n").trim()
    }

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
        // Use the message's own channel and externalIdentifier (propagated from the original user message)
        val channel = message.channel ?: return
        val identifier = message.externalIdentifier ?: return

        when (channel) {
            CHANNEL_SMS -> trySendSms(identifier, message)
            CHANNEL_EMAIL -> trySendEmail(identifier, message)
        }
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
    private suspend fun trySendEmail(externalIdentifier: String, message: SystemChatMessage) {
        val emailService = emailOutbound?.invoke() ?: return
        val fromAddr = emailFromAddress

        // Parse threading info from externalIdentifier
        val emailId = EmailExternalId.parse(externalIdentifier)

        // Build threading headers
        val threadingHeaders = buildEmailThreadingHeaders(message.conversationId, emailId)

        // Determine subject with Re: prefix if replying
        val baseSubject = emailId.subject
        val subject = if (baseSubject != null && !baseSubject.startsWith("Re:", ignoreCase = true)) {
            "Re: $baseSubject"
        } else {
            baseSubject ?: "Chat Response"
        }

        val htmlBody = formatMessageForEmail(message) ?: return

        try {
            emailService.send(
                Email(
                    subject = subject,
                    from = fromAddr,
                    to = listOf(EmailAddressWithName(emailId.email)),
                    html = htmlBody,
                    customHeaders = threadingHeaders,
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Build email threading headers (In-Reply-To, References) from conversation history.
     */
    context(runtime: ServerRuntime)
    private suspend fun buildEmailThreadingHeaders(
        conversationId: Uuid,
        replyingTo: EmailExternalId
    ): Map<String, List<String>> {
        val headers = mutableMapOf<String, List<String>>()

        // Set In-Reply-To if we have a messageId to reply to
        val inReplyTo = replyingTo.messageId
        if (inReplyTo != null) {
            headers["In-Reply-To"] = listOf(inReplyTo)
        }

        // Build References header from conversation history
        // Collect all Message-IDs from email messages in this conversation
        val messageIds = mutableListOf<String>()

        chatEndpoints.messageInfo.table()
            .find(
                condition {
                    (it.conversationId eq conversationId) and
                    (it.channel eq CHANNEL_EMAIL) and
                    (it.externalIdentifier neq null)
                },
                orderBy = sort { it.createdAt.ascending() }
            )
            .toList()
            .forEach { msg ->
                val extId = msg.externalIdentifier ?: return@forEach
                val parsed = EmailExternalId.parse(extId)
                parsed.messageId?.let { messageIds.add(it) }
            }

        if (messageIds.isNotEmpty()) {
            headers["References"] = listOf(messageIds.joinToString(" "))
        }

        return headers
    }

    private fun formatMessageForEmail(message: SystemChatMessage): String? {
        return when (message.role) {
            SystemChatMessage.Role.Assistant -> createHTML().div {
                // Split content by newlines and render each line, properly escaped
                message.content.lines().forEachIndexed { index, line ->
                    if (index > 0) br()
                    +line  // kotlinx.html automatically escapes this
                }
            }

            SystemChatMessage.Role.Error -> createHTML().p {
                style = "color:red"
                strong { +"Error: " }
                +message.content
            }

            SystemChatMessage.Role.ToolRequest -> {
                val tool = message.tool ?: return null
                if (tool.requiresApproval && tool.approval == null) {
                    val desc = message.content.ifBlank {
                        tool.approvalReason ?: "Execute ${tool.toolName}"
                    }
                    createHTML().div {
                        p {
                            strong { +"Tool Request: " }
                            +desc
                        }
                        p {
                            +"Reply with "
                            strong { +"YES" }
                            +" to approve or "
                            strong { +"NO" }
                            +" to reject."
                        }
                    }
                } else null
            }

            else -> null
        }
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

        val updated = chatEndpoints.messageInfo.table().updateOneById(
            pendingRequest._id,
            modification { it.tool.notNull.approval assign approval }
        ).new ?: return false

        // Trigger tool execution or response continuation
        if (approved) {
            chatEndpoints.triggerToolExecution(access, updated)
        } else {
            chatEndpoints.triggerContinueResponse(access, updated)
        }

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
                destination.put(TypedData(data, attachment.contentType))

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
