package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.client
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.settings.setting
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import java.net.URLDecoder


abstract class OauthEndpoints(
    path: ServerPath,
    val codeName: String,
    val jwtSigner: ()->JwtSigner,
    val landing: HttpEndpoint,
    val emailToId: suspend (String) -> String
): ServerPathGroup(path) {

    @Serializable
    data class OauthResponse(
        val access_token: String,
        val scope: String = "",
        val token_type: String = "Bearer",
        val id_token: String? = null,
    )

    protected abstract val niceName: String
    protected abstract val authUrl: String
    protected abstract val getTokenUrl: String
    protected abstract val scope: String
    protected abstract val additionalParams: String
    protected open fun secretTransform(secret: String): String { return secret }
    abstract suspend fun fetchEmail(response: OauthResponse): String

    val settings = setting<OauthProviderCredentials?>("oauth_$codeName", null)
    val callbackRoute = path.path("callback")

    suspend fun handleCallback(code: String?, error: String?): HttpResponse {
        val settings = settings() ?: throw NotFoundException("Oauth for $niceName is not configured.")
        return error?.let {
            throw BadRequestException("Got error code '${it}' from $niceName.")
        } ?: code?.let { code ->
            val result = client.post(getTokenUrl) {
                formData {
                    parameter("code", code)
                    parameter("client_id", settings.id)
                    parameter("client_secret", secretTransform(settings.secret))
                    parameter("redirect_uri", generalSettings().publicUrl + callbackRoute.toString())
                    parameter("grant_type", "authorization_code")
                }
                contentType(ContentType.Application.FormUrlEncoded)
                accept(ContentType.Application.Json)
            }
            if(!result.status.isSuccess()) {
                throw Exception("Got status ${result.status} from callback with body ${result.bodyAsText()}")
            }
            val response: OauthResponse = result.body()

            HttpResponse.redirectToGet(
                generalSettings().publicUrl + landing.path.toString() + "?jwt=${
                    jwtSigner().token(
                        emailToId(fetchEmail(response).lowercase())
                    )
                }"
            )
        } ?: throw IllegalStateException()
    }
    val callbackGet = callbackRoute.get.handler { request ->
        handleCallback(request.queryParameter("code"), request.queryParameter("error"))
    }
    val callbackPost = callbackRoute.post.handler { request ->
        val text = request.body!!.text()
        val map = text.split('&').associate {
            URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8).lowercase() to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
        }
        handleCallback(map["code"], map["error"])
    }

    val login = path.get("login").handler { request ->
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
}
