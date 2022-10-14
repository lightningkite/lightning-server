package com.lightningkite.lightningdb

import org.litote.kmongo.serialization.registerSerializer
import com.github.jershell.kbson.UUIDSerializer

private var fixed = false
fun registerRequiredSerializers() {
    if (fixed) return
    registerSerializer(ServerFileSerialization)
    registerSerializer(DurationMsSerializer)
    registerSerializer(UUIDSerializer)
    fixed = true
}
