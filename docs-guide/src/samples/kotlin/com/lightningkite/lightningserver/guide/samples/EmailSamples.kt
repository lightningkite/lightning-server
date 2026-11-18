package com.lightningkite.lightningserver.guide.samples

// region email-imports
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.data.*
import com.lightningkite.services.email.*
import com.lightningkite.services.sms.*
import kotlinx.html.*
import kotlin.test.*
// endregion email-imports

// region email-server
object MailServer : ServerBuilder() {
    init { registerBasicMediaTypeCoders() }

    val email = setting("email", EmailService.Settings())
    val sms = setting("sms", SMS.Settings())

    val sendWelcome = path.path("welcome").post bind ApiHttpHandler(
        summary = "Send welcome email",
        auth = noAuth,
        implementation = { to: EmailAddress ->
            email().send(
                Email(
                    subject = "Welcome!",
                    to = listOf(EmailAddressWithName(to)),
                    plainText = "Thanks for signing up.",
                )
            )
        }
    )

    val sendCode = path.path("code").post bind ApiHttpHandler(
        summary = "Text a code",
        auth = noAuth,
        implementation = { to: PhoneNumber ->
            sms().send(to, "Your code is 123456")
        }
    )
}
// endregion email-server

// region email-test
fun mailTest() = MailServer.testBlocking(settings = {
    email set EmailService.Settings("test")
    sms set SMS.Settings("test")
}) {
    MailServer.sendWelcome.test(null, "jane@example.com".toEmailAddress())
    val sent = (MailServer.email() as TestEmailService).lastEmailTo("jane@example.com")
    assertEquals("Welcome!", sent?.subject)

    MailServer.sendCode.test(null, "+15551234567".toPhoneNumber())
    val text = (MailServer.sms() as TestSMS).lastMessageSent
    assertEquals("Your code is 123456", text?.message)
}
// endregion email-test

// region email-html
fun orderConfirmation(to: EmailAddress): Email = Email(
    subject = "Order confirmed",
    from = EmailAddressWithName("orders@example.com", "Example Store"),
    to = listOf(EmailAddressWithName(to)),
    bcc = listOf(EmailAddressWithName("audit@example.com")),
    html = {
        body {
            h1 { +"Thanks for your order" }
            p { +"Your total is "; b { +"$42.00" }; +"." }
        }
    },
)
// endregion email-html

// region email-attachment
fun invoiceEmail(to: EmailAddress, pdf: ByteArray): Email = Email(
    subject = "Your invoice",
    to = listOf(EmailAddressWithName(to)),
    plainText = "Your invoice is attached.",
    attachments = listOf(
        Email.Attachment(
            inline = false,
            filename = "invoice.pdf",
            typedData = TypedData.bytes(pdf, MediaType.Application.Pdf),
        )
    ),
)
// endregion email-attachment

// region email-bulk
fun bulkMailTest() = MailServer.testBlocking(settings = { email set EmailService.Settings("test") }) {
    val template = Email(
        subject = "Hello {{name}}",
        to = listOf(),
        plainText = "Hi {{name}}, your code is {{code}}.",
    )
    MailServer.email().sendBulk(
        template,
        listOf(
            EmailPersonalization(
                to = listOf(EmailAddressWithName("alice@example.com")),
                substitutions = mapOf("name" to "Alice", "code" to "111222"),
            ),
            EmailPersonalization(
                to = listOf(EmailAddressWithName("bob@example.com")),
                substitutions = mapOf("name" to "Bob", "code" to "333444"),
            ),
        )
    )

    val bob = (MailServer.email() as TestEmailService).lastEmailTo("bob@example.com")
    assertEquals("Hello Bob", bob?.subject)
    assertEquals("Hi Bob, your code is 333444.", bob?.plainText)
}
// endregion email-bulk
