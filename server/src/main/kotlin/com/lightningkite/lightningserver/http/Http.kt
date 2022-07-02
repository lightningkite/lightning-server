package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.exceptions.HttpStatusException
import com.lightningkite.lightningserver.serialization.toHttpContent
import com.lightningkite.lightningserver.settings.GeneralServerSettings

object Http {
    val routes = mutableMapOf<HttpRoute, suspend (HttpRequest) -> HttpResponse>()
    var exception: suspend (HttpRequest, Exception) -> HttpResponse =
        { request, exception ->

            if (exception is HttpStatusException) {
                exception.toResponse(request)
            } else {
                HttpResponse(status = HttpStatus.InternalServerError)
            }
        }
}

suspend fun HttpRoute.test(
    parts: Map<String, String> = mapOf(),
    wildcard: String? = null,
    queryParameters: List<Pair<String, String>> = listOf(),
    headers: HttpHeaders = HttpHeaders.EMPTY,
    body: HttpContent? = null,
    domain: String = GeneralServerSettings.instance.publicUrl.substringAfter("://").substringBefore("/"),
    protocol: String = GeneralServerSettings.instance.publicUrl.substringBefore("://"),
    sourceIp: String = "0.0.0.0"
): HttpResponse {
    val req = HttpRequest(
        route = this,
        parts = parts,
        wildcard = wildcard,
        queryParameters = queryParameters,
        headers = headers,
        body = body,
        domain = domain,
        protocol = protocol,
        sourceIp = sourceIp,
    )
    return try {
        Http.routes[this]!!.invoke(req)
    } catch(e: HttpStatusException) {
        e.toResponse(req)
    }
}