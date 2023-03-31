package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.client
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.handler
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serialization.encodeToFormData
import com.lightningkite.lightningserver.serialization.queryParameters
import com.lightningkite.lightningserver.statusFailing
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.serialization.Serializable


/**
 * A shortcut function that sets up OAuth for GitHub accounts specifically.
 *
 * You can set up a new app for GitHub in your [developer settings](https://github.com/settings/developers).
 * Get the client ID and a client secret to put into your [setting] parameter.
 * Return URLs are your auth url + /oauth/github/callback
 */
class OauthGitHubEndpoints<USER : Any, ID>(
    val base: BaseAuthEndpoints<USER, ID>,
    val access: UserExternalServiceAccess<USER, ID>,
    val setting: () -> OauthProviderCredentials,
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
        val params = codeRequest().copy(response_mode = "query").let { Serialization.properties.encodeToFormData(it) }
        HttpResponse.redirectToGet("https://github.com/login/oauth/authorize?$params")
    }

    override val callback: HttpEndpoint = path.get("callback").handler { request ->
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
            if (primary.verified) primary.email else null
        }
        val idResults = ExternalServiceLogin(
            service = niceName,
            username = user.login,
            email = email,
        )
        base.redirectToLanding(access.byExternalService(idResults))
    }
}
