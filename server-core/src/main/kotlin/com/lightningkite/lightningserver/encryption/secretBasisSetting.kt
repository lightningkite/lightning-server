package com.lightningkite.lightningserver.encryption

import com.lightningkite.lightningserver.settings.setting
import kotlinx.serialization.Serializable
import java.util.*
import kotlin.random.Random

@Serializable
@JvmInline value class SecretBasis(val string: String) {
    constructor():this(Base64.getEncoder().encodeToString(Random.nextBytes(24)))
    val bytes: ByteArray get() = Base64.getDecoder().decode(string)
    fun derive(key: String): ByteArray = bytes
}

val secretBasis = setting("secretBasis", SecretBasis())

fun SecretBasis.hasher(variant: String): SecureHasher = SecureHasher.HS256(this.derive(variant))
fun SecretBasis.encryptor(variant: String): Encryptor = Encryptor.AesCbcPkcs5Padding(this.derive(variant))
fun (()->SecretBasis).hasher(variant: String): ()->SecureHasher = { this().hasher(variant) }
fun (()->SecretBasis).encryptor(variant: String): ()->Encryptor = { this().encryptor(variant) }