package com.lightningkite.ktorbatteries.typed

import com.lightningkite.ktorbatteries.serialization.Serialization
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import kotlinx.serialization.properties.decodeFromStringMap
import kotlin.reflect.KType
import kotlin.reflect.typeOf

data class ApiEndpoint<USER : Principal, INPUT : Any, OUTPUT>(
    val route: Route,
    val summary: String,
    val description: String = summary,
    val errorCases: List<ErrorCase>,
    val inputType: KType? = null,
    val outputType: KType? = null,
    val userType: KType? = null,
    val implementation: suspend (user: USER?, input: INPUT, pathSegments: Map<String, List<String>>) -> OUTPUT
) {
    companion object {
        val known: MutableCollection<ApiEndpoint<*, *, *>> = ArrayList()
    }

    data class ErrorCase(val status: HttpStatusCode, val internalCode: Int, val description: String)
}

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified USER : Principal, reified INPUT : Any, reified OUTPUT> Route.apiBase(
    path: String,
    method: HttpMethod,
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    crossinline parseInput: suspend ApplicationCall.() -> INPUT,
    noinline implementation: suspend (user: USER?, input: INPUT, pathSegments: Map<String, List<String>>) -> OUTPUT
): Unit {
    val inputType = typeOf<INPUT>().takeUnless { it.classifier == Unit::class }
    val outputType = typeOf<OUTPUT>().takeUnless { it.classifier == Unit::class }
    val userType = typeOf<USER>().takeUnless { it.classifier == Unit::class }
    val route = route(path, method) {
        handle {
            val user = if (userType != null) context.principal<USER>() else null
            val input = if (inputType != null) context.parseInput() else Unit as INPUT
            val result = implementation(user, input, context.parameters.toMap())
            if (outputType == null) call.respond(HttpStatusCode.NoContent)
            else if (result == null) call.respond(HttpStatusCode.NotFound)
            else call.respond(successCode, result)
        }
    }
    val doc = ApiEndpoint(
        route = route,
        summary = summary,
        description = description,
        errorCases = errorCases,
        inputType = inputType,
        outputType = outputType,
        userType = userType,
        implementation = implementation
    )
    ApiEndpoint.known += doc
}

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified USER : Principal, reified INPUT : Any, reified OUTPUT> Route.apiBody(
    path: String,
    method: HttpMethod,
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    noinline implementation: suspend (user: USER?, input: INPUT, pathSegments: Map<String, List<String>>) -> OUTPUT
): Unit = apiBase(path, method, summary, description, errorCases, successCode, { receive() }, implementation)

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified USER : Principal, reified INPUT : Any, reified OUTPUT> Route.apiQuery(
    path: String,
    method: HttpMethod,
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    noinline implementation: suspend (user: USER?, input: INPUT, pathSegments: Map<String, List<String>>) -> OUTPUT
): Unit = apiBase(
    path,
    method,
    summary,
    description,
    errorCases,
    successCode,
    {
        Serialization.properties.decodeFromStringMap<INPUT>(
            request.queryParameters.toMap().mapValues { it.value.joinToString(",") })
    },
    implementation
)

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified USER : Principal, reified INPUT : Any, reified OUTPUT> Route.apiParameterlessBody(
    path: String,
    method: HttpMethod,
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    crossinline implementation: suspend (user: USER?, input: INPUT) -> OUTPUT
) = apiBody<USER, INPUT, OUTPUT>(path, method, summary, description, errorCases, successCode) { user, input, segments ->
    implementation(user, input)
}

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified USER : Principal, reified INPUT : Any, reified OUTPUT> Route.apiSingleParameterBody(
    path: String,
    method: HttpMethod,
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    crossinline implementation: suspend (user: USER?, id: String, input: INPUT) -> OUTPUT
) = apiBody<USER, INPUT, OUTPUT>(
    path.removeSuffix("/") + "/{id}",
    method,
    summary,
    description,
    errorCases,
    successCode
) { user, input, segments ->
    implementation(user, segments["id"]!!.first(), input)
}

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified USER : Principal, reified INPUT : Any, reified OUTPUT> Route.apiParameterlessQuery(
    path: String,
    method: HttpMethod,
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    crossinline implementation: suspend (user: USER?, input: INPUT) -> OUTPUT
) = apiQuery<USER, INPUT, OUTPUT>(
    path,
    method,
    summary,
    description,
    errorCases,
    successCode
) { user, input, segments ->
    implementation(user, input)
}

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified USER : Principal, reified INPUT : Any, reified OUTPUT> Route.apiSingleParameterQuery(
    path: String,
    method: HttpMethod,
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    crossinline implementation: suspend (user: USER?, id: String, input: INPUT) -> OUTPUT
) = apiQuery<USER, INPUT, OUTPUT>(
    path.removeSuffix("/") + "/{id}",
    method,
    summary,
    description,
    errorCases,
    successCode
) { user, input, segments ->
    implementation(user, segments["id"]!!.first(), input)
}

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified USER : Principal, reified INPUT : Any, reified OUTPUT> Route.get(
    path: String,
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    crossinline implementation: suspend (user: USER?, input: INPUT) -> OUTPUT
) = apiParameterlessQuery(path, HttpMethod.Get, summary, description, errorCases, successCode, implementation)

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified USER : Principal, reified INPUT : Any, reified OUTPUT> Route.getItem(
    path: String,
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    crossinline implementation: suspend (user: USER?, id: String, input: INPUT) -> OUTPUT
) = apiSingleParameterQuery(path, HttpMethod.Get, summary, description, errorCases, successCode, implementation)

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified USER : Principal, reified INPUT : Any, reified OUTPUT> Route.post(
    path: String,
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    crossinline implementation: suspend (user: USER?, input: INPUT) -> OUTPUT
) = apiParameterlessBody(path, HttpMethod.Post, summary, description, errorCases, successCode, implementation)

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified USER : Principal, reified INPUT : Any, reified OUTPUT> Route.postItem(
    path: String,
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    crossinline implementation: suspend (user: USER?, id: String, input: INPUT) -> OUTPUT
) = apiSingleParameterBody(path, HttpMethod.Post, summary, description, errorCases, successCode, implementation)

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified USER : Principal, reified INPUT : Any, reified OUTPUT> Route.put(
    path: String,
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    crossinline implementation: suspend (user: USER?, input: INPUT) -> OUTPUT
) = apiParameterlessBody(path, HttpMethod.Put, summary, description, errorCases, successCode, implementation)

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified USER : Principal, reified INPUT : Any, reified OUTPUT> Route.putItem(
    path: String,
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    crossinline implementation: suspend (user: USER?, id: String, input: INPUT) -> OUTPUT
) = apiSingleParameterBody(path, HttpMethod.Put, summary, description, errorCases, successCode, implementation)

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified USER : Principal, reified INPUT : Any, reified OUTPUT> Route.patch(
    path: String,
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    crossinline implementation: suspend (user: USER?, input: INPUT) -> OUTPUT
) = apiParameterlessBody(path, HttpMethod.Patch, summary, description, errorCases, successCode, implementation)

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified USER : Principal, reified INPUT : Any, reified OUTPUT> Route.patchItem(
    path: String,
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    crossinline implementation: suspend (user: USER?, id: String, input: INPUT) -> OUTPUT
) = apiSingleParameterBody(path, HttpMethod.Patch, summary, description, errorCases, successCode, implementation)

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified USER : Principal, reified INPUT : Any, reified OUTPUT> Route.delete(
    path: String,
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    crossinline implementation: suspend (user: USER?, input: INPUT) -> OUTPUT
) = apiParameterlessQuery(path, HttpMethod.Delete, summary, description, errorCases, successCode, implementation)

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified USER : Principal, reified INPUT : Any, reified OUTPUT> Route.deleteItem(
    path: String,
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    crossinline implementation: suspend (user: USER?, id: String, input: INPUT) -> OUTPUT
) = apiSingleParameterQuery(path, HttpMethod.Delete, summary, description, errorCases, successCode, implementation)
