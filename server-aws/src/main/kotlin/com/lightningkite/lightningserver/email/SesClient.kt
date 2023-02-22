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
                val withoutScheme = it.url.substringAfter("://")
                val credentials = withoutScheme.substringBefore('@', "").split(':').filter { it.isNotBlank() }
                val region = withoutScheme.substringAfter('@')
                SesClient(
                    region = Region.of(region),
                    credentialProvider = if (credentials.isNotEmpty()) {
                        StaticCredentialsProvider.create(object : AwsCredentials {
                            override fun accessKeyId(): String = credentials[0]
                            override fun secretAccessKey(): String = credentials[1]
                        })
                    } else DefaultCredentialsProvider.create(),
                    fromEmail = it.fromEmail ?: throw IllegalStateException("SES Email requires a fromEmail to be set.")
                )
            }
        }
    }
    val client = SesAsyncClient.builder()
        .region(region)
        .credentialsProvider(credentialProvider)
        .build()
    override suspend fun send(
        subject: String,
        to: List<String>,
        message: String,
        htmlMessage: String?,
        attachments: List<Attachment>
    ) {
        val email = if (htmlMessage == null) {
            if (attachments.isEmpty()) {
                SimpleEmail().setMsg(message)
            } else {
                val multiPart = MultiPartEmail()
                multiPart.setMsg(message)
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
                    multiPart.attach(attachment)
                }
                multiPart
            }
        } else{
            val email = HtmlEmail()
            email.setHtmlMsg(htmlMessage)
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
            email
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