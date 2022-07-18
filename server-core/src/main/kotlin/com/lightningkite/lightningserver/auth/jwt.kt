package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.generalSettings
import io.ktor.http.content.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.getContextualDescriptor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import java.io.StringReader
import java.security.Signature
import java.security.interfaces.ECPrivateKey
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
    val userId: String? = null
)

interface SecureHasher {
    val name: String
    fun sign(bytes: ByteArray):ByteArray
    fun verify(bytes: ByteArray, signature: ByteArray): Boolean {
        return signature.contentEquals(sign(bytes))
    }

    class HS256(val secret: ByteArray): SecureHasher {
        override val name: String = "HS256"
        override fun sign(bytes: ByteArray): ByteArray {
            return Mac.getInstance("HmacSHA256").apply {
                init(SecretKeySpec(secret, "HmacSHA256"))
            }.doFinal(bytes)
        }
    }
    class ECDSA256(val privateKey: String): SecureHasher {
        override val name: String = "ECDSA256"
        val pk = run {
            val pk = JcaPEMKeyConverter().getPrivateKey(
                PEMParser(
                StringReader(
                    """
                            -----BEGIN PRIVATE KEY-----
                            ${privateKey.replace(" ", "")}
                            -----END PRIVATE KEY-----
                        """.trimIndent()
                )
            ).use { it.readObject() as PrivateKeyInfo })
            pk as ECPrivateKey
        }
        override fun sign(bytes: ByteArray): ByteArray {
            return Signature.getInstance("SHA1withECDSA").apply {
                initSign(pk)
                update(bytes)
            }.sign()
        }

        override fun verify(bytes: ByteArray, signature: ByteArray): Boolean {
            return Signature.getInstance("SHA1withECDSA").apply {
                initSign(pk)
                update(bytes)
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
    issuedAt: Instant = Instant.now()
): String = buildString {
    val withDefaults = Json(this@encodeJwt) { encodeDefaults = true; explicitNulls = false }
    append(Base64.getUrlEncoder().encodeToString(withDefaults.encodeToString(JwtHeader()).toByteArray()))
    append('.')
    append(Base64.getUrlEncoder().encodeToString(withDefaults.encodeToString(JwtClaims(
        iss = issuer,
        aud = audience,
        iat = issuedAt.toEpochMilli() / 1000,
        sub = if (serializer.isPrimitive())
            (encodeToJsonElement(serializer, subject) as JsonPrimitive).content
        else
            encodeToString(serializer, subject),
        exp = issuedAt.toEpochMilli() / 1000 + expire.seconds
    )).toByteArray()))
    val soFar = this.toString()
    append('.')
    append(Base64.getUrlEncoder().encodeToString(hasher.sign(soFar.toByteArray())))
}

class JwtException(message: String): Exception(message)

fun <T> Json.decodeJwt(
    hasher: SecureHasher,
    serializer: KSerializer<T>,
    token: String,
    requireAudience: String? = generalSettings().publicUrl
): T {
    val parts = token.split('.')
    if(parts.size != 3) throw JwtException("JWT does not have three parts.  This JWT is either missing pieces, corrupt, or not a JWT.")
    val signature = Base64.getUrlDecoder().decode(parts[2])
    if(!hasher.verify(token.substringBeforeLast('.').toByteArray(), signature)) throw JwtException("JWT Signature is incorrect.")
    val header: JwtHeader = decodeFromString(Base64.getUrlDecoder().decode(parts[0]).toString(Charsets.UTF_8))
    val claims: JwtClaims = decodeFromString(Base64.getUrlDecoder().decode(parts[1]).toString(Charsets.UTF_8))
    val textSubject = claims.sub ?: claims.userId ?: throw JwtException("JWT does not have a subject.")
    if(System.currentTimeMillis() / 1000L > claims.exp) throw JwtException("JWT has expired.")
    requireAudience?.let { if(claims.aud != it) throw JwtException("JWT has expired.") }
    return if (serializer.isPrimitive())
        decodeFromJsonElement(
            serializer,
            JsonPrimitive(textSubject)
        )
    else
        decodeFromString(serializer, textSubject)
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