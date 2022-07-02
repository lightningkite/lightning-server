package com.lightningkite.lightningserver.serialization

import com.github.jershell.kbson.Configuration
import com.github.jershell.kbson.KBson
import com.lightningkite.lightningserver.SetOnce
import com.lightningkite.lightningdb.ClientModule
import com.lightningkite.lightningdb.ServerFile
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.files.*
import com.lightningkite.lightningserver.http.HttpContent
import com.lightningkite.lightningserver.http.HttpRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.*
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.csv.Csv
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.getContextualDescriptor
import kotlinx.serialization.encoding.*
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.overwriteWith
import kotlinx.serialization.modules.serializersModuleOf
import kotlinx.serialization.properties.Properties
import nl.adaptivity.xmlutil.serialization.XML
import kotlin.reflect.*

/**
 * A place to hold all the support Serialization types.
 */
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
    var csv: Csv by SetOnce {
        Csv {
            hasHeaderRecord = true
            ignoreUnknownColumns = true
            serializersModule = module
        }
    }
    var bson: KBson by SetOnce {
        KBson(module, Configuration())
    }
    var xml: XML by SetOnce {
        XML(module) {
        }
    }
    var cbor: Cbor by SetOnce {
        Cbor {
            ignoreUnknownKeys = true
            serializersModule = module
        }
    }
    var javaData: JavaData by SetOnce {
        JavaData(module)
    }
    var properties: Properties by SetOnce {
        Properties(module)
    }
}
