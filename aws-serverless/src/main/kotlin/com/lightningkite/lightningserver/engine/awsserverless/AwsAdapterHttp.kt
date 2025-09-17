package com.lightningkite.lightningserver.engine.awsserverless

import com.lightningkite.MediaType
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpHeaderValue
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.RawHttpEndpoint
import com.lightningkite.lightningserver.runtime.addCorsHeaders
import com.lightningkite.lightningserver.runtime.handle
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.TypedData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.util.Base64


internal class AwsAdapterHttp(val root: AwsAdapter) {
    suspend fun handleHttp(event: APIGatewayV2HTTPEvent, setRoughContext: (String) -> Unit): APIGatewayV2HTTPResponse {
        val method = HttpMethod(event.httpMethod)
        val path = event.path.removePrefix("/" + event.requestContext.stage)
        val headers = HttpHeaders(event.multiValueHeaders.entries.flatMap { it.value.map { v -> it.key to v } })
        val body = event.body?.let { raw ->
            if (event.isBase64Encoded)
                TypedData.bytes(
                    Base64.getDecoder().decode(raw),
                    headers.contentType ?: MediaType.Application.OctetStream
                )
            else
                TypedData.text(raw, headers.contentType ?: MediaType.Text.Plain)
        }
        val queryParams =
            (event.multiValueQueryStringParameters
                ?: mapOf()).entries.flatMap { it.value.map { v -> it.key to URLDecoder.decode(v, Charsets.UTF_8) } }

        val request = HttpRequest<PathSpec>(
            path = RawHttpEndpoint(path, method),
            queryParameters = queryParams,
            headers = headers,
            body = body,
            domain = event.requestContext.domainName,
            protocol = "https",
            sourceIp = event.requestContext.identity.sourceIp
        )
        val result = root.handle(request)
        return result.toAws()
    }
}


internal suspend fun HttpResponse.toAws(
): APIGatewayV2HTTPResponse {
    val outHeaders = headers.toAwsMap()
        .toMutableMap()

    val b = body
    b?.mediaType?.let { outHeaders.put(HttpHeader.ContentType, it.toString()) }
    b?.data?.size?.let { outHeaders.put(HttpHeader.ContentLength, it.toString()) }
    when(val data = b?.data) {
        null -> {
            val response = APIGatewayV2HTTPResponse(
                statusCode = status.code,
                headers = outHeaders,
            )
            return response
        }

        is Data.Text -> {
            val response = withContext(Dispatchers.IO) {
                APIGatewayV2HTTPResponse(
                    statusCode = this@toAws.status.code,
                    headers = outHeaders,
                    body = data.data,
                )
            }
            return response
        }

        else -> {
            val response = withContext(Dispatchers.IO) {
                APIGatewayV2HTTPResponse(
                    statusCode = this@toAws.status.code,
                    headers = outHeaders,
                    body = Base64.getEncoder().encodeToString(data.bytes()),
                    isBase64Encoded = true,
                )
            }
            return response
        }
    }
}

internal fun HttpHeaders.toAwsMap(): Map<String, String> = buildMap<String, MutableList<String>> headerMap@{
    this@toAwsMap.normalizedEntries.forEach { (key: String, value: List<HttpHeaderValue>) ->
        // AWS does not allow repeated Header keys. We must combine repeated headers into the list form under one
        // instance, EXCEPT for Set-Cookie. Set-Cookie is weird, and if you turn multiple into a list the browser
        // may not accept them all. AWS response headers ARE case SENSITIVE, so if you defined multiple set-cookies
        // with different casings, they will all go through. This is a jank work around for this issue.
        if (key.equals(HttpHeader.SetCookie, ignoreCase = true))
            this@headerMap.getOrPut(key, { mutableListOf() }).addAll(value.map { it.toHttpString() })
        else {
            this@headerMap.getOrPut(key.lowercase(), { mutableListOf() }).addAll(value.map { it.toHttpString() })
        }
    }
}
    .mapValues { (_, values) -> values.joinToString(", ") { it } }