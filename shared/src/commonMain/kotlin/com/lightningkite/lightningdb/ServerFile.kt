package com.lightningkite.lightningdb

import kotlin.jvm.JvmInline

@JvmInline
@Description("A URL referencing a file that the server owns.")
value class ServerFile(val location: String)
