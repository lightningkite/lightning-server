package com.lightningkite.lightningserver.email

import com.lightningkite.lightningserver.http.HttpContent
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList

/**
 * A concrete implementation of EmailClient that will simply print out everything to the console
 * This is useful for local development
 */

object ConsoleEmailClient : EmailClient {

    override suspend fun send(email: Email) {
        println(buildString {
            appendLine("-----EMAIL-----")
            appendLine(email.subject)
            appendLine()
            appendLine(email.to.joinToString())
            appendLine()
            appendLine(email.plainText)

            if (email.attachments.isNotEmpty()) {
                appendLine("-----Attachments-----")
                email.attachments.forEach {
                    appendLine(it.filename)
                }
            }
        })
    }
}