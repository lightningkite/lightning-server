package com.lightningkite.ktorbatteries.typed

import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.pipeline.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlinx.serialization.properties.Properties
import kotlinx.serialization.serializer

// Contextual objects:
// Subject - The objected pointed at via the path
// Principal - The current user
// Input - The data posted - GET = params, other = body
//
//fun setup() {
//    subject("asdfsad/{asdf}/asdfsad") {
//        getSubject { ... }
//
//        post { user, subject, input: Something ->
//
//        }
//        post(Subject::someFunction)
//    }
//}
//
//fun Subject.someFunction(user: User, newSubject: Subject): Int {
//
//}

/**
 * DslMarker for subject endpoints
 */
@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPEALIAS, AnnotationTarget.TYPE, AnnotationTarget.FUNCTION)
public annotation class ApiEndpointDsl

class APIEndpoint<USER : Principal, SUBJECT, IN, OUT>(
    val subject: SubjectRoute<*, *>,
    val route: Route,
    val inputType: KType?,
    val outputType: KType,
    val inputMethod: InputMethod,
    val inputFunction: (suspend SUBJECT.(USER?, IN) -> OUT)? = null,
    val noInputFunction: (suspend SUBJECT.(USER?) -> OUT)? = null
)
enum class InputMethod {
    None,
    Parameters,
    Body
}

class SubjectRoute<USER: Principal, SUBJECT>(
    val route: Route,
    val userType: KType,
    val subjectType: KType,
    val getSubject: suspend ApplicationCall.(USER?) -> SUBJECT
) {
    val children = ArrayList<APIEndpoint<USER, SUBJECT, *, *>>()
}

object API {
    val subjects: MutableList<SubjectRoute<*, *>> = ArrayList()
}

@ApiEndpointDsl
inline fun <reified USER: Principal, reified SUBJECT> Route.subject(path: String, noinline getSubject: suspend ApplicationCall.(USER?) -> SUBJECT): SubjectRoute<USER, SUBJECT> {
    return SubjectRoute(route(path){}, typeOf<USER>(), typeOf<SUBJECT>(), getSubject).also { API.subjects.add(it) }
}

@ApiEndpointDsl
inline fun <reified USER : Principal, reified SUBJECT, reified IN : Any, reified OUT : Any> SubjectRoute<USER, SUBJECT>.bodyEndpoint(
    method: HttpMethod = HttpMethod.Post,
    successStatus: HttpStatusCode = HttpStatusCode.OK,
    noinline forFunction: suspend SUBJECT.(USER?, IN) -> OUT
): Route = route.method(method) {
    handle {
        val user = call.principal<USER>()
        call.respond(successStatus, forFunction(getSubject(call, user), user, call.receive()))
    }
}.also {
    children += APIEndpoint(
        subject = this,
        route = it,
        inputType = typeOf<IN>(),
        outputType = typeOf<OUT>(),
        inputMethod = InputMethod.Body,
        inputFunction = forFunction
    )
}

@ApiEndpointDsl
@OptIn(ExperimentalSerializationApi::class)
inline fun <reified USER : Principal, reified SUBJECT, reified IN : Any, reified OUT : Any> SubjectRoute<USER, SUBJECT>.paramsEndpoint(
    method: HttpMethod = HttpMethod.Get,
    successStatus: HttpStatusCode = HttpStatusCode.OK,
    noinline forFunction: suspend SUBJECT.(USER?, IN) -> OUT
): Route = route.method(method) {
    handle {
        val user = call.principal<USER>()
        call.respond(
            successStatus,
            forFunction(
                getSubject(call, user),
                user,
                Properties.decodeFromStringMap(
                    Properties.serializersModule.serializer(),
                    call.parameters.entries().associate { it.key to it.value[0] })
            )
        )
    }
}.also {
    children += APIEndpoint(
        subject = this,
        route = it,
        inputType = typeOf<IN>(),
        outputType = typeOf<OUT>(),
        inputMethod = InputMethod.Parameters,
        inputFunction = forFunction
    )
}

