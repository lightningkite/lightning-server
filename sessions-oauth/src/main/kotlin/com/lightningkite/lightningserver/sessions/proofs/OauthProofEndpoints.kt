package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.encryption.Signer
import com.lightningkite.lightningserver.encryption.signer
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.sessions.proofs.oauth.OauthProviderCredentials
import com.lightningkite.lightningserver.sessions.proofs.oauth.OauthProviderInfo
import com.lightningkite.lightningserver.typed.*
import kotlinx.datetime.*
import java.util.*

public class OauthProofEndpoints(
    proofSigner: RuntimeDeferred<Signer> = secretBasis.signer("proof"),
    private val provider: OauthProviderInfo,
    credentials: () -> OauthProviderCredentials,
    private val continueUiAuthUrl: ()->String
) : ServerBuilder(), ExternalProofMethod {

    init {
        proofMethods.register(this)
    }

    override val info: ProofMethodInfo = ProofMethodInfo(
        via = provider.identifierName,
        property = "email",
        strength = 10
    )

    val callback = path.path("callback").oauthCallback<UUID>(
        oauthProviderInfo = provider,
        credentials = credentials
    ) { response, _ ->
        val profile = provider.getProfile(response)
        val email = profile.email ?: throw BadRequestException("No email was found for this profile.")
        HttpResponse.redirectToGet(continueUiAuthUrl() + "?proof=${Serialization.json.encodeToString(Proof.serializer(), proofHasher().makeProof(
            info = info,
            property = "email",
            value = email,
            at = now()
        )).encodeURLQueryComponent()}&backend=${generalSettings().publicUrl.encodeURLQueryComponent()}")
    }

    override val indirectLink: ServerPath = path("open").get.handler {
        HttpResponse.redirectToGet(callback.loginUrl(UUID.random()))
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
            callback.loginUrl(UUID.random())
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
            callback.loginUrl(UUID.random(), loginHint = ensureEmail)
        }
    )
}