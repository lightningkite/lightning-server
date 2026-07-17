package com.lightningkite.lightningserver.sessions.proofs.oauth

import com.lightningkite.lightningserver.encryption.ES256
import com.lightningkite.lightningserver.encryption.signBlocking
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDSA
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration.Companion.days

/**
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
@Serializable
public data class OauthProviderCredentialsApple(
    val serviceId: String,
    val teamId: String,
    val keyId: String,
    val keyString: String,
) {
    context(_: ServerRuntime)
    public fun toOauthProviderCredentials(): OauthProviderCredentials = OauthProviderCredentials(
        id = serviceId,
        secret = generateJwt()
    )

    context(_: ServerRuntime)
    public fun generateJwt(): String {
        val withDefaults = Json { encodeDefaults = true; explicitNulls = false }

        val header = Base64.UrlSafe.encode(withDefaults.encodeToString(buildJsonObject {
            put("kid", keyId)
            put("alg", "ES256")
        }).toByteArray()).trimEnd('=')

        val issuedAt = now().minus(1.days)
        val payload = Base64.UrlSafe.encode(withDefaults.encodeToString(buildJsonObject {
            put("iss", teamId)
            put("iat", issuedAt.toEpochMilliseconds().div(1000))
            put("exp", issuedAt.plus(5.days).toEpochMilliseconds().div(1000))
            put("aud", "https://appleid.apple.com")
            put("sub", serviceId)
        }).toByteArray()).trimEnd('=')

        val unsignedToken = "$header.$payload"

        // 1. Ensure valid PEM formatting for the Apple .p8 key
        val formattedPem = if (keyString.contains("-----BEGIN")) keyString else """
        -----BEGIN PRIVATE KEY-----
        $keyString
        -----END PRIVATE KEY-----
    """.trimIndent()

        // 2. Decode the private key
        val ecdsaAlgorithm = CryptographyProvider.Default.get(ECDSA)
        val privateKeyDecoder = ecdsaAlgorithm.privateKeyDecoder(EC.Curve.P256)
        val privateKey = privateKeyDecoder.decodeFromByteArrayBlocking(
            EC.PrivateKey.Format.PEM,
            formattedPem.toByteArray()
        )

        // 3. Create a generator directly from privateKey (using RAW/IEEE_P1363 format for JWTs)
        val generator = privateKey.signatureGenerator(SHA256, ECDSA.SignatureFormat.RAW)
        val signature = generator.generateSignatureBlocking(unsignedToken.toByteArray())

        val encodedSignature = Base64.UrlSafe.encode(signature).trimEnd('=')

        return "$unsignedToken.$encodedSignature"
    }
}