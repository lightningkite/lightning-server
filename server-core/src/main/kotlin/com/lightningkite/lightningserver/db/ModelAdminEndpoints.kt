package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.auth.cast
import com.lightningkite.lightningserver.auth.rawUser
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.files.UploadEarlyEndpoint
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.handler
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serialization.parse
import com.lightningkite.lightningserver.typed.includeFormScript
import com.lightningkite.lightningserver.typed.insideHtmlForm
import com.lightningkite.lightningserver.typed.parseUrlPartOrBadRequest
import kotlinx.coroutines.flow.toList
import kotlinx.html.*
import kotlinx.serialization.properties.decodeFromStringMap
import java.net.URLEncoder
import java.nio.charset.Charset

open class ModelAdminEndpoints<USER, T : HasId<ID>, ID : Comparable<ID>>(
    path: ServerPath,
    val info: ModelInfoWithDefault<USER, T, ID>,
    val uploadEarlyEndpoint: UploadEarlyEndpoint? = UploadEarlyEndpoint.default,
) : ServerPathGroup(path) {
    companion object {
        val known: MutableCollection<ModelAdminEndpoints<*, *, *>> = ArrayList()
    }

    init {
        known.add(this)
    }

    val name = info.serialization.serializer.descriptor.serialName.substringAfterLast('.')
    val edit = get("{id}/").handler {
        val secured = info.collection(info.serialization.authInfo.cast(it.rawUser()))
        val item = secured.get(it.parts["id"]!!.parseUrlPartOrBadRequest(info.serialization.idSerializer))
        HttpResponse.html {
            head { includeFormScript() }
            body {
                form(method = FormMethod.post) {
                    id = "data-form"
                    insideHtmlForm(
                        title = this@ModelAdminEndpoints.name,
                        jsEditorName = "modelEditor",
                        serializer = info.serialization.serializer,
                        defaultValue = item,
                        collapsed = false,
                        uploadEarlyEndpoint = uploadEarlyEndpoint,
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
        info.collection(info.serialization.authInfo.cast(it.rawUser())).deleteOneById(
            it.parts["id"]!!.parseUrlPartOrBadRequest(
                info.serialization.idSerializer
            )
        )
        HttpResponse.redirectToGet("../..")
    }
    val editPost = post("{id}/").handler {
        val item: T = it.body!!.parse(info.serialization.serializer)
        info.collection(info.serialization.authInfo.cast(it.rawUser())).replaceOneById(
            it.parts["id"]!!.parseUrlPartOrBadRequest(
                info.serialization.idSerializer
            ), item
        )
        HttpResponse.redirectToGet("..")
    }
    val create = get("create/").handler {
        val user = info.serialization.authInfo.cast(it.rawUser())
        val item = info.defaultItem(user)
        HttpResponse.html {
            head { includeFormScript() }
            body {
                form(method = FormMethod.post) {
                    id = "data-form"
                    insideHtmlForm<T>(
                        title = this@ModelAdminEndpoints.name,
                        jsEditorName = "modelEditor",
                        serializer = info.serialization.serializer,
                        defaultValue = item,
                        collapsed = false,
                        uploadEarlyEndpoint = uploadEarlyEndpoint,
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
        info.collection(info.serialization.authInfo.cast(it.rawUser())).insertOne(item)
        HttpResponse.redirectToGet("..")
    }
    val list = get("/").handler {
        val secured = info.collection(info.serialization.authInfo.cast(it.rawUser()))
        val query = Serialization.properties.decodeFromStringMap<Query<T>>(Query.serializer(info.serialization.serializer), it.queryParameters.associate { it })
        val items = secured.query(query).toList()
        val propItems = items.map { Serialization.properties.encodeToStringMap(info.serialization.serializer, it) }
        val keys = propItems.flatMap { it.keys }.distinct()
        HttpResponse.html {
            head {
                style {
                    unsafe {
                        +"""
                        td {
                          max-width: 25rem;
                          padding: 1rem;
                          overflow: hidden;
                          min-width: 5rem;
                        }
                        """.trimIndent()
                    }
                }
            }
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
                                    } else if(item[key]?.let { it.startsWith("https://") || it.startsWith("http://") } == true) {
                                        a(href = item[key]!!, target = "_blank") {
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
                div {
                    if (query.skip != 0) {
                        a(
                            href = "?${
                                it.queryParameters.associate { it.first.lowercase() to it.second }.toMutableMap()
                                    .apply {
                                        this["skip"] = (query.skip - query.limit).toString()
                                        this["limit"] = (query.limit).toString()
                                    }.entries.joinToString("&") {
                                        "${it.key}=${
                                            URLEncoder.encode(
                                                it.value,
                                                Charsets.UTF_8
                                            )
                                        }"
                                    }
                            }"
                        ) { +"Previous Page " }
                    }
                    if(items.size == query.limit) {
                        a(
                            href = "?${
                                it.queryParameters.associate { it.first.lowercase() to it.second }.toMutableMap()
                                    .apply {
                                        this["skip"] = (query.skip + query.limit).toString()
                                        this["limit"] = (query.limit).toString()
                                    }.entries.joinToString("&") {
                                    "${it.key}=${
                                        URLEncoder.encode(
                                            it.value,
                                            Charsets.UTF_8
                                        )
                                    }"
                                }
                            }"
                        ) { +"Next Page " }
                    }
                }
            }
        }
    }
}