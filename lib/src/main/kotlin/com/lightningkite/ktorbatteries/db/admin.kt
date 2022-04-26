package com.lightningkite.ktorbatteries.db

import com.lightningkite.ktorbatteries.serialization.Serialization
import com.lightningkite.ktorbatteries.typed.jsForm
import com.lightningkite.ktorbatteries.typed.includeFormScript
import com.lightningkite.ktorbatteries.typed.insideHtmlForm
import com.lightningkite.ktorkmongo.*
import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.html.*
import io.ktor.http.content.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import kotlinx.coroutines.flow.toList
import kotlinx.html.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.properties.encodeToStringMap
import kotlin.reflect.typeOf


@ContextDsl
inline fun <reified USER: Principal, reified T : HasId> Route.adminPages(
    collection: FieldCollection<T>,
    crossinline defaultItem: (USER?)->T,
    crossinline secure: suspend (USER?) -> SecurityRules<T>
) {
    adminDetail(collection, defaultItem, secure)
    adminList(collection, defaultItem, secure)
}

@ContextDsl
inline fun <reified USER: Principal, reified T : HasId> Route.adminDetail(
    collection: FieldCollection<T>,
    crossinline defaultItem: (USER?)->T,
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
                }
            }
        }
    }
    post("{id}") {
        val item: T = call.receiveParameters()["__json"]!!.let { Serialization.json.decodeFromString(it) }
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
        val parameters = call.receiveParameters()
        println(parameters.toMap())
        val item: T = parameters["__json"]!!.let { Serialization.json.decodeFromString(it) }
        collection.insertOne(item)
        call.respondRedirect("..")
    }
}

@ContextDsl
inline fun <reified USER: Principal, reified T : HasId> Route.adminList(
    collection: FieldCollection<T>,
    crossinline defaultItem: (USER?)->T,
    crossinline secure: suspend (USER?) -> SecurityRules<T>
) {
    get {
        val secured = collection.secure(secure(this.context.principal()))
        val items = secured.query(Query(
            condition = Condition.Always(),
            orderBy = listOf(),
            skip = call.request.queryParameters["skip"]?.toIntOrNull() ?: 0,
            limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 25,
        )).toList()
        val propItems = items.map { Serialization.properties.encodeToStringMap(it) }
        val keys = propItems.flatMap { it.keys }.distinct()
        context.respondHtml {
            body {
                a(href="create") {
                    +"Create"
                }
                table {
                    tr {
                        for(key in keys) {
                            th { +key }
                        }
                    }
                    for(item in propItems) {
                        tr {
                            for(key in keys) {
                                td {
                                    if(key == "_id") {
                                        a(href=item["_id"]!!) {
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