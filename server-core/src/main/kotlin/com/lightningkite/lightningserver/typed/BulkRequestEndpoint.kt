package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.HttpResponseException
import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.exceptions.HttpStatusException
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.builtins.ListSerializer

fun ServerPath.bulkRequestEndpoint() = post.api(
    summary = "Bulk Request",
    description = "Performs multiple requests at once, returning the results in the same order.",
    authOptions = noAuth,
    implementation = { requests: Map<String, BulkRequest> ->
        coroutineScope {
            requests.entries.map { entry ->
                async {
                    val it = entry.value
                    val handler = Http.matcher.match(it.path, HttpMethod(it.method))
                        ?: return@async entry.key to BulkResponse(
                            error = LSError(404, detail = "no-match", message = "No matching route found", data = it.method + " " + it.path)
                        )
                    @Suppress("UNCHECKED_CAST")
                    val api = Http.endpoints[handler.endpoint] as? ApiEndpoint<HasId<*>?, TypedServerPath, Any?, Any?> ?: return@async entry.key to BulkResponse(
                        error = LSError(400, detail = "not-api", message = "Matched route is not an API endpoint", data = it.method + " " + it.path)
                    )
                    try {
                        val result = api.implementation(api.authAndPathParts(
                            authOrNull, HttpRequest(
                                endpoint = handler.endpoint,
                                parts = handler.parts,
                                wildcard = handler.wildcard,
                                body = it.body?.let { HttpContent.Text(it, ContentType.Application.Json) },
                                queryParameters = listOf(),
                                headers = rawRequest?.headers ?: HttpHeaders.EMPTY,
                                domain = rawRequest?.domain ?: "",
                                protocol = rawRequest?.protocol ?: "https",
                                sourceIp = rawRequest?.sourceIp ?: "0.0.0.0"
                            )
                        ), it.body?.let { Serialization.json.decodeFromString(api.inputType, it) } ?: Unit)
                        entry.key to BulkResponse(
                            result = Serialization.json.encodeToString(api.outputType, result)
                        )
                    } catch(e: Exception) {
                        entry.key to BulkResponse(
                            error = when(e) {
                                is HttpStatusException -> e.toLSError()
                                else -> LSError(500, "unknown", "An unknown server error occurred.")
                            }
                        )
                    }
                }
            }.awaitAll().associate { it }
        }
    }
)