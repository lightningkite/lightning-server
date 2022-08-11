package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.auth.rawUser
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.handler
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serialization.parse
import com.lightningkite.lightningserver.typed.includeFormScript
import com.lightningkite.lightningserver.typed.insideHtmlForm
import com.lightningkite.lightningserver.typed.parseUrlPartOrBadRequest
import kotlinx.coroutines.flow.toList
import kotlinx.html.*

open class ModelAdminEndpoints<USER, T : HasId<ID>, ID : Comparable<ID>> (
    path: ServerPath,
    val info: ModelInfoWithDefault<USER, T, ID>
): ServerPathGroup(path) {
    companion object {
        val known: MutableCollection<ModelAdminEndpoints<*, *, *>> = ArrayList()
    }
    init {
        known.add(this)
    }

    val name = info.serialization.serializer.descriptor.serialName.substringAfterLast('.')
    val edit = get("{id}/").handler {
        val secured = info.collection(info.serialization.authInfo.checker(it.rawUser()))
        val item = secured.get(it.parts["id"]!!.parseUrlPartOrBadRequest(info.serialization.idSerializer))
        HttpResponse.html {
            head { includeFormScript() }
            body {
                form(method = FormMethod.post) {
                    id = "data-form"
                    insideHtmlForm(
                        title = name,
                        jsEditorName = "editor",
                        serializer = info.serialization.serializer,
                        defaultValue = item,
                        collapsed = false
                    )
                    button {
                        +"Save"
                    }
                    a(href = "delete/") {
                        +"Delete"
                    }
                }
            }
        }
    }
    val editDelete = get("{id}/delete/").handler {
        HttpResponse.html {
            body {
                p { +"Are you sure?" }
                form {
                    button(formMethod = ButtonFormMethod.post) { +"Yes" }
                }
            }
        }
    }
    val editDeletePost = post("{id}/delete/").handler {
        info.collection(info.serialization.authInfo.checker(it.rawUser())).deleteOneById(
            it.parts["id"]!!.parseUrlPartOrBadRequest(
                info.serialization.idSerializer
            )
        )
        HttpResponse.redirectToGet("../..")
    }
    val editPost = post("{id}/").handler {
        val item: T = it.body!!.parse(info.serialization.serializer)
        info.collection(info.serialization.authInfo.checker(it.rawUser())).replaceOneById(
            it.parts["id"]!!.parseUrlPartOrBadRequest(
                info.serialization.idSerializer
            ), item
        )
        HttpResponse.redirectToGet("..")
    }
    val create = get("create/").handler {
        val user = info.serialization.authInfo.checker(it.rawUser())
        val item = info.defaultItem(user)
        HttpResponse.html {
            head { includeFormScript() }
            body {
                form(method = FormMethod.post) {
                    id = "data-form"
                    insideHtmlForm<T>(
                        title = name,
                        jsEditorName = "editor",
                        serializer = info.serialization.serializer,
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
    val createPost = post("create/").handler {
        val item: T = it.body!!.parse(info.serialization.serializer)
        info.collection(info.serialization.authInfo.checker(it.rawUser())).insertOne(item)
        HttpResponse.redirectToGet("..")
    }
    val list = get("/").handler {
        val secured = info.collection(info.serialization.authInfo.checker(it.rawUser()))
        val items = secured.query(
            Query(
                condition = Condition.Always(),
                orderBy = listOf(),
                skip = it.queryParameter("skip")?.toIntOrNull() ?: 0,
                limit = it.queryParameter("limit")?.toIntOrNull() ?: 25,
            )
        ).toList()
        val propItems = items.map { Serialization.properties.encodeToStringMap(info.serialization.serializer, it) }
        val keys = propItems.flatMap { it.keys }.distinct()
        HttpResponse.html {
            body {
                a(href = "create/") {
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
                                        a(href = item["_id"]!! + "/") {
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