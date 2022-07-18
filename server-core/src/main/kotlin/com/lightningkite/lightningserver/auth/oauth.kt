package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.client
import com.lightningkite.lightningserver.core.LightningServerDsl
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.handler
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.settings.setting
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
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
@LightningServerDsl
inline fun ServerPath.oauth(
    noinline jwtSigner: ()->JwtSigner,
    landingRoute: HttpEndpoint,
    niceName: String,
    codeName: String,
    authUrl: String,
    getTokenUrl: String,
    scope: String,
    additionalParams: String = "",
    crossinline secretTransform: (String) -> String = { it },
    crossinline remoteTokenToUserId: suspend (OauthResponse)->String
) {
    val landing = landingRoute
    val settings = setting<OauthProviderCredentials?>("oauth-$codeName", null)
    val callbackRoute = get("callback")
    get("login").handler { request ->
        val settings = settings() ?: throw NotFoundException("Oauth for $niceName is not configured.")
        HttpResponse.redirectToGet("""
                    $authUrl?
                    response_type=code&
                    scope=$scope&
                    redirect_uri=${generalSettings().publicUrl + callbackRoute.toString()}&
                    client_id=${settings.id}
                    $additionalParams
                """.trimIndent().replace("\n", ""))
    }
    callbackRoute.handler { request ->
        val settings = settings() ?: throw NotFoundException("Oauth for $niceName is not configured.")
        request.queryParameter("error")?.let {
            throw BadRequestException("Got error code '${it}' from $niceName.")
        } ?: request.queryParameter("code")?.let { code ->
            val response: OauthResponse = client.post(getTokenUrl) {
                formData {
                    parameter("code", code)
                    parameter("client_id", settings.id)
                    parameter("client_secret", secretTransform(settings.secret))
                    parameter("redirect_uri", generalSettings().publicUrl + callbackRoute.toString())
                    parameter("grant_type", "authorization_code")
                }
                accept(ContentType.Application.Json)
            }.body()

            HttpResponse.redirectToGet(generalSettings().publicUrl + landing.toString() + "?jwt=${jwtSigner().token(remoteTokenToUserId(response))}")
        } ?: throw IllegalStateException()
    }
}