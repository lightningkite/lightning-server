@file:UseContextualSerialization(Duration::class)
@file:OptIn(ExperimentalLightningServer::class)

package com.lightningkite.lightningserver.auth.proof

import com.lightningkite.lightningdb.ExperimentalLightningServer
import com.lightningkite.lightningserver.encryption.SecureHasher
import dev.turingcomplete.kotlinonetimepassword.HmacAlgorithm
import dev.turingcomplete.kotlinonetimepassword.OtpAuthUriBuilder
import dev.turingcomplete.kotlinonetimepassword.TimeBasedOneTimePasswordConfig
import dev.turingcomplete.kotlinonetimepassword.TimeBasedOneTimePasswordGenerator
import kotlinx.datetime.Instant
import kotlinx.serialization.UseContextualSerialization
import org.bouncycastle.util.encoders.Base32
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.*
import com.lightningkite.UUID
import kotlin.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

fun <ID: Comparable<ID>> OtpSecret(
    _id: ID,
    secret: ByteArray,
    label: String,
    issuer: String,
    config: TimeBasedOneTimePasswordConfig,
    active: Boolean,
) = OtpSecret<ID>(
    _id = _id,
    Base32.encode(secret).toString(Charsets.UTF_8),
    digits = config.codeDigits,
    label = label,
    issuer = issuer,
    period = when (config.timeStepUnit) {
        TimeUnit.NANOSECONDS -> config.timeStep.nanoseconds
        TimeUnit.MICROSECONDS -> config.timeStep.microseconds
        TimeUnit.MILLISECONDS -> config.timeStep.milliseconds
        TimeUnit.SECONDS -> config.timeStep.seconds
        TimeUnit.MINUTES -> config.timeStep.minutes
        TimeUnit.HOURS -> config.timeStep.hours
        TimeUnit.DAYS -> config.timeStep.days
        else -> throw IllegalArgumentException()
    },
    algorithm = when (config.hmacAlgorithm) {
        HmacAlgorithm.SHA1 -> OtpHashAlgorithm.SHA1
        HmacAlgorithm.SHA256 -> OtpHashAlgorithm.SHA256
        HmacAlgorithm.SHA512 -> OtpHashAlgorithm.SHA512
    },
    active = active,
)

val OtpSecret<*>.secret: ByteArray get() = Base32.decode(secretBase32)
val OtpSecret<*>.url: String
    get() = OtpAuthUriBuilder.forTotp(secretBase32.toByteArray())
        .label(label, issuer)
        .issuer(issuer)
        .digits(digits)
        .period(period.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        .algorithm(
            when (algorithm) {
                OtpHashAlgorithm.SHA1 -> HmacAlgorithm.SHA1
                OtpHashAlgorithm.SHA256 -> HmacAlgorithm.SHA256
                OtpHashAlgorithm.SHA512 -> HmacAlgorithm.SHA512
            }
        )
        .buildToString()
        .replace("+", "%20")
        .replace("/?", "?")
val OtpSecret<*>.config: TimeBasedOneTimePasswordConfig
    get() = TimeBasedOneTimePasswordConfig(
        timeStep = period.inWholeMilliseconds,
        timeStepUnit = TimeUnit.MILLISECONDS,
        codeDigits = digits,
        hmacAlgorithm = when (algorithm) {
            OtpHashAlgorithm.SHA1 -> HmacAlgorithm.SHA1
            OtpHashAlgorithm.SHA256 -> HmacAlgorithm.SHA256
            OtpHashAlgorithm.SHA512 -> HmacAlgorithm.SHA512
        }
    )
val OtpSecret<*>.generator: TimeBasedOneTimePasswordGenerator get() = TimeBasedOneTimePasswordGenerator(secret, config)
val OtpSecret<*>.code: String get() = generator.generate()


private fun signingInfo(
    via: String,
    property: String,
    value: String,
    strength: Int = 1,
    at: Instant,
): ByteArray = ByteArrayOutputStream().use {
    DataOutputStream(it).use {
        it.writeUTF(via)
        it.writeUTF(property)
        it.writeUTF(value)
        it.writeInt(strength)
        it.writeLong(at.toEpochMilliseconds())
    }
    it.toByteArray()
}

fun SecureHasher.makeProof(
    info: ProofMethodInfo,
    property: String,
    value: String,
    at: Instant,
): Proof = Proof(
    via = info.via,
    property = property,
    strength = info.strength,
    value = value,
    at = at,
    signature = Base64.getEncoder().encodeToString(sign(signingInfo(info.via, property, value, info.strength, at)))
)

fun SecureHasher.verify(proof: Proof): Boolean {
    return verify(proof.run { signingInfo(via, property, value, strength, at) }, Base64.getDecoder().decode(proof.signature))
}