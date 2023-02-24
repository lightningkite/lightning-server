package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.client
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serialization.encodeToFormData
import com.lightningkite.lightningserver.serialization.parse
import com.lightningkite.lightningserver.serialization.queryParameters
import com.lightningkite.lightningserver.statusFailing
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.util.*
import kotlinx.serialization.Serializable
import java.util.*

/**
 * A shortcut function that sets up OAuth for Google accounts specifically.
 *
 * You can set up a new Google project in the [Google console](https://console.cloud.google.com)
 * Fill out the [OAuth Consent Screen](https://console.cloud.google.com/apis/credentials/consent)
 * Enable the non-sensitive scopes for '.../auth/userinfo.email' and '.../auth/userinfo.profile'
 * Add an [OAuth 2.0 Client ID](https://console.cloud.google.com/apis/credentials/oauthclient)
 * 'Authorized redirect URIs' are your auth url + /oauth/google/callback
 *
 */
class OauthGoogleEndpoints<USER: Any, ID>(
    val base: BaseAuthEndpoints<USER, ID>,
    val access: UserExternalServiceAccess<USER, ID>,
    val setting: ()->OauthProviderCredentials,
    override val scope: String = "https://www.googleapis.com/auth/userinfo.email"
) : ServerPathGroup(base.path.path("oauth/google")), OauthMixin {
    override val niceName = "Google"
    override val clientId: String
        get() = setting().id
    override val clientSecret: String
        get() = setting().secret

    val login = path.get("login").handler { request ->
        val params = codeRequest().let { Serialization.properties.encodeToFormData(it) }
        HttpResponse.redirectToGet("https://accounts.google.com/o/oauth2/v2/auth?$params&access_type=online&include_granted_scopes=true")
    }

    override val callback: HttpEndpoint = path.post("callback").handler { request ->
        val response = codeToToken("https://oauth2.googleapis.com/token", request.body!!.parse())
        val response2: GoogleResponse2 = client.get("https://www.googleapis.com/oauth2/v2/userinfo") {
            headers {
                append("Authorization", "${response.token_type} ${response.access_token}")
            }
        }.body()
        val idResults = ExternalServiceLogin(
            service = niceName,
            email = if(response2.verified_email) response2.email else null,
        )
        base.redirectToLanding(access.byExternalService(idResults))
    }

    @Serializable
    private data class GoogleResponse2(
        val verified_email: Boolean,
        val email: String,
        val picture: String
    )

}
