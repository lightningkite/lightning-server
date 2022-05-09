package com.lightningkite.ktorbatteries.typed

import com.lightningkite.ktorbatteries.serialization.Serialization
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.reflect.KType
import kotlin.reflect.typeOf

data class ApiWebsocket<USER : Principal, INPUT, OUTPUT>(
    val route: Route,
    val summary: String,
    val description: String = summary,
    val errorCases: List<ErrorCase>,
    val inputType: KType,
    val outputType: KType,
    val userType: KType? = null,
    val implementation: suspend Session<INPUT, OUTPUT>.(user: USER?)->Unit
) {
    class Session<INPUT, OUTPUT>(
        val send: suspend (OUTPUT) -> Unit,
        val incoming: Flow<INPUT>
    )

    class UntypedSession(
        val send: suspend (String) -> Unit,
        val incoming: Flow<String>
    )

    companion object {
        val known: MutableCollection<ApiWebsocket<*, *, *>> = ArrayList()
    }

    data class ErrorCase(val closeReason: CloseReason, val internalCode: Int, val description: String)
}

inline fun <reified USER : Principal, reified INPUT, reified OUTPUT> Route.apiWebsocket(
    path: String = "",
    summary: String,
    description: String = summary,
    errorCases: List<ApiWebsocket.ErrorCase>,
    noinline implementation: suspend ApiWebsocket.Session<INPUT, OUTPUT>.(user: USER?) -> Unit
) {
    val inputType = typeOf<INPUT>()
    val outputType = typeOf<OUTPUT>()
    val userType = typeOf<USER>().takeUnless { it.classifier == Unit::class }
    ApiWebsocket.known.add(
        ApiWebsocket<USER, INPUT, OUTPUT>(
        route = this,
        summary = summary,
        description = description,
        errorCases = errorCases,
        inputType = inputType,
        outputType = outputType,
        userType = userType,
        implementation = implementation
    )
    )
    webSocket(path = path) {
        implementation(
            ApiWebsocket.Session(
                send = { send(Serialization.json.encodeToString<OUTPUT>(it)) },
                incoming = incoming.consumeAsFlow()
                    .mapNotNull { it as? Frame.Text }
                    .mapNotNull {
                        val text = it.readText()
                        if (text == "") {
                            send("")
                            null
                        } else {
                            Serialization.json.decodeFromString(text)
                        }
                    }
            ), this.call.principal())
    }
}
