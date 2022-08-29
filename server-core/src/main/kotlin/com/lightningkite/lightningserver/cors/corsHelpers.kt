package com.lightningkite.lightningserver.cors

import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.settings.generalSettings
import java.util.*


fun HttpResponse.addCors(request: HttpRequest): HttpResponse {
    val cors = generalSettings().cors ?: run {
        return this
    }
    val origin = request.headers[HttpHeader.Origin] ?: run {
        return this
    }
    val matches = cors.allowedDomains.any {
        it == "*" || it == origin || origin.endsWith(it.removePrefix("*"))
    }
    if(!matches) {
        return this
    }
    return this.copy(
        headers = HttpHeaders(this.headers.entries + listOf(
            HttpHeader.AccessControlAllowOrigin to origin,
            HttpHeader.AccessControlAllowMethods to (request.headers[HttpHeader.AccessControlRequestMethod] ?: "GET"),
            HttpHeader.AccessControlAllowHeaders to cors.allowedHeaders.joinToString(", "),
            HttpHeader.AccessControlAllowCredentials to "true",
        ))
    )
}