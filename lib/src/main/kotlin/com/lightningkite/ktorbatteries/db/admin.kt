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
    crossinline rules: suspend (principal: USER?, input: FieldCollection<T>) -> FieldCollection<T>
) {
    adminDetail(collection, defaultItem, rules)
    adminList(collection, defaultItem, rules)
}

@KtorDsl
inline fun <reified USER : Principal, reified T : HasId> Route.adminDetail(
    collection: FieldCollection<T>,
    crossinline defaultItem: (USER?) -> T,
    crossinline rules: suspend (principal: USER?, input: FieldCollection<T>) -> FieldCollection<T>
) {
    get("{id}") {
        val secured = rules(context.principal(), collection)
        val item = secured.get(this.context.parameters["id"]!!.toUuidOrBadRequest().also { println(it) })
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
        collection.deleteOneById(this.context.parameters["id"]!!.toUuidOrBadRequest())
        call.respondRedirect("..")
    }
    post("{id}") {
        val item: T = call.receive()
        collection.replaceOneById(this.context.parameters["id"]!!.toUuidOrBadRequest(), item)
        call.respondRedirect(".")
    }
    get("create") {
        val user = this.context.principal<USER>()
        val secured = rules(user, collection)
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
        call.respondRedirect(".")
    }
}

@KtorDsl
inline fun <reified USER : Principal, reified T : HasId> Route.adminList(
    collection: FieldCollection<T>,
    crossinline defaultItem: (USER?) -> T,
    crossinline rules: suspend (principal: USER?, input: FieldCollection<T>) -> FieldCollection<T>
) {
    get {
        val secured = rules(context.principal(), collection)
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
                println((this@adminList.selector as PathSegmentConstantRouteSelector).value + "/create")
                a(href = (this@adminList.selector as PathSegmentConstantRouteSelector).value + "/create") {
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
                                        a(href = (this@adminList.selector as PathSegmentConstantRouteSelector).value + "/" + item["_id"]!!) {
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