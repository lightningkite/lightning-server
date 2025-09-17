package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.data.SerializableCache
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.pathing.RawHttpEndpoint
import com.lightningkite.services.data.TypedData
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
public data class HttpRequest<PATH: PathSpec>(
    override val path: RawHttpEndpoint<PATH>,
    override val queryParameters: List<Pair<String, String>>,
    override val headers: HttpHeaders,
    override val domain: String,
    override val protocol: String,
    override val sourceIp: String,
    override val cache: SerializableCache = SerializableCache(),
    @Transient public val body: TypedData? = null,
) : Request<PATH>() {
    public fun <PATH2: PathSpec> copyWithNewPathType(
        path: RawHttpEndpoint<PATH2> ,
        queryParameters: List<Pair<String, String>> = this.queryParameters,
        headers: HttpHeaders = this.headers,
        domain: String = this.domain,
        protocol: String = this.protocol,
        sourceIp: String = this.sourceIp,
        cache: SerializableCache = this.cache,
        body: TypedData? = this.body,
    ): HttpRequest<PATH2> = HttpRequest(
        path = path,
        queryParameters = queryParameters,
        headers = headers,
        domain = domain,
        protocol = protocol,
        sourceIp = sourceIp,
        cache = cache,
        body = body,
    )
}