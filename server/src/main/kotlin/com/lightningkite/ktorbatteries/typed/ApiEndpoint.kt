package com.lightningkite.ktorbatteries.typed

import com.lightningkite.ktorbatteries.exceptions.AuthenticationException
import com.lightningkite.ktorbatteries.exceptions.ForbiddenException
import com.lightningkite.ktorbatteries.routes.docName
import com.lightningkite.ktorbatteries.routes.fullPath
import com.lightningkite.ktorbatteries.serialization.PrimitiveBox
import com.lightningkite.ktorbatteries.serialization.Serialization
import com.lightningkite.ktordb.HasId
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.http.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import kotlinx.serialization.properties.decodeFromStringMap
import kotlin.reflect.KType
import kotlin.reflect.typeOf

data class ApiEndpoint<USER, INPUT : Any, OUTPUT>(
    override val route: Route,
    override val summary: String,
    override val description: String = summary,
    val errorCases: List<ErrorCase>,
    val routeTypes: Map<String, KType> = mapOf(),
    val inputType: KType? = null,
    val outputType: KType? = null,
    override val userType: KType? = null,
    val implementation: suspend (user: USER, input: INPUT, pathSegments: Map<String, List<String>>) -> OUTPUT
): Documentable {
    val authenticationRequired: Boolean get() = userType?.isMarkedNullable == false

    companion object {
        val known: MutableCollection<ApiEndpoint<*, *, *>> = ArrayList()
    }

    data class ErrorCase(val status: HttpStatusCode, val internalCode: Int, val description: String)
}

@kotlinx.serialization.Serializable
data class IdHolder<ID>(val id: ID)
inline fun <reified T: Comparable<T>> String.parseUrlPartOrBadRequest(): T = try {
    Serialization.properties.decodeFromStringMap<IdHolder<T>>(mapOf("id" to this)).id
} catch(e: Exception) {
    e.printStackTrace()
    throw BadRequestException("ID ${this} could not be parsed as a ${T::class.simpleName}.")
}

