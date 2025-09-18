package com.lightningkite.lightningserver.cors

import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.definition.CorsSettings
import com.lightningkite.lightningserver.http.*


@InternalLightningServerApi
public fun HttpResponse.addCorsHeaders(request: HttpRequest<*>, cors: CorsSettings): HttpResponse {
    return this.copy(
        headers = this.headers + HttpHeaders(cors.generateCorsHeaders(request.headers))
    )
}

internal fun originMatches(allowed: List<String>, origin: String): Boolean {
    val originSchema = origin.substringBefore("://")
    val originTrimmed = origin.substringAfter("://")
    return allowed
        .any {
            val allowedSchema = it.substringBefore("://", "")
            val allowedTrimmed = it.substringAfter("://")
            (allowedSchema.isBlank() || originSchema == allowedSchema) &&
                    (allowedTrimmed == "*" ||
                            allowedTrimmed == originTrimmed ||
                            (allowedTrimmed.startsWith('*') && originTrimmed.endsWith(allowedTrimmed.removePrefix("*"))))
        }
}
// TODO: include Access-Control-Max-Age for extra performance?

@InternalLightningServerApi
public fun CorsSettings?.generateCorsHeaders(incomingHeaders: HttpHeaders): Map<String, List<HttpHeaderValue>> {
    if (this == null) return emptyMap()
    val origin = incomingHeaders[HttpHeader.Origin]?.root ?: return emptyMap()

    return buildMap headers@{

        this@headers[HttpHeader.AccessControlAllowOrigin] = listOf(
            HttpHeaderValue(
                this@generateCorsHeaders.limitToDomains
                    ?.let { allowed -> if (originMatches(allowed, origin)) origin else return emptyMap() }
                    ?: origin,
                emptyMap()
            ))

        if (this@generateCorsHeaders.exposedHeaders?.isNotEmpty() == true)
            this@headers[HttpHeader.AccessControlExposeHeaders] = listOf(
                HttpHeaderValue(
                    this@generateCorsHeaders.exposedHeaders.joinToString(),
                    emptyMap()
                )
            )

        if (this@generateCorsHeaders.allowCredentials == true)
            this@headers[HttpHeader.AccessControlAllowCredentials] = listOf(
                HttpHeaderValue(
                    "true",
                    emptyMap()
                )
            )
    }
}

@InternalLightningServerApi
public fun CorsSettings?.generatePreflightCorsHeaders(incomingHeaders: HttpHeaders): Map<String, List<HttpHeaderValue>> {
    if (this == null) return emptyMap()
    val origin = incomingHeaders[HttpHeader.Origin]?.root ?: return emptyMap()

    return buildMap headers@{
        this@headers[HttpHeader.AccessControlAllowOrigin] = listOf(
            HttpHeaderValue(
                this@generatePreflightCorsHeaders.limitToDomains
                    ?.let { allowed -> if (originMatches(allowed, origin)) origin else return emptyMap() }
                    ?: origin,
                emptyMap()
            ))
        this@headers[HttpHeader.AccessControlAllowMethods] = listOf(
            HttpHeaderValue(
                this@generatePreflightCorsHeaders.limitToMethods?.joinToString()
                    ?: incomingHeaders[HttpHeader.AccessControlRequestMethod]?.root
                    ?: "",
                emptyMap()
            )
        )

        this@headers[HttpHeader.AccessControlAllowHeaders] = listOf(
            HttpHeaderValue(
                this@generatePreflightCorsHeaders.limitToHeaders?.joinToString()
                    ?: incomingHeaders[HttpHeader.AccessControlRequestHeaders]?.root
                    ?: "",
                emptyMap()
            )
        )

        if (this@generatePreflightCorsHeaders.allowCredentials == true)
            this@headers[HttpHeader.AccessControlAllowCredentials] = listOf(
                HttpHeaderValue(
                    "true",
                    emptyMap()
                )
            )

    }

}
