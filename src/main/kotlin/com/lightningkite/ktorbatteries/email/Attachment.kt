package com.lightningkite.ktorbatteries.email

import java.io.File
import java.net.URL

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



