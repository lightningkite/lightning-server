package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.client
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.routes.fullUrl
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serialization.encodeToFormData
import com.lightningkite.lightningserver.serialization.parse
import com.lightningkite.lightningserver.statusFailing
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.util.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.time.Duration
import java.time.Instant
import java.util.*

/**
 * A shortcut function that sets up OAuth for Apple accounts specifically.
 *
 * SETUP STEPS:
 * Get an [Apple Developer Account](https://developer.apple.com)
 * Go to [Certificates, Identities, and Profiles](https://developer.apple.com/account/resources/certificates/list)
 * Add or edit an [App Identifier](https://developer.apple.com/account/resources/identifiers/list/bundleId) to have "Sign in with Apple" capability
 * Add a [Service Identifier](https://developer.apple.com/account/resources/identifiers/list/serviceId) for the server
 * Add Sign In With Apple to said service identifier
 *     Return URLs are your auth url + /oauth/apple/callback
 * Make a [key](https://developer.apple.com/account/resources/authkeys/list) for the server
 * Download the .p8
 * Copy out the contents of the P8 (it's a regular text file)
 * Set the credentials to:
 *     appId: the App ID above
 *     serviceId: the Service ID above
 *     teamId: Your team identifier
 *     keyId: Your key's ID
 *     keyString: the contents of the P8 without the begin/end private key annotations
 */
class OauthAppleEndpoints<USER: Any, ID>(
    val base: BaseAuthEndpoints<USER, ID>,
    val access: UserExternalServiceAccess<USER, ID>,
    val setting: ()->OauthAppleSettings,
): ServerPathGroup(base.path.path("oauth/apple")) {

    @Serializable data class OauthAppleSettings(
        val appId: String,
        val serviceId: String,
        val teamId: String,
        val keyId: String,
        val keyString: String
    ) {
        fun generateJwt(): String {
            return buildString {
                val withDefaults = Json { encodeDefaults = true; explicitNulls = false }
                append(Base64.getUrlEncoder().withoutPadding().encodeToString(withDefaults.encodeToString(buildJsonObject {
//                    put("typ", "JWT")
                    put("kid", keyId)
                    put("alg", "ES256")
                }).toByteArray()))
                append('.')
                val issuedAt = Instant.now()
                append(
                    Base64.getUrlEncoder().withoutPadding().encodeToString(
                        withDefaults.encodeToString(
                            buildJsonObject {
                                put("iss", teamId)
                                put("iat", issuedAt.toEpochMilli().div(1000))
                                put("exp", issuedAt.plus(Duration.ofDays(5)).toEpochMilli().div(1000))
                                put("aud", "https://appleid.apple.com")
                                put("sub", appId)
                            }
                        ).toByteArray()
                    )
                )
                val soFar = this.toString()
                append('.')
                append(
                    Base64.getUrlEncoder().withoutPadding().encodeToString(SecureHasher.ECDSA256(keyString).sign(soFar.toByteArray()))
                )
            }
        }
    }

    val niceName = "Apple"
    val scope = "email"

    val login = path.get("login").handler { request ->
        val params = codeRequest().let { Serialization.properties.encodeToFormData(it) }
        HttpResponse.redirectToGet("https://appleid.apple.com/auth/authorize?$params")
    }

    val callback: HttpEndpoint = path.post("callback").handler { request ->
        val response = codeToToken("https://appleid.apple.com/auth/token", request.body!!.parse())
        val id = (response.id_token ?: throw BadRequestException("No id_token found in response"))
        val decoded = Serialization.json.parseToJsonElement(
            Base64.getUrlDecoder().decode(id.split('.')[1]).toString(Charsets.UTF_8)
        ) as JsonObject
        if (!decoded.get("email_verified")!!.jsonPrimitive.boolean)
            throw BadRequestException("Apple has not verified the email address.")
        val email = decoded.get("email")!!.jsonPrimitive.content
        val idResults = ExternalServiceLogin(
            service = niceName,
            username = null,
            email = email,
        )
        base.redirectToLanding(access.byExternalService(idResults))
    }

    fun codeRequest(): OauthCodeRequest = OauthCodeRequest(
        response_type = "code",
        scope = scope,
        redirect_uri = callback.path.fullUrl(),
        client_id = setting().serviceId,
        response_mode = "form_post"
    )

    suspend fun codeToToken(getTokenUrl: String, oauth: OauthCode): OauthResponse {
        oauth.error?.let {
            throw BadRequestException("Got error code '${it}' from $niceName.")
        } ?: oauth.code?.let { code ->
            return client.post(getTokenUrl) {
                setBody(Serialization.properties.encodeToFormData(OauthTokenRequest(
                    code = code,
                    client_id = setting().appId,
                    client_secret = setting().generateJwt(),
                    redirect_uri = callback.path.fullUrl(),
                    grant_type = "authorization_code",
                )))
                contentType(ContentType.Application.FormUrlEncoded)
                accept(ContentType.Application.Json)
            }.statusFailing().body<OauthResponse>()
        }
        throw BadRequestException("Code is empty")
    }

}