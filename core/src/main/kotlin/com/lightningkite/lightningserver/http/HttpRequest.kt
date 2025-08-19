package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.SerializableCache
import com.lightningkite.lightningserver.pathing.RawPath
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.Request
import com.lightningkite.services.data.TypedData
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
public data class HttpRequest<PATH: PathSpec>(
    override val path: RawPath<PATH>,
    override val queryParameters: List<Pair<String, String>>,
    override val headers: HttpHeaders,
    override val domain: String,
    override val protocol: String,
    override val sourceIp: String,
    public val method: HttpMethod,
    override val cache: SerializableCache = SerializableCache(),
    @Transient public val body: TypedData? = null,
) : Request<PATH>()