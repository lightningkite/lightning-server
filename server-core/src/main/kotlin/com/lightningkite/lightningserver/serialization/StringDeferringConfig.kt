package com.lightningkite.lightningserver.serialization

import kotlinx.serialization.StringFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule

class StringDeferringConfig(
    val serializersModule: SerializersModule,
    val ignoreUnknownKeys: Boolean = false,
    val nullMarker: String = "null",
    val deferMarker: String = "%",
    val deferredFormat: StringFormat = Json {
        this.serializersModule = serializersModule
        this.ignoreUnknownKeys = ignoreUnknownKeys
        this.explicitNulls = true
        this.encodeDefaults = true
        this.isLenient = true;
    }
)