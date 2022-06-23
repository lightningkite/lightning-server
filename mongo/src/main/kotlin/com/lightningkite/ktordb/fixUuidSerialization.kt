package com.lightningkite.ktordb

import org.litote.kmongo.serialization.registerSerializer
import com.github.jershell.kbson.UUIDSerializer

private var fixed = false
fun registerRequiredSerializers() {
    if (fixed) return
    registerSerializer(ServerFileSerialization)
    registerSerializer(UUIDSerializer)
    fixed = true
}