inline fun <reified USER> ApplicationCall.user(): USER {
    (authentication.principal as? USER)?.let { return it }
    return try {
        principal<BoxPrincipal<USER>>()?.user as USER
    } catch(e: Exception) {
        throw AuthenticationException()
    }
}
data class BoxPrincipal<USER>(val user: USER): Principal

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified USER, reified INPUT : Any, reified OUTPUT> Route.apiBase(
    path: String = "",
    method: HttpMethod,
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    routeTypes: Map<String, KType> = mapOf(),
    successCode: HttpStatusCode = HttpStatusCode.OK,
    crossinline parseInput: suspend ApplicationCall.() -> INPUT,
    noinline implementation: suspend (user: USER, input: INPUT, pathSegments: Map<String, List<String>>) -> OUTPUT
): Unit {
    val inputType = typeOf<INPUT>().takeUnless { it.classifier == Unit::class }
    val outputType = typeOf<OUTPUT>().takeUnless { it.classifier == Unit::class }
    val userType = typeOf<USER>().takeUnless { it.classifier == Unit::class }
    val route = route(path, method) {
        handle {
            val user = if (userType != null) context.user<USER>() else null
            if(userType != null && !userType.isMarkedNullable && user == null) throw AuthenticationException()
            val input = if (inputType != null) context.parseInput() else Unit as INPUT
            val result = implementation(user as USER, input, context.parameters.toMap())
            if (outputType == null) call.respond(HttpStatusCode.NoContent)
            else if (result == null) call.respond(HttpStatusCode.NotFound)
            else call.respond(successCode, PrimitiveBox(result))
        }
    }
    val doc = ApiEndpoint(
        route = route,
        summary = summary,
        description = description,
        errorCases = errorCases,
        routeTypes = routeTypes,
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
inline fun <reified USER, reified INPUT : Any, reified OUTPUT> Route.apiBody(
    path: String = "",
    method: HttpMethod,
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    routeTypes: Map<String, KType> = mapOf(),
    successCode: HttpStatusCode = HttpStatusCode.OK,
    noinline implementation: suspend (user: USER, input: INPUT, pathSegments: Map<String, List<String>>) -> OUTPUT
): Unit = apiBase(
    path = path,
    method = method,
    summary = summary,
    description = description,
    errorCases = errorCases,
    routeTypes = routeTypes,
    successCode = successCode,
    parseInput = { receive<PrimitiveBox<INPUT>>().value },
    implementation = implementation
)

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified USER, reified INPUT : Any, reified OUTPUT> Route.apiQuery(
    path: String = "",
    method: HttpMethod,
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    routeTypes: Map<String, KType> = mapOf(),
    successCode: HttpStatusCode = HttpStatusCode.OK,
    noinline implementation: suspend (user: USER, input: INPUT, pathSegments: Map<String, List<String>>) -> OUTPUT
): Unit = apiBase(
    path = path,
    method = method,
    summary = summary,
    description = description,
    errorCases = errorCases,
    routeTypes = routeTypes,
    successCode = successCode,
    parseInput = {
        Serialization.properties.decodeFromStringMap<INPUT>(
            request.queryParameters.toMap().mapValues { it.value.joinToString(",") })
    },
    implementation = implementation
)

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified USER, reified INPUT : Any, reified OUTPUT> Route.apiParameterlessBody(
    path: String = "",
    method: HttpMethod,
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    crossinline implementation: suspend (user: USER, input: INPUT) -> OUTPUT
) = apiBody<USER, INPUT, OUTPUT>(
    path = path,
    method = method,
    summary = summary,
    description = description,
    errorCases = errorCases,
    routeTypes = mapOf(),
    successCode = successCode
) { user, input, segments ->
    implementation(user, input)
}

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified USER, reified ROUTE: Comparable<ROUTE>, reified INPUT : Any, reified OUTPUT> Route.apiSingleParameterBody(
    postIdPath: String = "",
    method: HttpMethod,
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    crossinline implementation: suspend (user: USER, id: ROUTE, input: INPUT) -> OUTPUT
) = apiBody<USER, INPUT, OUTPUT>(
    path = "{id}" + if (postIdPath.isBlank()) "" else "/$postIdPath",
    method = method,
    summary = summary,
    description = description,
    errorCases = errorCases,
    routeTypes = mapOf("id" to typeOf<ROUTE>()),
    successCode = successCode
) { user, input, segments ->
    implementation(user, segments["id"]!!.first().parseUrlPartOrBadRequest<ROUTE>(), input)
}

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified USER, reified INPUT : Any, reified OUTPUT> Route.apiParameterlessQuery(
    path: String = "",
    method: HttpMethod,
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    crossinline implementation: suspend (user: USER, input: INPUT) -> OUTPUT
) = apiQuery<USER, INPUT, OUTPUT>(
    path = path,
    method = method,
    summary = summary,
    description = description,
    errorCases = errorCases,
    routeTypes = mapOf(),
    successCode = successCode
) { user, input, segments ->
    implementation(user, input)
}

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified USER, reified ROUTE: Comparable<ROUTE>, reified INPUT : Any, reified OUTPUT> Route.apiSingleParameterQuery(
    postIdPath: String = "",
    method: HttpMethod,
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    crossinline implementation: suspend (user: USER, id: ROUTE, input: INPUT) -> OUTPUT
) = apiQuery<USER, INPUT, OUTPUT>(
    path = "{id}" + if (postIdPath.isBlank()) "" else "/$postIdPath",
    method = method,
    summary = summary,
    description = description,
    errorCases = errorCases,
    routeTypes = mapOf("id" to typeOf<ROUTE>()),
    successCode = successCode
) { user, input, segments ->
    implementation(user, segments["id"]!!.first().parseUrlPartOrBadRequest<ROUTE>(), input)
}

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified INPUT : Any, reified OUTPUT> Route.apiParameterlessBody(
    path: String = "",
    method: HttpMethod,
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    crossinline implementation: suspend (input: INPUT) -> OUTPUT
) = apiBody<Unit?, INPUT, OUTPUT>(
    path = path,
    method = method,
    summary = summary,
    description = description,
    errorCases = errorCases,
    routeTypes = mapOf(),
    successCode = successCode
) { _, input, segments ->
    implementation(input)
}

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified ROUTE: Comparable<ROUTE>, reified INPUT : Any, reified OUTPUT> Route.apiSingleParameterBody(
    postIdPath: String = "",
    method: HttpMethod,
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    crossinline implementation: suspend (id: ROUTE, input: INPUT) -> OUTPUT
) = apiBody<Unit?, INPUT, OUTPUT>(
    path = "{id}" + if (postIdPath.isBlank()) "" else "/$postIdPath",
    method = method,
    summary = summary,
    description = description,
    errorCases = errorCases,
    routeTypes = mapOf("id" to typeOf<ROUTE>()),
    successCode = successCode
) { _, input, segments ->
    implementation(segments["id"]!!.first().parseUrlPartOrBadRequest<ROUTE>(), input)
}

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified INPUT : Any, reified OUTPUT> Route.apiParameterlessQuery(
    path: String = "",
    method: HttpMethod,
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    crossinline implementation: suspend (input: INPUT) -> OUTPUT
) = apiQuery<Unit?, INPUT, OUTPUT>(
    path = path,
    method = method,
    summary = summary,
    description = description,
    errorCases = errorCases,
    routeTypes = mapOf(),
    successCode = successCode
) { _, input, segments ->
    implementation(input)
}

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified ROUTE: Comparable<ROUTE>, reified INPUT : Any, reified OUTPUT> Route.apiSingleParameterQuery(
    postIdPath: String = "",
    method: HttpMethod,
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    crossinline implementation: suspend (id: ROUTE, input: INPUT) -> OUTPUT
) = apiQuery<Unit?, INPUT, OUTPUT>(
    path = "{id}" + if (postIdPath.isBlank()) "" else "/$postIdPath",
    method = method,
    summary = summary,
    description = description,
    errorCases = errorCases,
    routeTypes = mapOf("id" to typeOf<ROUTE>()),
    successCode = successCode
) { _, input, segments ->
    implementation(segments["id"]!!.first().parseUrlPartOrBadRequest<ROUTE>(), input)
}

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified USER, reified INPUT : Any, reified OUTPUT> Route.get(
    path: String = "",
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    crossinline implementation: suspend (user: USER, input: INPUT) -> OUTPUT
) = apiParameterlessQuery(
    path = path,
    method = HttpMethod.Get,
    summary = summary,
    description = description,
    errorCases = errorCases,
    successCode = successCode,
    implementation = implementation
)

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified USER, reified ROUTE: Comparable<ROUTE>, reified INPUT : Any, reified OUTPUT> Route.getItem(
    postIdPath: String = "",
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    crossinline implementation: suspend (user: USER, id: ROUTE, input: INPUT) -> OUTPUT
) = apiSingleParameterQuery(
    postIdPath = postIdPath,
    method = HttpMethod.Get,
    summary = summary,
    description = description,
    errorCases = errorCases,
    successCode = successCode,
    implementation = implementation
)

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified USER, reified INPUT : Any, reified OUTPUT> Route.post(
    path: String = "",
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    crossinline implementation: suspend (user: USER, input: INPUT) -> OUTPUT
) = apiParameterlessBody(
    path = path,
    method = HttpMethod.Post,
    summary = summary,
    description = description,
    errorCases = errorCases,
    successCode = successCode,
    implementation = implementation
)

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified USER, reified ROUTE: Comparable<ROUTE>, reified INPUT : Any, reified OUTPUT> Route.postItem(
    postIdPath: String = "",
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    crossinline implementation: suspend (user: USER, id: ROUTE, input: INPUT) -> OUTPUT
) = apiSingleParameterBody(
    postIdPath = postIdPath,
    method = HttpMethod.Post,
    summary = summary,
    description = description,
    errorCases = errorCases,
    successCode = successCode,
    implementation = implementation
)

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified USER, reified INPUT : Any, reified OUTPUT> Route.put(
    path: String = "",
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    crossinline implementation: suspend (user: USER, input: INPUT) -> OUTPUT
) = apiParameterlessBody(
    path = path,
    method = HttpMethod.Put,
    summary = summary,
    description = description,
    errorCases = errorCases,
    successCode = successCode,
    implementation = implementation
)

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified USER, reified ROUTE: Comparable<ROUTE>, reified INPUT : Any, reified OUTPUT> Route.putItem(
    postIdPath: String = "",
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    crossinline implementation: suspend (user: USER, id: ROUTE, input: INPUT) -> OUTPUT
) = apiSingleParameterBody(
    postIdPath = postIdPath,
    method = HttpMethod.Put,
    summary = summary,
    description = description,
    errorCases = errorCases,
    successCode = successCode,
    implementation = implementation
)

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified USER, reified INPUT : Any, reified OUTPUT> Route.patch(
    path: String = "",
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    crossinline implementation: suspend (user: USER, input: INPUT) -> OUTPUT
) = apiParameterlessBody(
    path = path,
    method = HttpMethod.Patch,
    summary = summary,
    description = description,
    errorCases = errorCases,
    successCode = successCode,
    implementation = implementation
)

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified USER, reified ROUTE: Comparable<ROUTE>, reified INPUT : Any, reified OUTPUT> Route.patchItem(
    postIdPath: String = "",
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    crossinline implementation: suspend (user: USER, id: ROUTE, input: INPUT) -> OUTPUT
) = apiSingleParameterBody(
    postIdPath = postIdPath,
    method = HttpMethod.Patch,
    summary = summary,
    description = description,
    errorCases = errorCases,
    successCode = successCode,
    implementation = implementation
)

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified USER, reified INPUT : Any, reified OUTPUT> Route.delete(
    path: String = "",
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    crossinline implementation: suspend (user: USER, input: INPUT) -> OUTPUT
) = apiParameterlessQuery(
    path = path,
    method = HttpMethod.Delete,
    summary = summary,
    description = description,
    errorCases = errorCases,
    successCode = successCode,
    implementation = implementation
)

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified USER, reified ROUTE: Comparable<ROUTE>, reified INPUT : Any, reified OUTPUT> Route.deleteItem(
    path: String = "",
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    crossinline implementation: suspend (user: USER, id: ROUTE, input: INPUT) -> OUTPUT
) = apiSingleParameterQuery(
    postIdPath = path,
    method = HttpMethod.Delete,
    summary = summary,
    description = description,
    errorCases = errorCases,
    successCode = successCode,
    implementation = implementation
)



/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified INPUT : Any, reified OUTPUT> Route.get(
    path: String = "",
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    crossinline implementation: suspend (input: INPUT) -> OUTPUT
) = apiParameterlessQuery(
    path = path,
    method = HttpMethod.Get,
    summary = summary,
    description = description,
    errorCases = errorCases,
    successCode = successCode,
    implementation = implementation
)

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified ROUTE: Comparable<ROUTE>, reified INPUT : Any, reified OUTPUT> Route.getItem(
    postIdPath: String = "",
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    crossinline implementation: suspend (id: ROUTE, input: INPUT) -> OUTPUT
) = apiSingleParameterQuery<ROUTE, INPUT, OUTPUT>(
    postIdPath = postIdPath,
    method = HttpMethod.Get,
    summary = summary,
    description = description,
    errorCases = errorCases,
    successCode = successCode,
    implementation = { a, b -> implementation(a, b) }
)

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified INPUT : Any, reified OUTPUT> Route.post(
    path: String = "",
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    crossinline implementation: suspend (input: INPUT) -> OUTPUT
) = apiParameterlessBody(
    path = path,
    method = HttpMethod.Post,
    summary = summary,
    description = description,
    errorCases = errorCases,
    successCode = successCode,
    implementation = implementation
)

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified ROUTE: Comparable<ROUTE>, reified INPUT : Any, reified OUTPUT> Route.postItem(
    postIdPath: String = "",
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    crossinline implementation: suspend (id: ROUTE, input: INPUT) -> OUTPUT
) = apiSingleParameterBody<ROUTE, INPUT, OUTPUT>(
    postIdPath = postIdPath,
    method = HttpMethod.Post,
    summary = summary,
    description = description,
    errorCases = errorCases,
    successCode = successCode,
    implementation = { a, b -> implementation(a, b) }
)

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified INPUT : Any, reified OUTPUT> Route.put(
    path: String = "",
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    crossinline implementation: suspend (input: INPUT) -> OUTPUT
) = apiParameterlessBody(
    path = path,
    method = HttpMethod.Put,
    summary = summary,
    description = description,
    errorCases = errorCases,
    successCode = successCode,
    implementation = implementation
)

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified ROUTE: Comparable<ROUTE>, reified INPUT : Any, reified OUTPUT> Route.putItem(
    postIdPath: String = "",
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    crossinline implementation: suspend (id: ROUTE, input: INPUT) -> OUTPUT
) = apiSingleParameterBody<ROUTE, INPUT, OUTPUT>(
    postIdPath = postIdPath,
    method = HttpMethod.Put,
    summary = summary,
    description = description,
    errorCases = errorCases,
    successCode = successCode,
    implementation = { a, b -> implementation(a, b) }
)

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified INPUT : Any, reified OUTPUT> Route.patch(
    path: String = "",
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    crossinline implementation: suspend (input: INPUT) -> OUTPUT
) = apiParameterlessBody(
    path = path,
    method = HttpMethod.Patch,
    summary = summary,
    description = description,
    errorCases = errorCases,
    successCode = successCode,
    implementation = implementation
)

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified ROUTE: Comparable<ROUTE>, reified INPUT : Any, reified OUTPUT> Route.patchItem(
    postIdPath: String = "",
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    crossinline implementation: suspend (id: ROUTE, input: INPUT) -> OUTPUT
) = apiSingleParameterBody<ROUTE, INPUT, OUTPUT>(
    postIdPath = postIdPath,
    method = HttpMethod.Patch,
    summary = summary,
    description = description,
    errorCases = errorCases,
    successCode = successCode,
    implementation = { a, b -> implementation(a, b) }
)

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified INPUT : Any, reified OUTPUT> Route.delete(
    path: String = "",
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    crossinline implementation: suspend (input: INPUT) -> OUTPUT
) = apiParameterlessQuery(
    path = path,
    method = HttpMethod.Delete,
    summary = summary,
    description = description,
    errorCases = errorCases,
    successCode = successCode,
    implementation = implementation
)

/**
 * Builds a route to match `GET` requests
 */
@KtorDsl
inline fun <reified ROUTE: Comparable<ROUTE>, reified INPUT : Any, reified OUTPUT> Route.deleteItem(
    path: String = "",
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    crossinline implementation: suspend (id: ROUTE, input: INPUT) -> OUTPUT
) = apiSingleParameterQuery<ROUTE, INPUT, OUTPUT>(
    postIdPath = path,
    method = HttpMethod.Delete,
    summary = summary,
    description = description,
    errorCases = errorCases,
    successCode = successCode,
    implementation = { a, b -> implementation(a, b) }
)
