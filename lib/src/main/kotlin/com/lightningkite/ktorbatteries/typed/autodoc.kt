package com.lightningkite.ktorbatteries.typed

import com.lightningkite.ktorbatteries.routes.*
import io.ktor.server.html.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.html.*
import kotlin.reflect.typeOf

fun Route.helpFor(api: ApiEndpoint<*, *, *>) = get {
    if (api.inputType == null) {
        context.respond(HttpStatusCode.NoContent)
        return@get
    }
    this.context.respondHtml {
        head {
            includeFormScript()
        }
        body {
            div {
                script {
                    unsafe {
                        raw(
                            """
                        async function submit() {
                            const r = await fetch("${api.route.fullPath}", { method: '${api.route.selector.maybeMethod?.value}', body: editor.getValue() })
                            const asJson = await r.json()
                            result.setValue(asJson)
                        }
                    """.trimIndent()
                        )
                    }
                }
                h1 {
                    +api.route.fullPath
                }
                div {
                    p { +api.summary }
                    p { +api.description }
                    p { +"Input type: ${api.inputType}" }
                    p { +"Output type: ${api.outputType}" }
                    p { +"User type: ${api.userType}" }
                }
                div {
                    jsFormUntyped("Input", "editor", api.inputType)
                    button(type = ButtonType.button, classes = "btn btn-primary") {
                        onClick = "submit()"
                        +"Submit"
                    }
                    displayUntyped("Output", "result", api.outputType ?: typeOf<String>(), collapsed = true)
                }
            }
            nav {
                apiHelpIndex()
            }
        }
    }
}

fun Route.apiHelp() {
    val routes = ApiEndpoint.known.associateBy { it.route.fullPath + "/" + it.route.selector.maybeMethod?.value }
    for (r in routes) {
        route(r.key) { helpFor(r.value) }.also { println(it.fullPath) }
    }
    get {
        context.respondHtml {
            head { title("Index") }
            body {
                h1 { +"API Docs Index" }
                apiHelpIndex()
            }
        }
    }
}

fun FlowContent.apiHelpIndex() {
    val routes = ApiEndpoint.known.associateBy { it.route.fullPath + "/" + it.route.selector.maybeMethod?.value }
    for (r in routes) {
        div {
            a(href = r.key) {
                +r.value.route.fullPath
                +" - "
                +r.value.summary
            }
        }
    }
}