package com.lightningkite.lightningserver.engine.ktor

import com.lightningkite.MediaType
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.RawPath
import com.lightningkite.lightningserver.runtime.ServerRuntimeBase
import com.lightningkite.services.data.TypedData
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.util.*
import kotlinx.io.asSource

internal fun ContentType.adapt(): MediaType =
    MediaType(type = contentType, subtype = contentSubtype, parameters = parameters.associate { it.name to it.value })

internal fun Headers.adapt(): HttpHeaders = HttpHeaders(entry = flattenEntries().flatMap {
    it.second.split(',').map { it.trim() }.map { s -> it.first to s }
}.toTypedArray())

context(server: ServerRuntimeBase)
internal suspend fun ApplicationCall.adapt(): HttpRequest<PathSpec> {
    return HttpRequest(
        path = RawPath(request.path()),
        queryParameters = request.queryParameters.flattenEntries(),
        headers = request.headers.adapt(),
        domain = request.origin.serverHost,
        protocol = request.origin.scheme,
        sourceIp = server.settings.get(ktorRunConfig, server).realIpHeader?.let {
            request.header(it)
                ?: throw Exception("Real IP address header for proxy '$it' was missing from the request.")
        } ?: request.origin.remoteAddress,
        method = HttpMethod(request.httpMethod.value),
        body = run {
            // MutliPart Support?
            val stream = receiveStream()

            TypedData.sink( request.contentType().adapt(), request.contentLength() ?: -1) {
                it.transferFrom(stream.asSource())
            }
        },
    )
}

// MutliPart Support?

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
