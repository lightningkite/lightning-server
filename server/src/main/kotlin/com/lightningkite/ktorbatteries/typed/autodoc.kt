package com.lightningkite.ktorbatteries.typed

import com.lightningkite.ktorbatteries.routes.*
import io.ktor.server.html.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import kotlinx.html.*
import kotlin.reflect.typeOf

private fun Route.helpFor(root: Route, api: ApiEndpoint<*, *, *>) = get {
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
//                apiHelpIndex()
            }
        }
    }
}

@KtorDsl
fun Route.apiHelp(path: String = "docs") = route(path) {
    val rootRoute = this
    val routes = ApiEndpoint.known.associateBy { it.route.fullPath + "/" + it.route.selector.maybeMethod?.value }
    for (r in routes) {
        route(r.key) { helpFor(rootRoute, r.value) }
    }
    get {
        context.respondHtml {
            head { title("Index") }
            body {
                h1 { +"API Docs Index" }
                apiHelpIndex(path)
            }
        }
    }
}

private fun FlowContent.apiHelpIndex(prefix: String) {
    val routes = ApiEndpoint.known.associateBy { it.route.fullPath + "/" + it.route.selector.maybeMethod?.value }
    for (r in routes) {
        div {
            a(href = prefix + r.key) {
                +r.value.route.fullPath
                +" - "
                +r.value.summary
            }
        }
    }
}