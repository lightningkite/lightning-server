package com.lightningkite.lightningserver.auth.oauth

import com.lightningkite.lightningserver.encryption.SecureHasher
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration
import kotlinx.datetime.Instant
import java.util.*
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
data class OauthProviderCredentialsApple(
    val serviceId: String,
    val teamId: String,
    val keyId: String,
    val keyString: String
) {
    fun toOauthProviderCredentials(): OauthProviderCredentials = OauthProviderCredentials(
        id = serviceId,
        secret = generateJwt()
    )
    fun generateJwt(): String {
        return buildString {
            val withDefaults = Json { encodeDefaults = true; explicitNulls = false }
            append(
                Base64.getUrlEncoder().withoutPadding().encodeToString(withDefaults.encodeToString(buildJsonObject {
//                    put("typ", "JWT")
                    put("kid", keyId)
                    put("alg", "ES256")
                }).toByteArray())
            )
            append('.')
            val issuedAt = Clock.System.now().minus(1.days)
            append(
                Base64.getUrlEncoder().withoutPadding().encodeToString(
                    withDefaults.encodeToString(
                        buildJsonObject {
                            put("iss", teamId)
                            put("iat", issuedAt.toEpochMilliseconds().div(1000))
                            put("exp", issuedAt.plus(5.days).toEpochMilliseconds().div(1000))
                            put("aud", "https://appleid.apple.com")
                            put("sub", serviceId)
                        }
                    ).toByteArray()
                )
            )
            val soFar = this.toString()
            append('.')
            append(
                Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(SecureHasher.ECDSA256(keyString).sign(soFar.toByteArray()))
            )
        }
    }
}