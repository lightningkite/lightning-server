package com.lightningkite.lightningserver.sessions.openid

import com.lightningkite.lightningserver.ExperimentalOauthProviderApi
import kotlinx.serialization.Serializable
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import kotlin.io.encoding.Base64

/**
 * The RSA key that signs ID tokens (RS256), as base64 PKCS#8, so it can be a setting next to the secret basis.
 *
 * Persist it: a key made at startup changes on every restart and differs between instances, so clients couldn't verify
 * ID tokens against the published key set.  The public key needs no storage, since an RSA private key carries its
 * modulus and public exponent.
 */
@ExperimentalOauthProviderApi
@Serializable
@JvmInline
public value class OidcSigningKey(public val pkcs8: String) {
    public companion object {
        public fun generate(): OidcSigningKey {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            return OidcSigningKey(Base64.encode(pair.private.encoded))
        }
    }

    private val privateKey: RSAPrivateCrtKey
        get() = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.decode(pkcs8))) as? RSAPrivateCrtKey
            ?: throw IllegalArgumentException("The OIDC signing key must be an RSA private key with CRT parameters")

    internal fun sign(bytes: ByteArray): ByteArray =
        Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update(bytes)
            sign()
        }

    internal fun jwk(): JsonWebKey {
        val key = privateKey
        val n = unsigned(key.modulus)
        val e = unsigned(key.publicExponent)
        // RFC 7638 thumbprint, so a new key gets a new kid.
        val thumbprint = MessageDigest.getInstance("SHA-256").digest("{\"e\":\"$e\",\"kty\":\"RSA\",\"n\":\"$n\"}".encodeToByteArray())
        return JsonWebKey(kty = "RSA", use = "sig", kid = base64Url.encode(thumbprint), alg = "RS256", n = n, e = e)
    }
}

private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

private fun unsigned(value: BigInteger): String = base64Url.encode(value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray())
