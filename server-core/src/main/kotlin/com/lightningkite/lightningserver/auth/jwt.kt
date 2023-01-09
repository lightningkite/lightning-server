package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.generalSettings
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.getContextualDescriptor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1OutputStream
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Serializable
data class JwtHeader(val typ: String = "JWT", val alg: String = "HS256")

@Serializable
data class JwtClaims(
    val iss: String? = null,
    val sub: String? = null,
    val aud: String? = null,
    val exp: Long,
    val nbf: Long? = null,
    val iat: Long = System.currentTimeMillis() / 1000L,
    val jti: String? = null,
    val userId: String? = null,
)

interface SecureHasher {
    val name: String
    fun sign(bytes: ByteArray): ByteArray
    fun verify(bytes: ByteArray, signature: ByteArray): Boolean {
        return signature.contentEquals(sign(bytes))
    }

    class HS256(val secret: ByteArray) : SecureHasher {
        override val name: String = "HS256"
        override fun sign(bytes: ByteArray): ByteArray {
            return Mac.getInstance("HmacSHA256").apply {
                init(SecretKeySpec(secret, "HmacSHA256"))
            }.doFinal(bytes)
        }
    }

    class ECDSA256(privateKey: String) : SecureHasher {
        override val name: String = "ECDSA256"
        val pk = KeyFactory.getInstance("EC").generatePrivate(
            PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKey.filter { !it.isWhitespace() }))
        )

//        override fun sign(bytes: ByteArray): ByteArray {
//            return Signature.getInstance("NONEwithECDSA").apply {
//                initSign(pk)
//                update(bytes)
//            }.sign()
//        }
//
//        override fun verify(bytes: ByteArray, signature: ByteArray): Boolean {
//            return Signature.getInstance("NONEwithECDSA").apply {
//                update(bytes)
//            }.verify(signature)
//        }
        override fun sign(bytes: ByteArray): ByteArray {
            return Signature.getInstance("SHA256withECDSA").apply {
                initSign(pk)
                update(bytes)
            }.sign().let {
                val seq = ASN1InputStream(it).use {
                    (it.readObject() as ASN1Sequence).toArray()
                }
                (seq[0] as ASN1Integer).positiveValue.toByteArray() + (seq[1] as ASN1Integer).positiveValue.toByteArray()
            }
        }

        override fun verify(bytes: ByteArray, signature: ByteArray): Boolean {
            return Signature.getInstance("SHA256withECDSA").apply {
                initSign(pk)
                val b = ByteArrayOutputStream()
                val o = ASN1OutputStream.create(b)
                try {
                    o.writeObject(DERSequence(arrayOf(
                        ASN1Integer(BigInteger(1, bytes.copyOfRange(0, 32))),
                        ASN1Integer(BigInteger(1, bytes.copyOfRange(32, 64))),
                    )))
                } finally {
                    o.close()
                }
                update(b.toByteArray())
            }.verify(signature)
        }
    }
}

fun <T> Json.encodeJwt(
    hasher: SecureHasher,
    serializer: KSerializer<T>,
    subject: T,
    expire: Duration,
    issuer: String = generalSettings().publicUrl,
    audience: String? = generalSettings().publicUrl,
    issuedAt: Instant = Instant.now(),
): String = encodeJwt(hasher, if (serializer.isPrimitive())
    (encodeToJsonElement(serializer, subject) as JsonPrimitive).content
else
    encodeToString(serializer, subject), expire, issuer, audience, issuedAt)

fun <T> Json.decodeJwt(
    hasher: SecureHasher,
    serializer: KSerializer<T>,
    token: String,
    requireAudience: String? = generalSettings().publicUrl,
): T = decodeJwt(hasher, token, requireAudience).let { textSubject ->
    if (serializer.isPrimitive())
        decodeFromJsonElement(
            serializer,
            JsonPrimitive(textSubject)
        )
    else
        decodeFromString(serializer, textSubject)
}

fun Json.encodeJwt(
    hasher: SecureHasher,
    subject: String,
    expire: Duration,
    issuer: String = generalSettings().publicUrl,
    audience: String? = generalSettings().publicUrl,
    issuedAt: Instant = Instant.now(),
): String = buildString {
    val withDefaults = Json(this@encodeJwt) { encodeDefaults = true; explicitNulls = false }
    append(Base64.getUrlEncoder().withoutPadding().encodeToString(withDefaults.encodeToString(JwtHeader()).toByteArray()))
    append('.')
    append(
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            withDefaults.encodeToString(
                JwtClaims(
                    iss = issuer,
                    aud = audience,
                    iat = issuedAt.toEpochMilli() / 1000,
                    sub = subject,
                    exp = issuedAt.toEpochMilli() / 1000 + expire.seconds
                )
            ).toByteArray()
        )
    )
    val soFar = this.toString()
    append('.')
    append(Base64.getUrlEncoder().withoutPadding().encodeToString(hasher.sign(soFar.toByteArray())))
}

open class JwtException(message: String) : Exception(message)
open class JwtFormatException(message: String) : JwtException(message)
open class JwtSignatureException(message: String) : JwtException(message)
open class JwtExpiredException(message: String) : JwtException(message)

fun Json.decodeJwt(
    hasher: SecureHasher,
    token: String,
    requireAudience: String? = generalSettings().publicUrl,
): String {
    val parts = token.split('.')
    if (parts.size != 3) throw JwtFormatException("JWT does not have three parts.  This JWT is either missing pieces, corrupt, or not a JWT.")
    val signature = Base64.getUrlDecoder().decode(parts[2])
    if (!hasher.verify(
            token.substringBeforeLast('.').toByteArray(),
            signature
        )
    ) throw JwtSignatureException("JWT Signature is incorrect.")
    val header: JwtHeader = decodeFromString(Base64.getUrlDecoder().decode(parts[0]).toString(Charsets.UTF_8))
    val claims: JwtClaims = decodeFromString(Base64.getUrlDecoder().decode(parts[1]).toString(Charsets.UTF_8))
    val textSubject = claims.sub ?: claims.userId ?: throw JwtFormatException("JWT does not have a subject.")
    if (System.currentTimeMillis() / 1000L > claims.exp) throw JwtExpiredException("JWT has expired.")
    requireAudience?.let { if (claims.aud != it) throw JwtFormatException("JWT for a different audience.") }
    return textSubject
}

private fun KSerializer<*>.isPrimitive(): Boolean {
    var current = this.descriptor
    while (true) {
        when (current.kind) {
            is PrimitiveKind -> return true
            SerialKind.CONTEXTUAL -> current =
                Serialization.json.serializersModule.getContextualDescriptor(current)!!

            else -> return false
        }
    }
}