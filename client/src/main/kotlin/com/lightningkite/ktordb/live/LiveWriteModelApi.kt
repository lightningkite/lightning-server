@file:SharedCode
package com.lightningkite.ktordb.live

import com.lightningkite.khrysalis.SharedCode
import com.lightningkite.ktordb.*
import com.lightningkite.rx.okhttp.HttpClient
import com.lightningkite.rx.okhttp.discard
import com.lightningkite.rx.okhttp.readJson
import com.lightningkite.rx.okhttp.toJsonRequestBody
import io.reactivex.rxjava3.core.Single
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.RequestBody
import okhttp3.Response
import java.util.*

class LiveWriteModelApi<Model : HasId<UUID>>(
    val url: String,
    token: String,
    headers: Map<String, String>,
    val serializer: KSerializer<Model>
) : WriteModelApi<Model>() {

    private val authHeaders = headers + mapOf("Authorization" to "Bearer $token")

    override fun post(value: Model): Single<Model> = HttpClient.call(
        url = url,
        method = HttpClient.POST,
        headers = authHeaders,
        body = value.toJsonRequestBody(serializer),
    ).readJson(serializer)

    override fun postBulk(values: List<Model>): Single<List<Model>> = HttpClient.call(
        url = "$url/bulk",
        method = HttpClient.POST,
        headers = authHeaders,
        body = values.toJsonRequestBody(ListSerializer(serializer)),
    ).readJson(ListSerializer(serializer))

    override fun put(value: Model): Single<Model> = HttpClient.call(
        url = "$url/${value._id}",
        method = HttpClient.PUT,
        headers = authHeaders,
        body = value.toJsonRequestBody(serializer),
    ).readJson(serializer)

    override fun putBulk(values: List<Model>): Single<List<Model>> = HttpClient.call(
        url = "$url/bulk",
        method = HttpClient.PUT,
        headers = authHeaders,
        body = values.toJsonRequestBody(ListSerializer(serializer)),
    ).readJson(ListSerializer(serializer))

    override fun patch(id: UUIDFor<Model>, modification: Modification<Model>): Single<Model> = HttpClient.call(
        url = "$url/$id",
        method = HttpClient.PATCH,
        headers = authHeaders,
        body = modification.toJsonRequestBody(Modification.serializer(serializer)),
    ).readJson(serializer)

    override fun patchBulk(modification: MassModification<Model>): Single<List<Model>> =
        HttpClient.call(
            url = "$url/bulk",
            method = HttpClient.PATCH,
            headers = authHeaders,
            body = modification.toJsonRequestBody(MassModification.serializer(serializer)),
        ).readJson(ListSerializer(serializer))

    override fun delete(id: UUIDFor<Model>): Single<Unit> = HttpClient.call(
        url = "$url/$id",
        method = HttpClient.DELETE,
        headers = authHeaders,
    ).discard()

    override fun deleteBulk(condition: Condition<Model>): Single<Unit> = HttpClient.call(
        url = "$url/bulk",
        method = HttpClient.DELETE,
        headers = authHeaders,
        body = condition.toJsonRequestBody(),
    ).discard()
}