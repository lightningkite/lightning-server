package com.lightningkite.lightningserver.email

/**
 * A concrete implementation of EmailClient that will is similar to ConsoleEmailClient but with more options
 * You can turn off the console printing
 * It stores the last message sent
 * You can set a lambda for getting send events
 * This is useful for Unit Tests
 */

object TestEmailClient : EmailClient {
    var onEmailSent: ((Email)->Unit)? = null
    var lastEmailSent: Email? = null
        private set
    var printToConsole: Boolean = false

    override suspend fun send(email: Email) {
        lastEmailSent = email
        onEmailSent?.invoke(email)
        if (printToConsole) ConsoleEmailClient.send(email)
    }

}