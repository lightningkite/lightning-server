package com.lightningkite.lightningserver.azure

import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.exceptions.HttpStatusException
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.http.HttpMethod
import com.lightningkite.lightningserver.serialization.toMultipartContent
import com.microsoft.azure.functions.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.util.*
import com.lightningkite.lightningserver.http.HttpStatus as HttpStatus1

abstract class AzureAdapter {
    companion object {
        val logger: Logger = LoggerFactory.getLogger(AzureAdapter::class.java)
        val httpMatcher by lazy { HttpEndpointMatcher(Http.endpoints.keys.asSequence()) }
    }

    open fun http(
        request: HttpRequestMessage<Optional<ByteArray>>,
        context: ExecutionContext
    ): HttpResponseMessage {
        logger.debug("--> ${request.uri} ${request.httpMethod}")
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
                val inHeaders = HttpHeaders(request.headers)
                val request2 = HttpRequest(
                    route = match.endpoint,
                    parts = match.parts,
                    wildcard = match.wildcard,
                    queryParameters = request.queryParameters.entries.map { it.toPair() },
                    headers = inHeaders,
                    body = if (inHeaders.contentType == ContentType.MultiPart.FormData) {
                        ByteArrayInputStream(request.body.get()).toMultipartContent(inHeaders.contentType!!)
                    } else if (request.body.isPresent)
                        HttpContent.Binary(
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
        }.build()
    }
}
