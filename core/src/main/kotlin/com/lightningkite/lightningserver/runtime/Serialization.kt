package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.FormDataFormat
import com.lightningkite.services.data.KotlinBytesFormat
import com.lightningkite.services.data.StringArrayFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule

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