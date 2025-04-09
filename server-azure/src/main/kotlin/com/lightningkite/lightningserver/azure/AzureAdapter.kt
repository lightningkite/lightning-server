package com.lightningkite.lightningserver.azure

import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.exceptions.HttpStatusException
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.http.HttpMethod
import com.lightningkite.lightningserver.settings.generalSettings
import com.microsoft.azure.functions.*
import kotlinx.coroutines.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.util.*
import com.lightningkite.UUID
import com.lightningkite.lightningserver.cors.extensionForEngineAddCors
import com.lightningkite.lightningserver.cors.generateCorsHeaders
import com.lightningkite.lightningserver.cors.generatePreflightCorsHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.httpMethod
import io.ktor.server.response.header
import io.ktor.server.response.respond
import com.lightningkite.lightningserver.http.HttpStatus as HttpStatus1

abstract class AzureAdapter {
    companion object {
        val logger: Logger = LoggerFactory.getLogger(AzureAdapter::class.java)
    }

    open fun http(
        request: HttpRequestMessage<Optional<String>>,
        context: ExecutionContext,
    ): HttpResponseMessage {
        val inHeaders = HttpHeaders(request.headers)
        logger.debug("--> ${request.uri} ${request.httpMethod}")
        if (request.httpMethod == com.microsoft.azure.functions.HttpMethod.OPTIONS) {
            return request.createResponseBuilder(HttpStatus.NO_CONTENT)
                .apply {
                    generalSettings().cors
                        .generatePreflightCorsHeaders(HttpHeaders(request.headers))
                        .entries
                        .forEach { (key, value) -> this.header(key, value) }
                }
                .build()
        } else {
            val response = try {
                runBlocking {
                    val lookup = request.uri.path.removePrefix("/api")
                    val match = Http.matcher.match(
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
                            ByteArrayInputStream(
                                request.body.get().toByteArray(Charset.defaultCharset())
                            ).toMultipartContent(inHeaders.contentType!!, inHeaders.contentLength)
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
                        Http.execute(request2).extensionForEngineAddCors(request2)
                    } catch (e: HttpStatusException) {
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
                        body(it.stream().use { it.readBytes() })
                    }
                    header(HttpHeader.ContentType, it.type.toString())
                }
            }.build()
        }
    }
}
