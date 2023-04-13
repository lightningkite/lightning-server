package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.HtmlDefaults
import com.lightningkite.lightningserver.exceptions.HttpStatusException
import com.lightningkite.lightningserver.exceptions.report
import com.lightningkite.lightningserver.metrics.Metrics
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.tasks.Tasks
import com.lightningkite.lightningserver.utils.MutableMapWithChangeHandler

object Http {
    init {
        Metrics
    }

    var fixEndingSlash: Boolean = true

    val endpoints: MutableMap<HttpEndpoint, suspend (HttpRequest) -> HttpResponse> =
        MutableMapWithChangeHandler<HttpEndpoint, suspend (HttpRequest) -> HttpResponse> {
            _matcher = null
        }
    private var _matcher: HttpEndpointMatcher? = null
    val matcher: HttpEndpointMatcher
        get() {
            return _matcher ?: run {
                val created = HttpEndpointMatcher(endpoints.keys.asSequence())
                _matcher = created
                created
            }
        }

    var exception: suspend (HttpRequest, Exception) -> HttpResponse =
        { request, exception ->
            if (exception is HttpStatusException) {
                exception.toResponse(request)
            } else {
                exception.report(request)
                HttpResponse(status = HttpStatus.InternalServerError)
            }
        }
    var notFound: suspend (HttpEndpoint, HttpRequest) -> HttpResponse = { path, request ->
        HttpResponse.html(
            HttpStatus.NotFound, content = HtmlDefaults.basePage(
                """
            <h1>Not Found</h1>
            <p>Sorry, the page you're looking for isn't here.</p>
            ${
                    if (generalSettings().debug) {
                        "<p>Your path is $path.</p>"
                    } else ""
                }
        """.trimIndent()
            )
        )
    }

    suspend fun execute(request: HttpRequest): HttpResponse {
        return Metrics.handlerPerformance(request.endpoint) {
            endpoints[request.endpoint]?.let { handler ->
                try {
                    handler(request)
                } catch (e: Exception) {
                    exception(request, e)
                }
            } ?: notFound(request.endpoint, request)
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
    Tasks.onSettingsReady()
    Tasks.onEngineReady()
    val req = HttpRequest(
        endpoint = this,
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
    } catch (e: HttpStatusException) {
        e.toResponse(req)
    }
}