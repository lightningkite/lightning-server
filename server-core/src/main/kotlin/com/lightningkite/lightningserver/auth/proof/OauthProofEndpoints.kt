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
import com.lightningkite.lightningserver.encryption.hasher
import com.lightningkite.lightningserver.encryption.secretBasis
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.now
import com.lightningkite.uuid
import io.ktor.http.*
import kotlin.time.Duration
import kotlinx.datetime.*
import java.util.*

class OauthProofEndpoints(
    path: ServerPath,
    proofHasher: () -> SecureHasher = secretBasis.hasher("proof"),
    val provider: OauthProviderInfo,
    val credentials: () -> OauthProviderCredentials,
    val continueUiAuthUrl: ()->String
) : ServerPathGroup(path), Authentication.ExternalProofMethod {

    init {
        Authentication.register(this)
    }

    override val info: ProofMethodInfo = ProofMethodInfo(
        via = provider.identifierName,
        property = "email",
        strength = 10
    )

    @Suppress("UNCHECKED_CAST")
    val callback = path("callback").oauthCallback<UUID>(
        oauthProviderInfo = provider,
        credentials = credentials
    ) { response, uuid ->
        val profile = provider.getProfile(response)
        val email = profile.email ?: throw BadRequestException("No email was found for this profile.")
        HttpResponse.redirectToGet(continueUiAuthUrl() + "?proof=${Serialization.json.encodeToString(Proof.serializer(), proofHasher().makeProof(
            info = info,
            property = "email",
            value = email,
            at = now()
        )).encodeURLQueryComponent()}")
    }
    override val indirectLink: ServerPath = path("open").get.handler {
        HttpResponse.redirectToGet(callback.loginUrl(uuid()))
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
            callback.loginUrl(uuid())
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
            callback.loginUrl(uuid(), loginHint = ensureEmail)
        }
    )
}