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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.*
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
    val keyString: String
) {
    context(_: ServerRuntime)
    public fun toOauthProviderCredentials(): OauthProviderCredentials = OauthProviderCredentials(
        id = serviceId,
        secret = generateJwt()
    )

    @OptIn(ExperimentalEncodingApi::class)
    context(_: ServerRuntime)
    public fun generateJwt(): String {
        return buildString {
            val withDefaults = Json { encodeDefaults = true; explicitNulls = false }
            append(
                Base64.UrlSafe.encode(withDefaults.encodeToString(buildJsonObject {
//                    put("typ", "JWT")
                    put("kid", keyId)
                    put("alg", "ES256")
                }).toByteArray()).trimEnd('=')
            )
            append('.')
            val issuedAt = now().minus(1.days)
            append(
                Base64.UrlSafe.encode(
                    withDefaults.encodeToString(
                        buildJsonObject {
                            put("iss", teamId)
                            put("iat", issuedAt.toEpochMilliseconds().div(1000))
                            put("exp", issuedAt.plus(5.days).toEpochMilliseconds().div(1000))
                            put("aud", "https://appleid.apple.com")
                            put("sub", serviceId)
                        }
                    ).toByteArray()
                ).trimEnd('=')
            )
            val soFar = this.toString()
            append('.')
            
            // Parse the ECDSA P-256 private key and sign
            // The cryptography library API: get ECDSA algorithm, then get key decoder with curve only
            val ecdsaAlgorithm = CryptographyProvider.Default.get(ECDSA)
            val privateKeyDecoder = ecdsaAlgorithm.privateKeyDecoder(EC.Curve.P256)
            val privateKey = privateKeyDecoder.decodeFromByteArrayBlocking(EC.PrivateKey.Format.PEM, keyString.toByteArray())

            // We only have private key, but KeyPair interface requires both. Create a minimal public key.
            val publicKeyDecoder = ecdsaAlgorithm.publicKeyDecoder(EC.Curve.P256)
            val publicKey = publicKeyDecoder.decodeFromByteArrayBlocking(EC.PublicKey.Format.DER, ByteArray(0))

            @OptIn(dev.whyoleg.cryptography.CryptographyProviderApi::class)
            val keyPair = object : ECDSA.KeyPair {
                override val privateKey = privateKey
                override val publicKey = publicKey
            }
            val signer = keyPair.ES256()
            
            append(
                Base64.UrlSafe.encode(signer.signBlocking(soFar.toByteArray())).trimEnd('=')
            )
        }
    }
}