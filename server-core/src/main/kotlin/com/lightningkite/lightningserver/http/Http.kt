package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.exceptions.HttpStatusException
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.tasks.Tasks

object Http {
    var fixEndingSlash: Boolean = true
    val endpoints = mutableMapOf<HttpEndpoint, suspend (HttpRequest) -> HttpResponse>()
    var exception: suspend (HttpRequest, Exception) -> HttpResponse =
        { request, exception ->

            if (exception is HttpStatusException) {
                exception.toResponse(request)
            } else {
                exception.printStackTrace()
                HttpResponse(status = HttpStatus.InternalServerError)
            }
        }
}

suspend fun HttpEndpoint.test(
    parts: Map<String, String> = mapOf(),
    wildcard: String? = null,
    queryParameters: List<Pair<String, String>> = listOf(),
    headers: HttpHeaders = HttpHeaders.EMPTY,
    body: HttpContent? = null,
    domain: String = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
    protocol: String = generalSettings().publicUrl.substringBefore("://"),
    sourceIp: String = "0.0.0.0"
): HttpResponse {
    Tasks.startup()
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
        Http.endpoints[this]!!.invoke(req)
    } catch(e: HttpStatusException) {
        e.toResponse(req)
    }
}