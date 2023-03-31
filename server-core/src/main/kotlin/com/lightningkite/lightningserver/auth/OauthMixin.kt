package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.client
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.routes.fullUrl
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serialization.encodeToFormData
import com.lightningkite.lightningserver.statusFailing
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*

interface OauthMixin {
    val clientId: String
    val clientSecret: String
    val niceName: String
    val callback: HttpEndpoint
    val scope: String

    fun codeRequest(): OauthCodeRequest = OauthCodeRequest(
        response_type = "code",
        scope = scope,
        redirect_uri = callback.path.fullUrl(),
        client_id = clientId,
        response_mode = "form_post"
    )

    suspend fun codeToToken(getTokenUrl: String, oauth: OauthCode): OauthResponse {
        oauth.error?.let {
            throw BadRequestException("Got error code '${it}' from $niceName.")
        } ?: oauth.code?.let { code ->
            return client.post(getTokenUrl) {
                setBody(
                    Serialization.properties.encodeToFormData(
                        OauthTokenRequest(
                            code = code,
                            client_id = clientId,
                            client_secret = clientSecret,
                            redirect_uri = callback.path.fullUrl(),
                            grant_type = "authorization_code",
                        )
                    )
                )
                contentType(ContentType.Application.FormUrlEncoded)
                accept(ContentType.Application.Json)
            }.statusFailing().body<OauthResponse>()
        }
        throw BadRequestException("Code is empty")
    }
}