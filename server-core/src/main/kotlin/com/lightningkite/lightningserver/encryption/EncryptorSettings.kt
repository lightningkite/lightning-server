package com.lightningkite.lightningserver.encryption

import com.lightningkite.lightningserver.settings.Pluggable
import kotlinx.serialization.Serializable
import java.util.*
import kotlin.random.Random

@Serializable
data class EncryptorSettings(
    val type: String = "AES/GCM/NoPadding",
    val secret: String = Base64.getEncoder().encodeToString(Random.nextBytes(24)),
    val rawStringSecret: String? = null,
): ()-> Encryptor {
    companion object: Pluggable<EncryptorSettings, Encryptor>() {
        init {
            register("AES/GCM/NoPadding") {
                Encryptor.AesCbcPkcs5Padding(it.rawStringSecret?.toByteArray() ?: Base64.getDecoder().decode(it.secret))
            }
        }
    }
    override fun invoke(): Encryptor = parse(type, this)
}