@file:SharedCode
package com.lightningkite.ktordb.live

import com.lightningkite.khrysalis.IsCodableAndHashable
import com.lightningkite.khrysalis.SharedCode
import com.lightningkite.ktordb.*
import com.lightningkite.ktordb.HasId
import com.lightningkite.rx.okhttp.defaultJsonMapper
import kotlinx.serialization.serializer
import java.util.*

class LiveCompleteModelApi<Model : HasId<UUID>>(
    override val read: ReadModelApi<Model>,
    override val write: WriteModelApi<Model>,
    override val observe: ObserveModelApi<Model>
) : CompleteModelApi<Model>() {
    companion object {
        inline fun <reified Model : HasId<UUID>> create(
            root: String,
            multiplexSocketUrl: String,
            path: String,
            token: String,
            headers: Map<String, String> = mapOf(),
        ): LiveCompleteModelApi<Model> = LiveCompleteModelApi(
            read = LiveReadModelApi.create(root, path, token, headers),
            write = LiveWriteModelApi.create(root, path, token, headers),
            observe = LiveObserveModelApi.create(multiplexSocketUrl, token, headers, path)
        )
    }
}