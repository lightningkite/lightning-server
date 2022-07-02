@file:SharedCode
package com.lightningkite.lightningdb.live

import com.lightningkite.khrysalis.SharedCode
import com.lightningkite.lightningdb.*
import com.lightningkite.lightningdb.HasId
import com.lightningkite.rx.okhttp.defaultJsonMapper
import kotlinx.serialization.serializer
import java.util.*

class LiveFullReadModelApi<Model : HasId<UUID>>(
    override val read: LiveReadModelApi<Model>,
    override val observe: ObserveModelApi<Model>
) : FullReadModelApi<Model>() {
    companion object {
        inline fun <reified Model : HasId<UUID>> create(
            root: String,
            multiplexSocketUrl: String,
            path: String,
            token: String,
            headers: Map<String, String> = mapOf(),
        ): LiveFullReadModelApi<Model> = LiveFullReadModelApi(
            read = LiveReadModelApi("$root$path", token, headers, defaultJsonMapper.serializersModule.serializer()),
            observe = LiveObserveModelApi.create(multiplexSocketUrl, token, headers, path)
        )
    }
}