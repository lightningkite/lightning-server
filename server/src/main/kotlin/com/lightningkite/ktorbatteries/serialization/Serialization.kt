package com.lightningkite.ktorbatteries.serialization

import com.lightningkite.ktorbatteries.SetOnce
import com.lightningkite.ktorbatteries.files.ExternalServerFileSerializer
import com.lightningkite.ktordb.ClientModule
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.overwriteWith
import kotlinx.serialization.modules.serializersModuleOf
import kotlinx.serialization.properties.Properties

object Serialization {
    var module: SerializersModule by SetOnce {
        ClientModule.overwriteWith(serializersModuleOf(ExternalServerFileSerializer))
    }
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
