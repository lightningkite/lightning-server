@file:UseContextualSerialization(Duration::class)
package com.lightningkite.lightningserver.auth.proof

import com.lightningkite.lightningdb.GenerateDataClassPaths
import com.lightningkite.lightningdb.HasId
import dev.turingcomplete.kotlinonetimepassword.HmacAlgorithm
import dev.turingcomplete.kotlinonetimepassword.OtpAuthUriBuilder
import dev.turingcomplete.kotlinonetimepassword.TimeBasedOneTimePasswordConfig
import dev.turingcomplete.kotlinonetimepassword.TimeBasedOneTimePasswordGenerator
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import org.bouncycastle.util.encoders.Base32
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

@Serializable
@GenerateDataClassPaths
data class OtpSecret<ID : Comparable<ID>>(
    @Contextual override val _id: ID,
    val secretBase32: String,
    val label: String,
    val issuer: String,
    val period: Duration,
    val digits: Int,
    val algorithm: OtpHashAlgorithm
) : HasId<ID> {
    constructor(
        _id: ID,
        secret: ByteArray,
        label: String,
        issuer: String,
        config: TimeBasedOneTimePasswordConfig
    ) : this(
        _id = _id,
        Base32.encode(secret).toString(Charsets.UTF_8),
        digits = config.codeDigits,
        label = label,
        issuer = issuer,
        period = Duration.of(
            config.timeStep, when (config.timeStepUnit) {
                TimeUnit.NANOSECONDS -> ChronoUnit.NANOS
                TimeUnit.MICROSECONDS -> ChronoUnit.MICROS
                TimeUnit.MILLISECONDS -> ChronoUnit.MILLIS
                TimeUnit.SECONDS -> ChronoUnit.SECONDS
                TimeUnit.MINUTES -> ChronoUnit.MINUTES
                TimeUnit.HOURS -> ChronoUnit.HOURS
                TimeUnit.DAYS -> ChronoUnit.DAYS
                else -> throw IllegalArgumentException()
            }
        ),
        algorithm = when (config.hmacAlgorithm) {
            HmacAlgorithm.SHA1 -> OtpHashAlgorithm.SHA1
            HmacAlgorithm.SHA256 -> OtpHashAlgorithm.SHA256
            HmacAlgorithm.SHA512 -> OtpHashAlgorithm.SHA512
        }
    )

    val secret: ByteArray get() = Base32.decode(secretBase32)
    val url: String
        get() = OtpAuthUriBuilder.forTotp(secretBase32.toByteArray())
            .label(label, issuer)
            .issuer(issuer)
            .digits(digits)
            .period(period.toMillis(), TimeUnit.MILLISECONDS)
            .algorithm(when (algorithm) {
                OtpHashAlgorithm.SHA1 -> HmacAlgorithm.SHA1
                OtpHashAlgorithm.SHA256 -> HmacAlgorithm.SHA256
                OtpHashAlgorithm.SHA512 -> HmacAlgorithm.SHA512
            })
            .buildToString()
            .replace("+", "%20")
            .replace("/?", "?")
    val config: TimeBasedOneTimePasswordConfig
        get() = TimeBasedOneTimePasswordConfig(
            timeStep = period.toMillis(),
            timeStepUnit = TimeUnit.MILLISECONDS,
            codeDigits = digits,
            hmacAlgorithm = when (algorithm) {
                OtpHashAlgorithm.SHA1 -> HmacAlgorithm.SHA1
                OtpHashAlgorithm.SHA256 -> HmacAlgorithm.SHA256
                OtpHashAlgorithm.SHA512 -> HmacAlgorithm.SHA512
            }
        )
    val generator: TimeBasedOneTimePasswordGenerator get() = TimeBasedOneTimePasswordGenerator(secret, config)
    val code: String get() = generator.generate()
}
