package com.lightningkite.lightningserver.auth.proof

import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.auth.oauth.OauthProviderCredentials
import com.lightningkite.lightningserver.auth.oauth.OauthProviderInfo
import com.lightningkite.lightningserver.auth.oauth.oauthCallback
import com.lightningkite.lightningserver.auth.old.asExternal
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.email.Email
import com.lightningkite.lightningserver.email.EmailClient
import com.lightningkite.lightningserver.email.EmailLabeledValue
import com.lightningkite.lightningserver.email.EmailPersonalization
import com.lightningkite.lightningserver.encryption.SecureHasher
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.typed.*
import io.ktor.http.*
import kotlin.time.Duration
import kotlinx.datetime.*
import java.util.*

class OauthProofEndpoints(
    path: ServerPath,
    proofHasher: () -> SecureHasher,
    val provider: OauthProviderInfo,
    val credentials: () -> OauthProviderCredentials,
    val continueUiAuthUrl: ()->String
) : ServerPathGroup(path), Authentication.ExternalProofMethod {

    override val name: String
        get() = provider.identifierName
    override val humanName: String
        get() = provider.niceName
    override val strength: Int
        get() = 10
    override val validates: String
        get() = "email"

    @Suppress("UNCHECKED_CAST")
    val callback = path("callback").oauthCallback<UUID>(
        oauthProviderInfo = provider,
        credentials = credentials
    ) { response, uuid ->
        val profile = provider.getProfile(response)
        val email = profile.email ?: throw BadRequestException("No email was found for this profile.")
        HttpResponse.redirectToGet(continueUiAuthUrl() + "?proof=${Serialization.json.encodeToString(Proof.serializer(), proofHasher().makeProof(
            info = info,
            value = email,
            at = Clock.System.now()
        )).encodeURLQueryComponent()}")
    }
    override val indirectLink: ServerPath = path("open").get.handler {
        HttpResponse.redirectToGet(callback.loginUrl(UUID.randomUUID()))
    }.path
    val loginApi = path("login").get.api(
        summary = "Log In via ${provider.niceName}",
        authOptions = noAuth,
        description = "Returns a URL which, when opened in a browser, will allow you to log into the system with ${provider.niceName}.",
        errorCases = listOf(),
        examples = listOf(
            ApiExample(
                Unit,
                "${provider.loginUrl}?someparams=x"
            )
        ),
        implementation = { _: Unit ->
            callback.loginUrl(UUID.randomUUID())
        }
    )
    override val start = path("start").get.api(
        summary = "Log In via ${provider.niceName}",
        authOptions = noAuth,
        description = "Returns a URL which, when opened in a browser, will allow you to log into the system with ${provider.niceName}.",
        errorCases = listOf(),
        examples = listOf(
            ApiExample(
                "joseph@lightningkite.com",
                "${provider.loginUrl}?someparams=x"
            )
        ),
        implementation = { ensureEmail: String ->
            // TODO: Ensure the passed email
            callback.loginUrl(UUID.randomUUID())
        }
    )
}