@ApiEndpointDsl
@OptIn(ExperimentalSerializationApi::class)
inline fun <reified USER : Principal, reified SUBJECT, reified OUT : Any> SubjectRoute<USER, SUBJECT>.noInputEndpoint(
    method: HttpMethod = HttpMethod.Get,
    successStatus: HttpStatusCode = HttpStatusCode.OK,
    noinline forFunction: suspend SUBJECT.(USER?) -> OUT
): Route = route.method(method) {
    handle {
        val user = call.principal<USER>()
        call.respond(
            successStatus,
            forFunction(
                getSubject(call, user),
                user
            )
        )
    }
}.also {
    children += APIEndpoint<USER, SUBJECT, Unit, OUT>(
        subject = this,
        route = it,
        inputType = null,
        outputType = typeOf<OUT>(),
        inputMethod = InputMethod.Body,
        noInputFunction = forFunction
    )
}

@ApiEndpointDsl
inline fun <reified USER : Principal, reified SUBJECT, reified OUT : Any> SubjectRoute<USER, SUBJECT>.get(
    successStatus: HttpStatusCode = HttpStatusCode.OK,
    noinline forFunction: suspend SUBJECT.(USER?) -> OUT
) = noInputEndpoint(method = HttpMethod.Get, successStatus = successStatus, forFunction = forFunction)

@ApiEndpointDsl
inline fun <reified USER : Principal, reified SUBJECT, reified IN : Any, reified OUT : Any> SubjectRoute<USER, SUBJECT>.get(
    successStatus: HttpStatusCode = HttpStatusCode.OK,
    noinline forFunction: suspend SUBJECT.(USER?, IN) -> OUT
) = paramsEndpoint(method = HttpMethod.Get, successStatus = successStatus, forFunction = forFunction)

@ApiEndpointDsl
inline fun <reified USER : Principal, reified SUBJECT, reified OUT : Any> SubjectRoute<USER, SUBJECT>.delete(
    successStatus: HttpStatusCode = HttpStatusCode.OK,
    noinline forFunction: suspend SUBJECT.(USER?) -> OUT
) = noInputEndpoint(method = HttpMethod.Delete, successStatus = successStatus, forFunction = forFunction)

@ApiEndpointDsl
inline fun <reified USER : Principal, reified SUBJECT, reified IN : Any, reified OUT : Any> SubjectRoute<USER, SUBJECT>.deleteParams(
    successStatus: HttpStatusCode = HttpStatusCode.OK,
    noinline forFunction: suspend SUBJECT.(USER?, IN) -> OUT
) = paramsEndpoint(method = HttpMethod.Delete, successStatus = successStatus, forFunction = forFunction)

@ApiEndpointDsl
inline fun <reified USER : Principal, reified SUBJECT, reified IN : Any, reified OUT : Any> SubjectRoute<USER, SUBJECT>.post(
    successStatus: HttpStatusCode = HttpStatusCode.OK,
    noinline forFunction: suspend SUBJECT.(USER?, IN) -> OUT
) = bodyEndpoint(method = HttpMethod.Post, successStatus = successStatus, forFunction = forFunction)

@ApiEndpointDsl
inline fun <reified USER : Principal, reified SUBJECT, reified IN : Any, reified OUT : Any> SubjectRoute<USER, SUBJECT>.put(
    successStatus: HttpStatusCode = HttpStatusCode.OK,
    noinline forFunction: suspend SUBJECT.(USER?, IN) -> OUT
) = bodyEndpoint(method = HttpMethod.Put, successStatus = successStatus, forFunction = forFunction)

@ApiEndpointDsl
inline fun <reified USER : Principal, reified SUBJECT, reified IN : Any, reified OUT : Any> SubjectRoute<USER, SUBJECT>.patch(
    successStatus: HttpStatusCode = HttpStatusCode.OK,
    noinline forFunction: suspend SUBJECT.(USER?, IN) -> OUT
) = bodyEndpoint(method = HttpMethod.Patch, successStatus = successStatus, forFunction = forFunction)

@ApiEndpointDsl
inline fun <reified USER : Principal, reified SUBJECT, reified IN : Any, reified OUT : Any> SubjectRoute<USER, SUBJECT>.deleteBody(
    successStatus: HttpStatusCode = HttpStatusCode.OK,
    noinline forFunction: suspend SUBJECT.(USER?, IN) -> OUT
) = bodyEndpoint(method = HttpMethod.Delete, successStatus = successStatus, forFunction = forFunction)

