package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.core.LightningServerDsl
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serialization.parse
import com.lightningkite.lightningserver.serialization.queryParameters
import com.lightningkite.lightningserver.serialization.toHttpContent
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.serializer
import java.net.URLDecoder

interface ApiEndpoint<INPUT : Any, OUTPUT> : Documentable, (suspend (HttpRequest) -> HttpResponse) {
    val route: HttpEndpoint
    override val authOptions: AuthOptions
    val inputType: KSerializer<INPUT>
    val outputType: KSerializer<OUTPUT>
    override val summary: String
    override val description: String
    val successCode: HttpStatus
    val errorCases: List<LSError>
    val routeTypes: Map<String, KSerializer<*>>
    val examples: List<ApiExample<INPUT, OUTPUT>>

    override val path: ServerPath
        get() = route.path

    suspend fun invokeAny(auth: RequestAuth<*>, input: INPUT, routes: Map<String, Any?>): OUTPUT
}

data class ApiExample<INPUT, OUTPUT>(
    val input: INPUT,
    val output: OUTPUT,
    val name: String = "Example",
    val notes: String? = null,
)


inline fun <reified T : Comparable<T>> String.parseUrlPartOrBadRequest(): T = parseUrlPartOrBadRequest(
    Serialization.module.serializer()
)

fun <T> String.parseUrlPartOrBadRequest(serializer: KSerializer<T>): T = try {
    Serialization.fromString(URLDecoder.decode(this, Charsets.UTF_8), serializer)
} catch (e: Exception) {
    throw BadRequestException("ID ${this} could not be parsed as a ${serializer.descriptor.serialName}.")
}

private fun String.parseUrlPartOrBadRequestUntyped(serializer: KSerializer<*>): Any? = try {
    Serialization.fromString(URLDecoder.decode(this, Charsets.UTF_8), serializer)
} catch (e: Exception) {
    throw BadRequestException("ID ${this} could not be parsed as a ${serializer.descriptor.serialName}.")
}

