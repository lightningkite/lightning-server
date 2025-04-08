package com.lightningkite.lightningserver.cors

import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.settings.CorsSettings
import com.lightningkite.lightningserver.settings.generalSettings
import org.jetbrains.annotations.TestOnly


fun HttpResponse.extensionForEngineAddCors(request: HttpRequest): HttpResponse {
    return this.copy(
        headers = this.headers + generalSettings().cors.generateCorsHeaders(request.headers)
    )
}

@TestOnly
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

fun CorsSettings?.generateCorsHeaders(incomingHeaders: HttpHeaders): HttpHeaders {
    if (this == null) return HttpHeaders()
    val origin = incomingHeaders[HttpHeader.Origin] ?: return HttpHeaders()

    return HttpHeaders headers@{

        this@headers.set(
            HttpHeader.AccessControlAllowOrigin,
            this@generateCorsHeaders.allowedDomains
                ?.let { allowed -> if (originMatches(allowed, origin)) origin else return HttpHeaders() }
                ?: this@generateCorsHeaders.limitToDomains
                    ?.let { allowed -> if (originMatches(allowed, origin)) origin else return HttpHeaders() }
                ?: origin
        )
        if (this@generateCorsHeaders.exposedHeaders?.isNotEmpty() == true)
            this@headers.set(
                HttpHeader.AccessControlExposeHeaders,
                this@generateCorsHeaders.exposedHeaders.joinToString()
            )
        if (this@generateCorsHeaders.allowCredentials == true)
            this@headers.set(
                HttpHeader.AccessControlAllowCredentials,
                "true"
            )
    }
}

fun CorsSettings?.generatePreflightCorsHeaders(incomingHeaders: HttpHeaders): HttpHeaders {
    if (this == null) return HttpHeaders()
    val origin = incomingHeaders[HttpHeader.Origin] ?: return HttpHeaders()

    return HttpHeaders headers@{
        this@headers.set(
            HttpHeader.AccessControlAllowOrigin,
            this@generatePreflightCorsHeaders.allowedDomains
                ?.let { allowed -> if (originMatches(allowed, origin)) origin else return HttpHeaders() }
                ?: this@generatePreflightCorsHeaders.limitToDomains
                    ?.let { allowed -> if (originMatches(allowed, origin)) origin else return HttpHeaders() }
                ?: origin
        )
        this@headers.set(
            HttpHeader.AccessControlAllowMethods,
            this@generatePreflightCorsHeaders.limitToMethods?.joinToString()
                ?: incomingHeaders[HttpHeader.AccessControlRequestMethod]
                ?: ""
        )
        this@headers.set(
            HttpHeader.AccessControlAllowHeaders,
            this@generatePreflightCorsHeaders.allowedHeaders
                ?.let { allowed ->
                    buildList {
                        add(HttpHeader.ContentType)
                        add(HttpHeader.Authorization)

                        allowed.forEach {
                            if (it == "*") add(incomingHeaders[HttpHeader.AccessControlRequestHeaders])
                            else add(it)
                        }
                    }
                        .joinToString()
                }
                ?: this@generatePreflightCorsHeaders.limitToHeaders?.joinToString()
                ?: incomingHeaders[HttpHeader.AccessControlRequestHeaders]
                ?: ""
        )
        if (this@generatePreflightCorsHeaders.allowCredentials == true)
            this@headers.set(
                HttpHeader.AccessControlAllowCredentials,
                "true"
            )
    }

}
