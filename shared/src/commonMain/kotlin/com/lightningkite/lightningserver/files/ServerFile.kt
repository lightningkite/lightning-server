package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.db.Description
import kotlin.jvm.JvmInline

@JvmInline
@Description("A URL referencing a file that the server owns.")
value class ServerFile(val location: String)
