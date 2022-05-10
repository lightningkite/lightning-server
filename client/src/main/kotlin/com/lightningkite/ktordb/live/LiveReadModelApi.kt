@file:SharedCode
package com.lightningkite.ktordb.live

import com.lightningkite.khrysalis.SharedCode
import com.lightningkite.ktordb.*
import com.lightningkite.ktordb.HasId
import com.lightningkite.ktordb.UUIDFor
import com.lightningkite.rx.okhttp.HttpClient
import com.lightningkite.rx.okhttp.readJson
import com.lightningkite.rx.okhttp.toJsonRequestBody
import io.reactivex.rxjava3.core.Single
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.Response

class LiveReadModelApi<Model : HasId>(
    val url: String,
    val token: String,
    val serializer: KSerializer<Model>
) : ReadModelApi<Model>() {

    override fun list(query: Query<Model>): Single<List<Model>> = HttpClient.call(
        url = "$url/query",
        method = HttpClient.POST,
        headers = mapOf("Authorization" to "Bearer $token"),
        body = query.toJsonRequestBody(),
    ).readJson(ListSerializer(serializer))


    override fun get(id: UUIDFor<Model>): Single<Model> = HttpClient.call(
        url = "$url/$id",
        method = HttpClient.GET,
        headers = mapOf("Authorization" to "Bearer $token"),
    ).readJson(serializer)

}

