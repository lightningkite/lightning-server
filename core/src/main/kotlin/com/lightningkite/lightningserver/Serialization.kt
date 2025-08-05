package com.lightningkite.lightningserver

import com.lightningkite.serviceabstractions.data.KotlinBytesFormat
import com.lightningkite.serviceabstractions.data.StringArrayFormat
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.overwriteWith
import kotlinx.serialization.modules.serializersModuleOf
import kotlinx.serialization.properties.Properties
import java.util.UUID

public open class Serialization(public val serializersModule: SerializersModule = SerializersModule { }) {
    public open val stringArrayFormat: StringArrayFormat = StringArrayFormat(serializersModule)
    public open val kotlinBytesFormat: KotlinBytesFormat = KotlinBytesFormat(serializersModule)
    public open val formDataFormat: FormDataFormat = FormDataFormat(serializersModule)
    public open val json: Json = Json {
        this.serializersModule = this@Serialization.serializersModule
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }
}