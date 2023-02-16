package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.client
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.debugJsonBody
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serialization.encodeToFormData
import com.lightningkite.lightningserver.serialization.parse
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.util.*
import kotlinx.serialization.Serializable
import java.util.*


/**
 * A shortcut function that sets up OAuth for Microsoft accounts specifically.
 *
 * @param defaultLanding The final page to send the user after authentication.
 * @param emailToId A lambda that returns the users ID given an email.
 */
class OauthMicrosoftEndpoints<USER: Any, ID>(
    val base: BaseAuthEndpoints<USER, ID>,
    val access: UserExternalServiceAccess<USER, ID>,
    val setting: ()->OauthProviderCredentials,
    override val scope: String = "openid email profile"
) : ServerPathGroup(base.path.path("oauth/microsoft")), OauthMixin {
    override val niceName = "Microsoft"
    override val clientId: String
        get() = setting().id
    override val clientSecret: String
        get() = setting().secret

    val login = path.get("login").handler { request ->
        val params = codeRequest().let { Serialization.properties.encodeToFormData(it) }
        HttpResponse.redirectToGet("https://login.microsoftonline.com/common/oauth2/v2.0/authorize?$params")
    }

    override val callback: HttpEndpoint = path.post("callback").handler { request ->
        val response = codeToToken("https://login.microsoftonline.com/common/oauth2/v2.0/token", request.body!!.parse())
        val response2: MicrosoftAccountInfo = client.get("https://graph.microsoft.com/oidc/userinfo") {
            headers {
                append("Authorization", "${response.token_type} ${response.access_token}")
            }
        }.debugJsonBody()
        base.redirectToLanding(access.byExternalService(ExternalServiceLogin(
            service = niceName,
            email = response2.email,
            avatar = response2.picture
        )))
    }

    @Serializable
    private data class MicrosoftAccountInfo(
        val email: String? = null,
        val picture: String? = null
    )
}
