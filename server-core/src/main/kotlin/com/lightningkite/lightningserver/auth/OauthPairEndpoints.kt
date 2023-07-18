package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.client
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.debugJsonBody
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.routes.fullUrl
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serialization.encodeToFormData
import com.lightningkite.lightningserver.serialization.parse
import com.lightningkite.lightningserver.serialization.queryParameters
import com.lightningkite.lightningserver.statusFailing
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import java.util.*

class OauthPairEndpoints(
    path: ServerPath,
    val oauthProviderInfo: OauthProviderInfo,
    val credentials: () -> OauthProviderCredentials,
    val scope: suspend (HttpRequest)->String = { oauthProviderInfo.scopeForProfile },
    val accessType: OauthAccessType = OauthAccessType.online,
    val onAccess: suspend (OauthResponse) -> HttpResponse
) : ServerPathGroup(path) {

    val login = path.get("login").handler { request ->
        HttpResponse.redirectToGet(oauthProviderInfo.loginUrl(credentials, callback, scope(request), accessType))
    }
    val callback: HttpEndpoint = when (oauthProviderInfo.mode) {
        OauthResponseMode.form_post -> {
            val endpoint = path.post("callback")
            endpoint.handler { request ->
                onAccess(oauthProviderInfo.accessToken(credentials, endpoint, request.body!!.parse<OauthCode>()))
            }
        }
        OauthResponseMode.query -> {
            val endpoint = path.get("callback")
            endpoint.handler { request ->
                onAccess(oauthProviderInfo.accessToken(credentials, endpoint, request.queryParameters<OauthCode>()))
            }
        }
    }
}