package com.lightningkite.lightningserver.guide.samples

// region oauth-imports
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.sessions.proofs.*
import com.lightningkite.lightningserver.sessions.proofs.oauth.*
import com.lightningkite.lightningserver.typed.sdk.module
import com.lightningkite.services.cache.*
import io.ktor.http.encodeURLQueryComponent
// endregion oauth-imports
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.settings.*
import com.lightningkite.lightningserver.typed.*

// region oauth-server
object SocialLoginServer : ServerBuilder() {
    val cache = setting("cache", Cache.Settings())
    val googleOauth = setting("googleOauth", OauthProviderCredentials(id = "", secret = ""))

    val proofGoogle = path.path("proof").path("google") module OauthProofEndpoints(
        provider = OauthProviderInfo.google,
        cache = cache,
        credentials = googleOauth,
        continueUiAuthUrl = { proof ->
            val json = serverRuntime.externalSerialization.json.encodeToString(Proof.serializer(), proof)
            "https://app.example.com/login/continue?proof=" + json.encodeURLQueryComponent()
        },
    )
}
// endregion oauth-server

fun oauthStartTest() = SocialLoginServer.testBlocking(settings = {
    cache set Cache.Settings("ram")
    googleOauth set OauthProviderCredentials(id = "my-client-id", secret = "my-secret")
}) {
    val url = proofGoogle.start.test(null, "alice@example.com")
    check(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"))
    check("client_id=my-client-id" in url)
    check("code_challenge=" in url)
    check("proof%2Fgoogle%2Fcallback" in url)
}