@ApiEndpointDsl
inline fun <reified USER : Principal, reified SUBJECT, reified OUT : Any> SubjectRoute<USER, SUBJECT>.get(
    path: String,
    successStatus: HttpStatusCode = HttpStatusCode.OK,
    noinline forFunction: suspend SUBJECT.(USER?) -> OUT
) = route.route(path) {
    noInputEndpoint(
        method = HttpMethod.Get,
        successStatus = successStatus,
        forFunction = forFunction
    )
}

@ApiEndpointDsl
inline fun <reified USER : Principal, reified SUBJECT, reified IN : Any, reified OUT : Any> SubjectRoute<USER, SUBJECT>.get(
    path: String,
    successStatus: HttpStatusCode = HttpStatusCode.OK,
    noinline forFunction: suspend SUBJECT.(USER?, IN) -> OUT
) = route.route(path) {
    paramsEndpoint(
        method = HttpMethod.Get,
        successStatus = successStatus,
        forFunction = forFunction
    )
}

@ApiEndpointDsl
inline fun <reified USER : Principal, reified SUBJECT, reified OUT : Any> SubjectRoute<USER, SUBJECT>.delete(
    path: String,
    successStatus: HttpStatusCode = HttpStatusCode.OK,
    noinline forFunction: suspend SUBJECT.(USER?) -> OUT
) = route.route(path) {
    noInputEndpoint(
        method = HttpMethod.Delete,
        successStatus = successStatus,
        forFunction = forFunction
    )
}

@ApiEndpointDsl
inline fun <reified USER : Principal, reified SUBJECT, reified IN : Any, reified OUT : Any> SubjectRoute<USER, SUBJECT>.deleteParams(
    path: String,
    successStatus: HttpStatusCode = HttpStatusCode.OK,
    noinline forFunction: suspend SUBJECT.(USER?, IN) -> OUT
) = route.route(path) {
    paramsEndpoint(
        method = HttpMethod.Delete,
        successStatus = successStatus,
        forFunction = forFunction
    )
}

@ApiEndpointDsl
inline fun <reified USER : Principal, reified SUBJECT, reified IN : Any, reified OUT : Any> SubjectRoute<USER, SUBJECT>.post(
    path: String,
    successStatus: HttpStatusCode = HttpStatusCode.OK,
    noinline forFunction: suspend SUBJECT.(USER?, IN) -> OUT
) = route.route(path) { bodyEndpoint(method = HttpMethod.Post, successStatus = successStatus, forFunction = forFunction) }

@ApiEndpointDsl
inline fun <reified USER : Principal, reified SUBJECT, reified IN : Any, reified OUT : Any> SubjectRoute<USER, SUBJECT>.put(
    path: String,
    successStatus: HttpStatusCode = HttpStatusCode.OK,
    noinline forFunction: suspend SUBJECT.(USER?, IN) -> OUT
) = route.route(path) { bodyEndpoint(method = HttpMethod.Put, successStatus = successStatus, forFunction = forFunction) }

@ApiEndpointDsl
inline fun <reified USER : Principal, reified SUBJECT, reified IN : Any, reified OUT : Any> SubjectRoute<USER, SUBJECT>.patch(
    path: String,
    successStatus: HttpStatusCode = HttpStatusCode.OK,
    noinline forFunction: suspend SUBJECT.(USER?, IN) -> OUT
) = route.route(path) { bodyEndpoint(method = HttpMethod.Patch, successStatus = successStatus, forFunction = forFunction) }

@ApiEndpointDsl
inline fun <reified USER : Principal, reified SUBJECT, reified IN : Any, reified OUT : Any> SubjectRoute<USER, SUBJECT>.deleteBody(
    path: String,
    successStatus: HttpStatusCode = HttpStatusCode.OK,
    noinline forFunction: suspend SUBJECT.(USER?, IN) -> OUT
) = route.route(path) { bodyEndpoint(method = HttpMethod.Delete, successStatus = successStatus, forFunction = forFunction) }


/*

Leave it, add admin
    Manual HTML Form Serializer
    Generate JSON Schema, https://json-editor.github.io/json-editor/
Smart Routing

 */