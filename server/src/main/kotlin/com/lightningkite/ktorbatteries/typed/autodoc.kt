package com.lightningkite.ktorbatteries.typed

import com.lightningkite.ktorbatteries.routes.*
import io.ktor.server.html.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import kotlinx.html.*
import kotlin.reflect.typeOf

@KtorDsl
fun Route.apiHelp(path: String = "docs") = route(path) {
    val rootRoute = this
    val routes = ApiEndpoint.known.associateBy { it.route.fullPath + "/" + it.route.selector.maybeMethod?.value }
    get {
        context.respondHtml {
            head { title("Index") }
            body {
                h1 { +"API Docs" }
                for(api in routes.entries.sortedBy { it.key }.map { it.value }) {
                    h2 {
                        +api.route.fullPath
                    }
                    div {
                        p { +api.summary }
                        p { +api.description }
                        p { +"Input type: ${api.inputType}" }
                        p { +"Output type: ${api.outputType}" }
                        p { +"User type: ${api.userType}" }
                    }
                }
            }
        }
    }
}
