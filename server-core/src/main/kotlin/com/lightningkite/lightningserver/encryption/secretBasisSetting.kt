package com.lightningkite.lightningserver.encryption

import com.lightningkite.lightningserver.settings.setting
import kotlinx.serialization.Serializable
import java.util.*
import kotlin.random.Random

@Serializable
@JvmInline value class SecretBasis(val string: String) {
    companion object {
        const val BITS = 512
        const val BYTES = BITS / 8
        const val BASE64_CHARS = 66
    }
    constructor():this(Base64.getEncoder().encodeToString(Random.nextBytes(BYTES)))
    val bytes: ByteArray get() = Base64.getDecoder().decode(string).sliceArray(0 until BYTES)
    fun derive(key: String): ByteArray = SecureHasher.HS512(bytes).sign(key.toByteArray())
}

val secretBasis = setting("secretBasis", SecretBasis())

fun SecretBasis.hasher(variant: String): SecureHasher = SecureHasher.HS512(this.derive(variant))
fun SecretBasis.encryptor(variant: String): Encryptor = Encryptor.AesCbcPkcs5Padding(this.derive(variant).sliceArray(0 until 32))
fun (()->SecretBasis).hasher(variant: String): ()->SecureHasher = { this().hasher(variant) }
fun (()->SecretBasis).encryptor(variant: String): ()->Encryptor = { this().encryptor(variant) }