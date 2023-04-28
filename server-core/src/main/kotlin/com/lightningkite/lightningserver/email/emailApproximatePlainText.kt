package com.lightningkite.lightningserver.email

fun String.emailApproximatePlainText(): String = this
    .substringAfter("<body>")
    .replace(Regex("< *\\/? *span[^>]*>"), "")
    .replace(Regex("\\s+"), " ")
    .replace(Regex("<[^<]+>"), "\n")
    .replace(Regex("\\s*\\n\\s*"), "\n")


fun String.emailPlainTextToHtml(): String = this
    .replace("\n", "<br>")
