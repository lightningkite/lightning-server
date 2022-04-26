package com.lightningkite.ktorbatteries.serialization

import com.lightningkite.ktorbatteries.SetOnce
import com.lightningkite.ktorkmongo.ClientModule
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.properties.Properties

object Serialization {
    var module: SerializersModule by SetOnce { ClientModule }
    var json: Json by SetOnce {
        Json {
            ignoreUnknownKeys = true
            serializersModule = module
        }
    }
    var properties: Properties by SetOnce {
        Properties(module)
    }
}
