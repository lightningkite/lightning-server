package com.lightningkite.ktorbatteries.typed

import com.lightningkite.ktorbatteries.routes.fullPath
import io.ktor.server.routing.*
import java.io.File
import kotlin.reflect.KClass

object SDK {
    fun test() {
        println(buildString {
            appendLine("interface Api {")
            ApiEndpoint.known.forEach { it ->
                append("fun ${it.functionName}(")
                append(it.arguments().joinToString(", "))
                append("): ")
                it.outputType?.let {
                    append("Single<$it>")
                } ?: append("Completable")
                appendLine()
            }
            ApiWebsocket.known.forEach {
                append("fun ${it.functionName}(")
                append(it.arguments().joinToString(", "))
                append("): ")
                append("Observable<${it.outputType}>")
                appendLine()
            }
            appendLine("}")
            appendLine()
        })
    }
}

fun ApiEndpoint<*, *, *>.arguments() = listOfNotNull(
    userType?.let {
        "${(it.classifier as? KClass<Any>)?.simpleName?.replaceFirstChar { it.lowercase() } ?: "session"}: String"
    }?.let(::listOf),
    generateSequence(route) { it.parent }.toList().reversed()
        .mapNotNull { it.selector as? PathSegmentParameterRouteSelector }
        .map {
            "${it.name}: ${routeTypes[it.name] ?: "String"}"
        },
    inputType?.let {
        "input: ${it}"
    }?.let(::listOf)
).flatten()

fun ApiWebsocket<*, *, *>.arguments() = listOfNotNull(
    userType?.let {
        "${(it.classifier as? KClass<Any>)?.simpleName?.replaceFirstChar { it.lowercase() } ?: "session"}: String"
    }?.let(::listOf),
    generateSequence(route) { it.parent }.toList().reversed()
        .mapNotNull { it.selector as? PathSegmentParameterRouteSelector }
        .map {
            "${it.name}: String"
        },
    inputType.let {
        "input: ${it}"
    }.let(::listOf)
).flatten()

/*

interface Api {
    interface SomeRoute {

    }
}

class LiveApi: Api {
    class SomeRoute: Api.SomeRoute {

    }
}

 */