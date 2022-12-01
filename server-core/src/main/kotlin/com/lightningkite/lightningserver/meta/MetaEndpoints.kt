package com.lightningkite.lightningserver.meta

import com.lightningkite.lightningserver.auth.AuthInfo
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.db.adminIndex
import com.lightningkite.lightningserver.files.UploadEarlyEndpoint
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.jsonschema.*
import com.lightningkite.lightningserver.routes.fullUrl
import com.lightningkite.lightningserver.schedule.Scheduler
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serverhealth.healthCheck
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.tasks.Tasks
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.lightningserver.websocket.WebSockets
import kotlinx.html.*
import kotlinx.serialization.encodeToString

class MetaEndpoints<USER>(
    path: ServerPath,
    val authInfo: AuthInfo<USER>,
    packageName: String = "com.mypackage",
    isAdmin: suspend (USER)->Boolean
): ServerPathGroup(path) {
    val root = get.handler {
        HttpResponse(body = HttpContent.Html {
            head { title("${generalSettings().projectName} - Meta Information") }
            body {
                ul {
                    for(endpoint in endpoints) {
                        li { a(href = endpoint.path.fullUrl()) { +endpoint.path.segments.last().toString() } }
                    }
                }
            }
        })
    }
    val docs = path("docs").apiDocs(packageName)
    val health = path("health").healthCheck(authInfo, isAdmin)
    val isOnline = path("online").get.handler { HttpResponse.plainText("Server is running.") }
    val adminIndex = path("admin").adminIndex()
    val schema =  path("schema").get.handler {
        HttpResponse(
            body = HttpContent.Text(Serialization.jsonWithoutDefaults.encodeToString(lightningServerSchema), ContentType.Application.Json),
            status = HttpStatus.OK
        )
    }
    val paths = get("paths").handler {
        HttpResponse(body = HttpContent.Html {
            head { title("${generalSettings().projectName} - Path List") }
            body {
                ul {
                    for(endpoint in Http.endpoints.keys) {
                        li { a(href = endpoint.path.fullUrl()) { +endpoint.toString() } }
                    }
                    for(path in WebSockets.handlers.keys) {
                        li { a(href = path.fullUrl()) { +"WS $path" } }
                    }
                    for(schedule in Scheduler.schedules) {
                        li { +"SCHEDULE ${schedule.key}: ${schedule.value.schedule}" }
                    }
                    for(task in Tasks.tasks) {
                        li { +"TASK ${task.key}: ${task.value.serializer.descriptor.serialName}" }
                    }
                }
            }
        })
    }
    val endpoints = listOf<HttpEndpoint>(
        docs,
        health.route,
        isOnline,
        adminIndex,
        schema,
        paths,
    )
}

inline fun <reified USER> ServerPath.metaEndpoints(packageName: String = "com.mypackage", noinline isAdmin: suspend (USER)->Boolean): MetaEndpoints<USER>
    = MetaEndpoints(this, AuthInfo(), packageName, isAdmin)