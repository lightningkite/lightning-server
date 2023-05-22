package com.lightningkite.lightningserver.email

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import org.apache.commons.mail.EmailAttachment
import org.apache.commons.mail.HtmlEmail
import org.apache.commons.mail.MultiPartEmail
import org.apache.commons.mail.SimpleEmail
import software.amazon.awssdk.auth.credentials.AwsCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ses.SesAsyncClient
import software.amazon.awssdk.services.ses.SesClient
import software.amazon.awssdk.services.ses.model.RawMessage
import java.io.ByteArrayOutputStream
import java.lang.IllegalStateException
import java.util.*
import javax.mail.Session

class SesClient(
    val region: Region,
    val credentialProvider: AwsCredentialsProvider,
    val fromEmail: String
): EmailClient {
    companion object {
        init {
            EmailSettings.register("ses") {
                Regex("""ses://(?<accessKey>[^:]*):(?<secretAccessKey>[^@]*)@(?<region>.+)(?:\?(?<params>.*))?""").matchEntire(it.url)?.let { match ->
                    val accessKey = match.groups["accessKey"]!!.value
                    val secretAccessKey = match.groups["secretAccessKey"]!!.value
                    val params = EmailSettings.parseParameterString(match.groups["params"]?.value ?: "")
                    SesClient(
                        region = Region.of(match.groups["region"]!!.value),
                        credentialProvider = if (accessKey.isNotBlank() && secretAccessKey.isNotBlank()) {
                            StaticCredentialsProvider.create(object : AwsCredentials {
                                override fun accessKeyId(): String = accessKey
                                override fun secretAccessKey(): String = secretAccessKey
                            })
                        } else DefaultCredentialsProvider.create(),
                        fromEmail = params["fromEmail"]?.first() ?: it.fromEmail ?: throw IllegalStateException("SES Email requires a fromEmail to be set.")
                    )
                }
                    ?: throw IllegalStateException("Invalid SES URL. The URL should match the pattern: ses://[accessKey]:[secreteAccessKey]@[region]?[params]\nAvailable params are: fromEmail")
            }
        }
    }
    val client = SesAsyncClient.builder()
        .region(region)
        .credentialsProvider(credentialProvider)
        .build()

    override suspend fun sendHtml(
        subject: String,
        to: List<String>,
        html: String,
        plainText: String,
        attachments: List<Attachment>
    ) {
        val email = HtmlEmail()
        email.setHtmlMsg(html)
        email.setTextMsg(plainText)
        attachments.forEach {
            val attachment = EmailAttachment()
            attachment.disposition = EmailAttachment.ATTACHMENT
            attachment.description = it.description
            attachment.name = it.name
            when (it) {
                is Attachment.Remote -> {
                    attachment.url = it.url
                }
                is Attachment.Local -> {
                    attachment.path = it.file.absolutePath
                }
            }
            email.attach(attachment)
        }
        email.subject = subject
        email.addTo(*to.toTypedArray())
        email.setFrom(fromEmail)
        email.mailSession = Session.getDefaultInstance(Properties())
        email.buildMimeMessage()
        val bytes = withContext( Dispatchers.IO) {
            ByteArrayOutputStream().use {
                email.mimeMessage.writeTo(it)
                it.toByteArray()
            }
        }
        client.sendRawEmail {
            it.rawMessage(RawMessage.builder().data(SdkBytes.fromByteArray(bytes)).build())
        }.await()
    }
}