package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.bouncycastle.asn1.*
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPrivateKey
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.Security
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

interface SecureHasher {
    val name: String
    fun sign(bytes: ByteArray): ByteArray
    fun verify(bytes: ByteArray, signature: ByteArray): Boolean {
        return signature.contentEquals(sign(bytes))
    }

    companion object {
        init {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    class HS256(val secret: ByteArray) : SecureHasher {
        init {
            SecureHasher
        }

        override val name: String = "HS256"
        override fun sign(bytes: ByteArray): ByteArray {
            return Mac.getInstance("HmacSHA256").apply {
                init(SecretKeySpec(secret, "HmacSHA256"))
            }.doFinal(bytes)
        }
    }

    class ECDSA256(privateKey: String) : SecureHasher {
        init {
            SecureHasher
        }

        override val name: String = "ECDSA256"
        private val factory = KeyFactory.getInstance("ECDSA", "BC")
        val pk = factory.generatePrivate(
            PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKey.filter { !it.isWhitespace() }))
        )
        val public = run {
            val s = (pk as BCECPrivateKey).parameters
            val q = s.g.multiply((pk as BCECPrivateKey).d)
            factory.generatePublic(org.bouncycastle.jce.spec.ECPublicKeySpec(q, s))
        }

        override fun sign(bytes: ByteArray): ByteArray {
            return Signature.getInstance("SHA256withECDSA").apply {
                initSign(pk)
                update(bytes)
            }.sign().let {
                val seq = ASN1InputStream(it).use {
                    (it.readObject() as ASN1Sequence).toArray()
                }
                (seq[0] as ASN1Integer).value.signed32() + (seq[1] as ASN1Integer).value.signed32()
            }
        }

        override fun verify(bytes: ByteArray, signature: ByteArray): Boolean {
            return Signature.getInstance("SHA256withECDSA").apply {
                initVerify(public)
                update(bytes)
            }.verify(run {
                val b = ByteArrayOutputStream()
                val o = ASN1OutputStream.create(b)
                try {
                    o.writeObject(DERSequence(ASN1EncodableVector().apply {
                        add(ASN1Integer(fromSigned32(signature.copyOfRange(0, 32))))
                        add(ASN1Integer(fromSigned32(signature.copyOfRange(32, 64))))
                    }))
                } finally {
                    o.close()
                }
                b.toByteArray()
            })
        }
    }
}

fun SecureHasher.sign(string: String): String = Base64.getEncoder().encodeToString(sign(string.toByteArray()))
fun SecureHasher.verify(string: String, base64Signature: String): Boolean =
    verify(string.toByteArray(), Base64.getDecoder().decode(base64Signature))

fun SecureHasher.signJwt(claims: JwtClaims): String = buildString {
    val withDefaults = Json(Serialization.json) { encodeDefaults = true; explicitNulls = false }
    append(
        Base64.getUrlEncoder().withoutPadding().encodeToString(withDefaults.encodeToString(JwtHeader()).toByteArray())
    )
    append('.')
    @Suppress("UNCHECKED_CAST")
    append(
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            withDefaults.encodeToString(claims).toByteArray()
        )
    )
    val soFar = this.toString()
    append('.')
    val signature = Base64.getUrlEncoder().withoutPadding().encodeToString(sign(soFar.toByteArray()))
    append(signature)
    println("${this@signJwt} signing ${soFar} as $signature")
}
fun SecureHasher.verifyJwt(token: String, requiredAudience: String? = null): JwtClaims? {
    val parts = token.split('.')
    if (parts.size != 3) return null  // It's not a JWT, so we'll ignore it.
    val signature = Base64.getUrlDecoder().decode(parts[2])
    val header: JwtHeader = Serialization.json.decodeFromString(Base64.getUrlDecoder().decode(parts[0]).toString(Charsets.UTF_8))
    val claims: JwtClaims = Serialization.json.decodeFromString(Base64.getUrlDecoder().decode(parts[1]).toString(Charsets.UTF_8))
    requiredAudience?.let { if (claims.aud != it) return null }  // It's for someone else.  Ignore it.
    if (System.currentTimeMillis() / 1000L > claims.exp) throw JwtExpiredException("JWT has expired.")
    println("$this Verifying ${token.substringBeforeLast('.')} is $signature")
    if (!verify(
            token.substringBeforeLast('.').toByteArray(),
            signature
        )
    ) throw JwtSignatureException("JWT Signature is incorrect.")
    return claims
}

internal fun BigInteger.signed32(): ByteArray {
    return if (this >= BigInteger.ONE.shiftLeft(32 * 8 - 1)) {
        (this - BigInteger.ONE.shiftLeft(32 * 8)).toByteArray().let {
            ByteArray(32 - it.size) { 0xFF.toByte() } + it
        }
    } else {
        this.toByteArray().let {
            ByteArray(32 - it.size) { 0 } + it
        }
    }
}

internal fun fromSigned32(array: ByteArray): BigInteger {
    var raw = BigInteger(array)
    if (raw < BigInteger.ZERO) {
        raw += BigInteger.ONE.shiftLeft(32 * 8)
    }
    return raw
}