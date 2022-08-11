package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.client
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
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


abstract class OauthEndpoints(
    path: ServerPath,
    val jwtSigner: ()->JwtSigner,
    val landing: HttpEndpoint,
    val emailToId: suspend (String) -> String
): ServerPathGroup(path) {

    @Serializable
    data class OauthResponse(
        val access_token: String,
        val scope: String,
        val token_type: String = "Bearer",
        val id_token: String? = null
    )

    protected abstract val niceName: String
    protected abstract val codeName: String
    protected abstract val authUrl: String
    protected abstract val getTokenUrl: String
    protected abstract val scope: String
    protected abstract val additionalParams: String
    protected open fun secretTransform(secret: String): String { return secret }
    abstract suspend fun fetchEmail(response: OauthResponse): String

    val settings by lazy { setting<OauthProviderCredentials?>("oauth-$codeName", null) }
    val callbackRoute = path.get("callback")
    init {
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

                HttpResponse.redirectToGet(
                    generalSettings().publicUrl + landing.toString() + "?jwt=${
                        jwtSigner().token(
                            emailToId(fetchEmail(response))
                        )
                    }"
                )
            } ?: throw IllegalStateException()
        }
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
