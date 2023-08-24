package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.settings.Pluggable
import kotlinx.serialization.Serializable
import java.util.*
import kotlin.random.Random

@Serializable
data class SecureHasherSettings(
    val type: String = "HS256",
    val secret: String = Base64.getEncoder().encodeToString(Random.nextBytes(24)),
): ()-> SecureHasher {
    companion object: Pluggable<SecureHasherSettings, SecureHasher>() {
        init {
            register("HS256") {
                SecureHasher.HS256(Base64.getDecoder().decode(it.secret))
            }
            register("ECDSA256") {
                SecureHasher.ECDSA256(it.secret)
            }
        }
    }
    override fun invoke(): SecureHasher = parse(type, this)
}