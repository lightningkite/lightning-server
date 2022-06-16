package com.lightningkite.ktorbatteries.auth

import com.lightningkite.ktorbatteries.client
import com.lightningkite.ktorbatteries.routes.fullPath
import com.lightningkite.ktorbatteries.settings.GeneralServerSettings
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import kotlinx.serialization.Serializable
import java.util.*


@Serializable
data class OauthResponse(
    val access_token: String,
    val scope: String,
    val token_type: String = "Bearer",
    val id_token: String? = null
)


/**
 * A shortcut function for setting up an OAuth method.
 * It will set up a login and callback endpoint for the method.
 *
 * @param niceName A readable name for this method
 * @param codeName Used as the path as well as the key for the oauth config found in AuthSettings.
 * @param authUrl The url to redirect the user upon auth request. This will be the third parties auth url.
 * @param getTokenUrl The third parties url for retrieving their verification token.
 * @param scope The oath Scope.
 * @param additionalParams Any additional parameters to add to the third party url.
 * @param defaultLanding The final page to direct the user after authenticating.
 * @param secretTransform An optional lambda that allows any custom transformations on the client_secret before being used.
 * @param remoteTokenToUserId A lambda that will return the userId given the token from the third party.
 */
@KtorDsl
inline fun Route.oauth(
    niceName: String,
    codeName: String,
    authUrl: String,
    getTokenUrl: String,
    scope: String,
    additionalParams: String = "",
    defaultLanding: String = "",
    crossinline secretTransform: (String) -> String = { it },
    crossinline remoteTokenToUserId: suspend (OauthResponse)->String
) = route(codeName) {
    val settings = AuthSettings.instance.oauth[codeName] ?: return@route
    val baseUrl = GeneralServerSettings.instance.publicUrl + this.fullPath
    get("login") {
        val token = makeToken {
            withClaim("landing", call.request.queryParameters["redirect_uri"] ?: defaultLanding)
                .withClaim("requester", call.request.origin.remoteHost)
                .withExpiresAt(Date(System.currentTimeMillis() + 10L * 60L * 1000L))
        }
        call.respondRedirect("""
                    $authUrl?
                    response_type=code&
                    scope=$scope&
                    state=$token&
                    redirect_uri=$baseUrl/callback&
                    client_id=${settings.id}
                    $additionalParams
                """.trimIndent().replace("\n", ""))
    }
    get("callback") {
        val token = call.request.queryParameters["state"] ?: throw BadRequestException("State token missing")
        val tokenResult = checkToken(token) ?: throw BadRequestException("Invalid state token")
        val requester = tokenResult.getClaim("requester")!!.asString()
        if(requester != call.request.origin.remoteHost) throw BadRequestException("Not original requester - original requester was ${requester}, but you are ${call.request.origin.remoteHost}.")
        val destinationUri = GeneralServerSettings.instance.publicUrl + tokenResult.getClaim("landing")!!.asString()
        call.request.queryParameters["error"]?.let {
            throw BadRequestException("Got error code '${it}' from $niceName.")
        } ?: call.request.queryParameters["code"]?.let { code ->
            val response: OauthResponse = client.post(getTokenUrl) {
                formData {
                    parameter("code", code)
                    parameter("client_id", settings.id)
                    parameter("client_secret", secretTransform(settings.secret))
                    parameter("redirect_uri", "$baseUrl/callback")
                    parameter("grant_type", "authorization_code")
                }
                accept(ContentType.Application.Json)
            }.body()
            call.respondRedirect(destinationUri + "?jwt=${makeToken(remoteTokenToUserId(response))}")
        }
    }
}