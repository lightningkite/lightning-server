package com.lightningkite.ktorbatteries.serialization

import com.github.jershell.kbson.BsonFlexibleDecoder
import com.github.jershell.kbson.Configuration
import com.github.jershell.kbson.KBson
import com.lightningkite.ktorbatteries.SetOnce
import com.lightningkite.ktorbatteries.files.ExternalServerFileSerializer
import com.lightningkite.ktordb.ClientModule
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.csv.Csv
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.overwriteWith
import kotlinx.serialization.modules.serializersModuleOf
import kotlinx.serialization.properties.Properties
import kotlinx.serialization.protobuf.ProtoBuf
import nl.adaptivity.xmlutil.serialization.XML

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
    var protobuf: ProtoBuf by SetOnce {
        ProtoBuf {
            serializersModule = module
        }
    }
    var properties: Properties by SetOnce {
        Properties(module)
    }
}
