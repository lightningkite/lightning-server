package com.lightningkite.lightningserver.email

import com.lightningkite.lightningserver.http.download
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import org.apache.commons.mail.EmailAttachment
import org.apache.commons.mail.HtmlEmail
import software.amazon.awssdk.auth.credentials.AwsCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ses.SesAsyncClient
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

    override suspend fun send(email: Email) {
        val subject = email.subject
        val to = email.to
        val html = email.html
        val plainText = email.plainText
        val attachments = email.attachments

        val out = HtmlEmail()
        out.setHtmlMsg(html)
        out.setTextMsg(plainText)
        attachments.forEach {
            val attachment = EmailAttachment()
            attachment.disposition = EmailAttachment.ATTACHMENT
            attachment.name = it.filename
            attachment.path = it.content.download().path
            out.attach(attachment)
        }
        out.subject = subject
        out.addTo(*to.map { it.toString() }.toTypedArray())
        out.addCc(*email.cc.map { it.toString() }.toTypedArray())
        out.addBcc(*email.bcc.map { it.toString() }.toTypedArray())
        email.customHeaders?.entries?.forEach {
            out.addHeader(it.first, it.second)
        }
        out.setFrom(fromEmail)
        out.mailSession = Session.getDefaultInstance(Properties())
        out.buildMimeMessage()
        val bytes = withContext( Dispatchers.IO) {
            ByteArrayOutputStream().use {
                out.mimeMessage.writeTo(it)
                it.toByteArray()
            }
        }
        client.sendRawEmail {
            it.rawMessage(RawMessage.builder().data(SdkBytes.fromByteArray(bytes)).build())
        }.await()
    }
}