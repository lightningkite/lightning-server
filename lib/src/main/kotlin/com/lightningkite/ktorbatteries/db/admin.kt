package com.lightningkite.ktorbatteries.db

import com.lightningkite.ktorbatteries.routes.fullPath
import com.lightningkite.ktorbatteries.serialization.Serialization
import com.lightningkite.ktorbatteries.typed.jsForm
import com.lightningkite.ktorbatteries.typed.includeFormScript
import com.lightningkite.ktorbatteries.typed.insideHtmlForm
import com.lightningkite.ktorkmongo.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.html.*
import io.ktor.server.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import kotlinx.coroutines.flow.toList
import kotlinx.html.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.properties.encodeToStringMap
import kotlin.reflect.typeOf


@KtorDsl
inline fun <reified USER : Principal, reified T : HasId> Route.adminPages(
    collection: FieldCollection<T>,
    crossinline defaultItem: (USER?) -> T,
    crossinline secure: suspend (USER?) -> SecurityRules<T>
) {
    adminDetail(collection, defaultItem, secure)
    adminList(collection, defaultItem, secure)
}

@KtorDsl
inline fun <reified USER : Principal, reified T : HasId> Route.adminDetail(
    collection: FieldCollection<T>,
    crossinline defaultItem: (USER?) -> T,
    crossinline secure: suspend (USER?) -> SecurityRules<T>
) {
    get("{id}") {
        val secured = collection.secure(secure(this.context.principal()))
        val item = secured.get(this.context.parameters["id"]!!.toUuidOrBadRequest().also { println(it) })
        println(secured.query(Query()).toList())
        println(item)
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
                    deleteButton {
                        +"Delete"
                    }
                }
            }
        }
    }
    delete("{id}") {
        collection.deleteOneById(this.context.parameters["id"]!!.toUuidOrBadRequest())
        call.respondRedirect("..")
    }
    post("{id}") {
        val item: T = call.receive()
        collection.replaceOneById(this.context.parameters["id"]!!.toUuidOrBadRequest(), item)
        call.respondRedirect("..")
    }
    get("create") {
        val user = this.context.principal<USER>()
        val secured = collection.secure(secure(user))
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
        collection.insertOne(item)
        call.respondRedirect("..")
    }
}

@KtorDsl
inline fun <reified USER : Principal, reified T : HasId> Route.adminList(
    collection: FieldCollection<T>,
    crossinline defaultItem: (USER?) -> T,
    crossinline secure: suspend (USER?) -> SecurityRules<T>
) {
    get {
        val secured = collection.secure(secure(this.context.principal()))
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
                a(href = "create") {
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
                                        a(href = "/" + (this@adminList.selector as PathSegmentConstantRouteSelector).value + "/" + item["_id"]!!) {
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