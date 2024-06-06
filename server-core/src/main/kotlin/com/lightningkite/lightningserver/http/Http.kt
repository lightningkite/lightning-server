package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.HtmlDefaults
import com.lightningkite.lightningserver.auth.authAny
import com.lightningkite.lightningserver.core.serverLogger
import com.lightningkite.lightningserver.exceptions.HttpStatusException
import com.lightningkite.lightningserver.exceptions.report
import com.lightningkite.lightningserver.metrics.Metrics
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.tasks.Tasks
import com.lightningkite.lightningserver.utils.MutableMapWithChangeHandler
import org.slf4j.LoggerFactory

object Http {
    init {
        Metrics
    }

    var fixEndingSlash: Boolean = true

    private val logger = LoggerFactory.getLogger("com.lightningkite.lightningserver.http.Http")

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
                if (generalSettings().debug) {
                    println(exception.toLSError())
                    logger.warn(exception.toLSError().toString())
                }
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

    var interceptors = listOf<HttpInterceptor>()
        set(value) {
            field = value
            // WARNING: This will melt your brain
            fullAction = interceptors.fold<HttpInterceptor, HttpInterceptor>({ request, handler -> handler(request) }) { total, wrapper ->
                return@fold { request, handler ->
                    total(request) { wrapper(it, handler) }
                }
            }
        }
    private var fullAction: HttpInterceptor = { req, cont -> cont(req) }

    suspend fun execute(request: HttpRequest): HttpResponse {
        return endpoints[request.endpoint]?.let { handler ->
            val authOrNull = request.authAny()
            serverLogger.info("${request.endpoint} (${request.parts}) accessed by ${authOrNull} (${request.sourceIp})")
            try {
                Metrics.handlerPerformance(request.endpoint) {
                    fullAction(request, handler)
                }
            } catch (e: Exception) {
                exception(request, e)
            }
        } ?: notFound(request.endpoint, request).also {
            if (generalSettings().debug) {
                logger.warn("${request.endpoint} not found!")
            }
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
        Http.execute(req)
    } catch (e: HttpStatusException) {
        e.toResponse(req)
    }
}

typealias HttpInterceptor = suspend (request: HttpRequest, cont: suspend (HttpRequest) -> HttpResponse) -> HttpResponse