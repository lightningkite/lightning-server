package com.lightningkite.ktorbatteries.db

import com.lightningkite.ktorbatteries.routes.pathRelativeTo
import com.lightningkite.ktorbatteries.serialization.Serialization
import com.lightningkite.ktorbatteries.typed.*
import com.lightningkite.ktordb.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.html.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import kotlinx.coroutines.flow.toList
import kotlinx.html.*
import kotlinx.serialization.properties.encodeToStringMap
import kotlin.reflect.KType
import kotlin.reflect.typeOf

data class AutoAdminSection<USER, T: HasId<*>>(
    val route: Route,
    val type: KType,
    val userType: KType,
    val defaultItem: (USER?) -> T,
    val getCollection: suspend (USER?) -> FieldCollection<T>
) {
    companion object {
        val known: MutableCollection<AutoAdminSection<*, *>> = ArrayList()
    }
}

@KtorDsl
fun Route.adminIndex(path: String = "admin") = route(path) {
    get {
        context.respondHtml {
            head {}
            body {
                div {
                    for(section in AutoAdminSection.known) {
                        div {
                            a(href = section.route.pathRelativeTo(this@route.parent!!)) {
                                +section.type.toString()
                            }
                        }
                    }
                }
            }
        }
    }
}

@KtorDsl
inline fun <reified USER, reified T : HasId<ID>, reified ID: Comparable<ID>> Route.adminPages(
    path: String = "",
    noinline defaultItem: (USER?) -> T,
    noinline getCollection: suspend (principal: USER?) -> FieldCollection<T>
) = route(path) {
    AutoAdminSection.known.add(
        AutoAdminSection(
        route = this,
        type = typeOf<T>(),
        userType = typeOf<USER>(),
        defaultItem = defaultItem,
        getCollection = getCollection,
    ))
    get("{id}") {
        val secured = getCollection(call.user<USER>())
        val item = secured.get(this.context.parameters["id"]!!.parseUrlPartOrBadRequest())
        context.respondHtml {
            head { includeFormScript() }
            body {
                form(method = FormMethod.post) {
                    id = "data-form"
                    insideHtmlForm(
                        title = T::class.simpleName ?: "Model",
                        jsEditorName = "editor",
                        defaultValue = item,
                        collapsed = false
                    )
                    button {
                        +"Save"
                    }
                    a(href = "${context.parameters["id"]!!}/delete") {
                        +"Delete"
                    }
                }
            }
        }
    }
    get("{id}/delete") {
        call.respondHtml {
            body {
                p { +"Are you sure?" }
                form {
                    button(formMethod = ButtonFormMethod.post) { +"Yes" }
                }
            }
        }
    }
    post("{id}/delete") {
        getCollection(call.user<USER>()).deleteOneById(this.context.parameters["id"]!!.parseUrlPartOrBadRequest())
        call.respondRedirect("../admin")
    }
    post("{id}") {
        val item: T = call.receive()
        getCollection(call.user<USER>()).replaceOneById(this.context.parameters["id"]!!.parseUrlPartOrBadRequest(), item)
        call.respondRedirect("../admin")
    }
    get("create") {
        val user = this.context.principal<BoxPrincipal<USER>>()?.user
        context.respondHtml {
            head { includeFormScript() }
            body {
                form(method = FormMethod.post) {
                    id = "data-form"
                    insideHtmlForm<T>(
                        title = T::class.simpleName ?: "Model",
                        jsEditorName = "editor",
                        defaultValue = defaultItem(user),
                        collapsed = false
                    )
                    button {
                        +"Save"
                    }
                }
            }
        }
    }
    post("create") {
        val item: T = call.receive()
        getCollection(call.user<USER>()).insertOne(item)
        call.respondRedirect("../admin")
    }
    get {
        val secured = getCollection(call.user<USER>())
        val items = secured.query(
            Query(
                condition = Condition.Always(),
                orderBy = listOf(),
                skip = call.request.queryParameters["skip"]?.toIntOrNull() ?: 0,
                limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 25,
            )
        ).toList()
        val propItems = items.map { Serialization.properties.encodeToStringMap(it) }
        val keys = propItems.flatMap { it.keys }.distinct()
        context.respondHtml {
            body {
                a(href = (this@route.selector as PathSegmentConstantRouteSelector).value + "/create") {
                    +"Create"
                }
                table {
                    tr {
                        for (key in keys) {
                            th { +key }
                        }
                    }
                    for (item in propItems) {
                        tr {
                            for (key in keys) {
                                td {
                                    if (key == "_id") {
                                        a(href = (this@route.selector as PathSegmentConstantRouteSelector).value + "/" + item["_id"]!!) {
                                            +(item[key] ?: "-")
                                        }
                                    } else {
                                        +(item[key] ?: "-")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
