package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.auth.AuthOptions
import com.lightningkite.lightningserver.auth.RequestAuth
import com.lightningkite.lightningserver.auth.assert
import com.lightningkite.lightningserver.auth.authChecked
import com.lightningkite.lightningserver.auth.authOptions
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.Request
import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.serialization.KSerializer
import java.net.URLDecoder


suspend fun <USER: HasId<*>?, PATH: TypedServerPath> PATH.authAndPathParts(
    request: Request,
    authOptions: AuthOptions<USER>,
): AuthAndPathParts<USER, PATH> {
    val wildcards = path.segments.filterIsInstance<ServerPath.Segment.Wildcard>()
    return AuthAndPathParts<USER, PATH>(
        authOrNull = request.authChecked(authOptions),
        rawRequest = request,
        parts = serializers.mapIndexed { idx, ser ->
            val name = wildcards.get(idx).name
            val str = request.parts[name] ?: throw BadRequestException("Route segment $name not found")
            str.parseUrlPartOrBadRequest(ser)
        }.toTypedArray()
    ).also {
        authOptions.assert(it.authOrNull)
    }
}

private fun <T> String.parseUrlPartOrBadRequest(serializer: KSerializer<T>): T = try {
    Serialization.fromString(URLDecoder.decode(this, Charsets.UTF_8), serializer)
} catch (e: Exception) {
    throw BadRequestException("Path part ${this} could not be parsed as a ${serializer.descriptor.serialName}.")
}