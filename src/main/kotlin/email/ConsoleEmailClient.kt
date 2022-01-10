package com.lightningkite.ktorkmongo.email

object ConsoleEmailClient : EmailClient {
    override fun sendEmail(
        subject: String,
        to: List<String>,
        message: String,
        htmlMessage: String?,
        attachments: List<Attachment>
    ) {
        println(subject)
        println()
        println(to.joinToString())
        println()
        htmlMessage?.let{
            println(it)
            println()
        }
        println(message)
        println()
        attachments.forEach {
            println(it.name)
            when(it){
                is Attachment.Local -> println(it.file)
                is Attachment.Remote -> println(it.url)
            }
            println(it.description)
            println()
        }
    }
}