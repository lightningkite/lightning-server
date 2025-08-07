package com.lightningkite.lightningserver

import com.lightningkite.services.data.TypedData
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
public class HttpRequest<PATH: PathSpec>(
    override val path: PathServer<PATH>,
    override val queryParameters: List<Pair<String, String>>,
    override val headers: HttpHeaders,
    override val domain: String,
    override val protocol: String,
    override val sourceIp: String,
    public val method: HttpMethod,
    override val cache: KeyedSerializableCache = KeyedSerializableCache(),
    @Transient public val body: TypedData? = null,
) : Request<PATH>()