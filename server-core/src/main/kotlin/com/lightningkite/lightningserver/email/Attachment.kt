package com.lightningkite.lightningserver.email

import com.lightningkite.lightningserver.client
import com.lightningkite.lightningserver.files.download
import com.lightningkite.lightningserver.http.HttpContent
import io.ktor.client.request.*
import java.io.File
import java.net.URL

/**
 * Attachment is used by EmailClients for attaching files to an email, whether they be
 * a local file for uploading, or a remote file for embedding.
 */
@Deprecated("Use Email.Attachment instead")
sealed interface Attachment {
    val description: String
    val name: String
    val inline: Boolean
    suspend fun convert(): Email.Attachment

    @Deprecated("Use Email.Attachment instead")
    data class Local(
        val file: File,
        override val description: String,
        override val name: String,
        override val inline: Boolean = false
    ) : Attachment {
        override suspend fun convert(): Email.Attachment = Email.Attachment(
            inline = inline,
            filename = name,
            content = HttpContent.file(file)
        )
    }

    @Deprecated("Use Email.Attachment instead")
    data class Remote(
        val url: URL,
        override val description: String,
        override val name: String,
        override val inline: Boolean = false
    ) : Attachment {
        override suspend fun convert(): Email.Attachment = Email.Attachment(
            inline = inline,
            filename = name,
            content = HttpContent.file(client.get(url).download())
        )
    }
}



