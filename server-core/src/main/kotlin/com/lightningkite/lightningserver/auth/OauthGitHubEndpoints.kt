package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.client
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.routes.fullUrl
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serialization.encodeToFormData
import com.lightningkite.lightningserver.serialization.parse
import com.lightningkite.lightningserver.serialization.queryParameters
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.statusFailing
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.util.*
import kotlinx.serialization.Serializable
import java.util.*


/**
 * A shortcut function that sets up OAuth for GitHub accounts specifically.
 *
 * @param defaultLanding The final page to send the user after authentication.
 * @param emailToId A lambda that returns the users ID given an email.
 */
class OauthGitHubEndpoints<USER: Any, ID>(
    val base: BaseAuthEndpoints<USER, ID>,
    val access: UserExternalServiceAccess<USER, ID>,
    val setting: ()->OauthProviderCredentials,
    override val scope: String = "user:email"
) : ServerPathGroup(base.path.path("oauth/github")), OauthMixin {
    override val niceName = "GitHub"
    override val clientId: String
        get() = setting().id
    override val clientSecret: String
        get() = setting().secret

    @Serializable
    private data class GithubUser(
        val login: String? = null,
        val id: Long? = null,
        val url: String? = null,
        val email: String? = null
    )

    @Serializable
    private data class GithubEmail(
        val email: String,
        val verified: Boolean,
        val primary: Boolean,
        val visibility: String? = null
    )

    val login = path.get("login").handler { request ->
        val params = codeRequest().let { Serialization.properties.encodeToFormData(it) }
        HttpResponse.redirectToGet("https://github.com/login/oauth/authorize?$params")
    }

    override val callback: HttpEndpoint = path.post("callback").handler { request ->
        val response = codeToToken("https://github.com/login/oauth/access_token", request.queryParameters())
        val user = run {
            client.get("https://api.github.com/user") {
                headers {
                    append("Authorization", "${response.token_type} ${response.access_token}")
                }
            }.statusFailing().body<GithubUser>()
        }
        val email = run {
            val response2: List<GithubEmail> = client.get("https://api.github.com/user/emails") {
                headers {
                    append("Authorization", "${response.token_type} ${response.access_token}")
                }
            }.statusFailing().body()
            val primary = response2.firstOrNull { it.primary }
                ?: response2.firstOrNull()
                ?: return@run null
            if(primary.verified) primary.email else null
        }
        val idResults = ExternalServiceLogin(
            service = niceName,
            username = user.login,
            email = email,
        )
        base.redirectToLanding(access.byExternalService(idResults))
    }
}
