package com.lightningkite.lightningserver.engine.ktor

import com.lightningkite.MediaType
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.PathSegments
import com.lightningkite.lightningserver.http.QueryParameters
import com.lightningkite.lightningserver.logger
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.RawHttpEndpoint
import com.lightningkite.lightningserver.runtime.ServerRuntimeBase
import com.lightningkite.services.data.TypedData
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.util.*
import kotlinx.io.asSource

/**
 * Converts a Ktor ContentType to a Lightning Server MediaType.
 */
internal fun ContentType.adapt(): MediaType =
    MediaType(type = contentType, subtype = contentSubtype, parameters = parameters.associate { it.name to it.value })

/**
 * Converts Ktor Headers to Lightning Server HttpHeaders.
 */
internal fun Headers.adapt(): HttpHeaders = HttpHeaders(flattenEntries())

/**
 * Converts a Ktor ApplicationCall to a Lightning Server HttpRequest.
 *
 * Extracts the real client IP from the configured proxy header if available.
 * Falls back to the origin remote address if the header is not present.
 */
context(server: ServerRuntimeBase)
internal suspend fun ApplicationCall.adapt(): HttpRequest<PathSpec> {
    return HttpRequest(
        path = RawHttpEndpoint(request.path().decodeURLPart(), HttpMethod(request.httpMethod.value)),
        queryParameters = QueryParameters(request.queryParameters.flattenEntries()),
        headers = request.headers.adapt(),
        domain = request.origin.serverHost,
        protocol = request.origin.scheme,
        sourceIp = ktorRunConfig().realIpHeader?.let {
            request.header(it)
                ?: run { server.logger.warn { "Real IP address header for proxy '$it' was missing from the request." }; null }
        } ?: request.origin.remoteAddress,
        body = run {
            // TODO: Add MultiPart support
            val stream = receiveStream()

            TypedData.sink(request.contentType().adapt(), request.contentLength() ?: -1) {
                it.transferFrom(stream.asSource())
            }
        },
    )
}

// TODO: Implement MultiPart support - the code below is a partial implementation that needs completion

//internal fun MultiPartData.adapt(myType: MediaType):Flow<TypedData> {
//    return flow{
//            this@adapt.forEachPart {
//                emit(
//                    when (it) {
//                        is PartData.FormItem -> HttpContent.Multipart.formItem(
//                            it.name ?: "",
//                            it.value
//                        )
//
//                        is PartData.FileItem -> {
//                            val h = it.headers.adapt()
//                            HttpContent.Multipart.dataItem(
//                                key = it.name ?: "",
//                                filename = it.originalFileName ?: "",
//                                headers = h,
//                                content = HttpContent.LazyStream(
//                                    { it.provider().toInputStream() },
//                                    h.contentLength,
//                                    it.contentType?.adapt()
//                                        ?: com.lightningkite.lightningserver.core.ContentType.Application.OctetStream
//                                )
//                            )
//                        }
//
//                        is PartData.BinaryItem -> {
//                            val h = it.headers.adapt()
//                            HttpContent.Multipart.dataItem(
//                                key = it.name ?: "",
//                                filename = "",
//                                headers = h,
//                                content = HttpContent.LazyStream(
//                                    { it.provider().asStream() },
//                                    h.contentLength,
//                                    it.contentType?.adapt()
//                                        ?: com.lightningkite.lightningserver.core.ContentType.Application.OctetStream
//                                )
//                            )
//                        }
//
//                        is PartData.BinaryChannelItem -> TODO()
//                    }
//                )
//        }
//    }
//}

/*
 * TODO: API Recommendations
 *
 * 1. Complete the MultiPart support implementation or remove the commented code
 * 2. The Headers.adapt() function splits comma-separated values, but some headers
 *    (like Set-Cookie) shouldn't be split this way. Consider header-specific handling.
 * 3. Consider adding error handling for invalid content types in adapt()
 * 4. The typo "MutliPart" appears in comments - should be "MultiPart"
 */
