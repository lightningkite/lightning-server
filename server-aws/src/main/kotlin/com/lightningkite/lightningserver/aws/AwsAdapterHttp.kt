package com.lightningkite.lightningserver.aws

import com.lightningkite.lightningserver.compression.extensionForEngineCompression
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.cors.extensionForEngineAddCors
import com.lightningkite.lightningserver.http.Http
import com.lightningkite.lightningserver.http.HttpContent
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.http.HttpEndpointMatcher
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpMethod
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.settings.CorsSettings
import com.lightningkite.lightningserver.settings.generalSettings
import io.ktor.http.decodeURLPart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Base64

class AwsAdapterHttp(val root: AwsAdapter) {
    suspend fun handleHttp(event: APIGatewayV2HTTPEvent, setRoughContext: (String) -> Unit): APIGatewayV2HTTPResponse {
        val method = HttpMethod(event.httpMethod)
        val path = event.path.removePrefix("/" + event.requestContext.stage)
        val headers = HttpHeaders(event.multiValueHeaders.entries.flatMap { it.value.map { v -> it.key to v } })
        val body = event.body?.let { raw ->
            if (event.isBase64Encoded)
                HttpContent.Binary(
                    Base64.getDecoder().decode(raw),
                    headers.contentType ?: ContentType.Application.OctetStream
                )
            else
                HttpContent.Text(raw, headers.contentType ?: ContentType.Text.Plain)
        }
        val queryParams =
            (event.multiValueQueryStringParameters
                ?: mapOf()).entries.flatMap { it.value.map { v -> it.key to v.decodeURLPart() } }

        val match = Http.matcher.match(path, method) ?: run {
            if (method == HttpMethod.OPTIONS) {
                val origin = headers[HttpHeader.Origin] ?: return APIGatewayV2HTTPResponse(
                    statusCode = 404,
                    body = "No matching path for '${path}' found"
                )
                val cors = generalSettings().cors ?: CorsSettings()
                val domainMatches = cors.allowedDomains.any {
                    it == "*" || it == origin || origin.endsWith(it.removePrefix("*"))
                }
                val headerMatches = headers[HttpHeader.AccessControlRequestHeaders]?.split(',')?.filter { header ->
                    cors.allowedHeaders.any {
                        it == "*" || it == header || header.endsWith(it.removePrefix("*"))
                    }
                } ?: emptyList()
                if (domainMatches) {
                    return APIGatewayV2HTTPResponse(
                        statusCode = HttpStatus.NoContent.code,
                        headers = mapOf(
                            HttpHeader.AccessControlAllowOrigin to (headers[HttpHeader.Origin] ?: "*"),
                            HttpHeader.AccessControlAllowMethods to "GET,POST,PUT,PATCH,DELETE,HEAD",
                            HttpHeader.AccessControlAllowHeaders to (headerMatches.joinToString(",")),
                            HttpHeader.AccessControlAllowCredentials to "true",
                        )
                    )
                } else {
                    HttpEndpointMatcher.Match(
                        HttpEndpoint(path, method),
                        parts = mapOf(),
                        wildcard = null
                    )
                }
            } else HttpEndpointMatcher.Match(
                HttpEndpoint(path, method),
                parts = mapOf(),
                wildcard = null
            )
        }
        setRoughContext(match.endpoint.toString())
        val request = HttpRequest(
            endpoint = match.endpoint,
            parts = match.parts,
            wildcard = match.wildcard,
            queryParameters = queryParams,
            headers = headers,
            body = body,
            domain = event.requestContext.domainName,
            protocol = "https",
            sourceIp = event.requestContext.identity.sourceIp
        )
        val result = Http.execute(request).extensionForEngineAddCors(request).extensionForEngineCompression(request)
        return result.toAws()
    }
}

internal suspend fun HttpResponse.toAws(
): APIGatewayV2HTTPResponse {
    val outHeaders = buildMap<String, MutableList<String>> headerMap@{
        headers.entries.forEach { (key, value) ->
            if (key.lowercase() == HttpHeader.SetCookie.lowercase())
                this@headerMap[key] = mutableListOf(value)
            else {
                this@headerMap.getOrPut(key.lowercase(), { mutableListOf() }).add(value)
            }
        }
    }
        .mapValues { (_, values) -> values.joinToString(", ") { it } }
        .toMutableMap()

    val b = body
    b?.type?.let { outHeaders.put(HttpHeader.ContentType, it.toString()) }
    b?.length?.let { outHeaders.put(HttpHeader.ContentLength, it.toString()) }
    when {
        b == null -> {
            val response = APIGatewayV2HTTPResponse(
                statusCode = status.code,
                headers = outHeaders,
//                cookies = cookies,
            )
            return response
        }

        b is HttpContent.Text -> {
            val response = withContext(Dispatchers.IO) {
                APIGatewayV2HTTPResponse(
                    statusCode = this@toAws.status.code,
                    headers = outHeaders,
                    body = b.text(),
//                    cookies = cookies,
                )
            }
            return response
        }

        b is HttpContent.Binary -> {
            val response = withContext(Dispatchers.IO) {
                APIGatewayV2HTTPResponse(
                    statusCode = this@toAws.status.code,
                    headers = outHeaders,
                    body = Base64.getEncoder().encodeToString(b.bytes),
                    isBase64Encoded = true,
//                    cookies = cookies,
                )
            }
            return response
        }

        else -> {
            val response = withContext(Dispatchers.IO) {
                APIGatewayV2HTTPResponse(
                    statusCode = this@toAws.status.code,
                    headers = outHeaders,
                    body = Base64.getEncoder().encodeToString(b.stream().use { it.readAllBytes() }),
                    isBase64Encoded = true,
//                    cookies = cookies,
                )
            }
            return response
        }
    }
}