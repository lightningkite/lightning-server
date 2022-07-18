package com.lightningkite.lightningserver.email

import java.io.File
import java.net.URL

/**
 * Attachment is used by EmailClients for attaching files to an email, whether they be
 * a local file for uploading, or a remote file for embedding.
 */
sealed interface Attachment {
    val description: String
    val name: String

    data class Local(
        val file: File,
        override val description: String,
        override val name: String,
    ) : Attachment

    data class Remote(
        val url: URL,
        override val description: String,
        override val name: String,
    ) : Attachment
}



