@file:SharedCode
package com.lightningkite.ktordb.live

import com.lightningkite.khrysalis.SharedCode
import com.lightningkite.ktordb.*
import com.lightningkite.ktordb.HasId
import com.lightningkite.rx.okhttp.defaultJsonMapper
import kotlinx.serialization.serializer

class LiveFullReadModelApi<Model : HasId>(
    override val read: LiveReadModelApi<Model>,
    override val observe: ObserveModelApi<Model>
) : FullReadModelApi<Model>() {
    companion object {
        inline fun <reified Model : HasId> create(
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