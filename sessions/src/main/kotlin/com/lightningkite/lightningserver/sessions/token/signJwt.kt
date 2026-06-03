package com.lightningkite.lightningserver.sessions.token

import com.lightningkite.lightningserver.encryption.Signer
import com.lightningkite.lightningserver.encryption.sign
import com.lightningkite.lightningserver.runtime.ServerRuntime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Signs [claims] into a compact JWS (a JWT: `base64url(header).base64url(payload).base64url(signature)`).
 *
 * The JWT header's `alg` is taken from the signer's [Signer.name], and `kid` from [keyId] when given.
 * Defaults are encoded and nulls omitted, matching JWT conventions.
 *
 * This is the single JWT-assembly primitive used both for Lightning Server session tokens
 * (see [JwtTokenFormat]) and for OpenID Connect ID tokens.
 *
 * @param claims The payload to sign
 * @param serializer Serializer for [claims]
 * @param keyId Optional JWKS key id placed in the header `kid` (used by OpenID providers for rotation)
 */
context(server: ServerRuntime)
@OptIn(ExperimentalEncodingApi::class)
public suspend fun <T> Signer.signJwt(
    claims: T,
    serializer: KSerializer<T>,
    keyId: String? = null,
): String {
    val json = Json(server.internalSerialization.json) { encodeDefaults = true; explicitNulls = false }
    val encoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
    return buildString {
        append(encoder.encode(json.encodeToString(JwtHeader.serializer(), JwtHeader(alg = name, kid = keyId)).encodeToByteArray()))
        append('.')
        append(encoder.encode(json.encodeToString(serializer, claims).encodeToByteArray()))
        val signature = encoder.encode(sign(this.toString().encodeToByteArray()))
        append('.')
        append(signature)
    }
}
