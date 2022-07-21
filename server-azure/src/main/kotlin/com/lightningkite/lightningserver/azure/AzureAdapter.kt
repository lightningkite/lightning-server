package com.lightningkite.lightningserver.azure

import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.exceptions.HttpStatusException
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.http.HttpMethod
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.toMultipartContent
import com.microsoft.azure.functions.*
import kotlinx.coroutines.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.util.*
import com.lightningkite.lightningserver.http.HttpStatus as HttpStatus1

abstract class AzureAdapter {
    companion object {
        val logger: Logger = LoggerFactory.getLogger(AzureAdapter::class.java)
        val httpMatcher by lazy { HttpEndpointMatcher(Http.endpoints.keys.asSequence()) }
    }

    fun processCors(request: HttpRequestMessage<Optional<String>>, responseBuilder: HttpResponseMessage.Builder) {
        val inHeaders = HttpHeaders(request.headers)
        val cors = generalSettings().cors ?: run {
            return
        }
        val origin = inHeaders[HttpHeader.Origin] ?: run {
            return
        }
        val matches = cors.allowedDomains.any {
            it == "*" || it == origin || origin.endsWith(it.removePrefix("*"))
        }
        if(!matches) {
            return
        }
        responseBuilder.header(HttpHeader.AccessControlAllowOrigin, origin)
        responseBuilder.header(HttpHeader.AccessControlAllowMethods, inHeaders[HttpHeader.AccessControlRequestMethod] ?: "GET")
        responseBuilder.header(HttpHeader.AccessControlAllowHeaders, cors.allowedHeaders.joinToString(", "))
        responseBuilder.header(HttpHeader.AccessControlAllowCredentials, "true")
    }

    open fun http(
        request: HttpRequestMessage<Optional<String>>,
        context: ExecutionContext
    ): HttpResponseMessage {
        val inHeaders = HttpHeaders(request.headers)
        logger.debug("--> ${request.uri} ${request.httpMethod}")
        if(request.httpMethod == com.microsoft.azure.functions.HttpMethod.OPTIONS) {
            return request.createResponseBuilder(HttpStatus.NO_CONTENT)
                .apply { processCors(request, this) }
                .build()
        } else {
            val response = try {
                runBlocking {
                    val lookup = request.uri.path.removePrefix("/api")
                    val match = httpMatcher.match(
                        lookup,
                        HttpMethod(request.httpMethod.name.uppercase())
                    ) ?: run {
                        logger.debug("No route found for $lookup")
                        return@runBlocking HttpResponse(status = HttpStatus1.NotFound)
                    }
                    val request2 = HttpRequest(
                        endpoint = match.endpoint,
                        parts = match.parts,
                        wildcard = match.wildcard,
                        queryParameters = request.queryParameters.entries.map { it.toPair() },
                        headers = inHeaders,
                        body = if (inHeaders.contentType == ContentType.MultiPart.FormData) {
                            ByteArrayInputStream(request.body.get().toByteArray(Charset.defaultCharset())).toMultipartContent(inHeaders.contentType!!)
                        } else if (request.body.isPresent)
                            HttpContent.Text(
                                request.body.get(),
                                inHeaders.contentType ?: ContentType.Application.Json
                            )
                        else null,
                        domain = request.uri.host,
                        sourceIp = inHeaders[HttpHeader.XForwardedFor] ?: "255.255.255.255",
                        protocol = request.uri.scheme
                    )
                    val result = try {
                        Http.endpoints[match.endpoint]!!.invoke(request2)
                    } catch(e: HttpStatusException) {
                        e.toResponse(request2)
                    }
                    logger.debug("<-- ${request.uri} ${request.httpMethod} ${result.status}")
                    result
                }
            } catch (e: Throwable) {
                e.printStackTrace()
                logger.debug("<-- ${request.uri} ${request.httpMethod} 500 ISE ${e.message}")
                null
            } ?: return request
                .createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR)
                .apply { processCors(request, this) }
                .build()
            return request.createResponseBuilder(HttpStatus.valueOf(response.status.code)).apply {
                response.headers.entries
                    .filter { it.first.lowercase() != "transfer-encoding" }
                    .forEach {
                        header(it.first, it.second)
                    }
                response.body?.length?.let {
                    header(HttpHeader.ContentLength, it.toString())
                }
                response.body?.let {
                    runBlocking {
                        body(it.stream().readBytes())
                    }
                    header(HttpHeader.ContentType, it.type.toString())
                }
                processCors(request, this)
            }.build()
        }
    }
}
