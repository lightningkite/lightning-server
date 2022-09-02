package com.lightningkite.lightningserver.meta

import com.lightningkite.lightningserver.auth.AuthInfo
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.db.adminIndex
import com.lightningkite.lightningserver.http.HttpContent
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.handler
import com.lightningkite.lightningserver.routes.fullUrl
import com.lightningkite.lightningserver.serverhealth.healthCheck
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.typed.apiDocs
import com.lightningkite.lightningserver.typed.apiHelp
import kotlinx.html.*

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
                    li { a(href = docs.path.fullUrl()) { +"docs" } }
                    li { a(href = health.path.fullUrl()) { +"health" } }
                    li { a(href = isOnline.path.fullUrl()) { +"isOnline" } }
                    li { a(href = adminIndex.path.fullUrl()) { +"adminIndex" } }
                }
            }
        })
    }
    val docs = path("docs").apiDocs(packageName)
    val health = path("health").healthCheck(authInfo, isAdmin)
    val isOnline = path("online").get.handler { HttpResponse.plainText("Server is running.") }
    val adminIndex = path("admin").adminIndex()
}

inline fun <reified USER> ServerPath.metaEndpoints(packageName: String = "com.mypackage", noinline isAdmin: suspend (USER)->Boolean): MetaEndpoints<USER>
    = MetaEndpoints(this, AuthInfo(), packageName, isAdmin)