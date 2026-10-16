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
 * Holds the credentials for Sign in with Apple.
 *
 * To obtain them, create an [App Identifier](https://developer.apple.com/account/resources/identifiers/list/bundleId) with the "Sign in with Apple" capability, and a [Service Identifier](https://developer.apple.com/account/resources/identifiers/list/serviceId) with it too, whose return URL is your auth URL + `/oauth/apple/callback`. Then create a [key](https://developer.apple.com/account/resources/authkeys/list) and download its `.p8`.
 *
 * @param serviceId The Service Identifier.
 * @param teamId Your team identifier.
 * @param keyId The key's ID.
 * @param keyString The contents of the `.p8` without the begin/end private key lines.
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