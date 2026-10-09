@file:OptIn(ExperimentalLightningServer::class)

package com.lightningkite.lightningserver.sessions.proofs.extensions

import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.sessions.TotpSecret
import com.lightningkite.lightningserver.sessions.proofs.TotpHashAlgorithm
import com.lightningkite.services.data.ExperimentalLightningServer
import dev.turingcomplete.kotlinonetimepassword.*
import org.apache.commons.codec.binary.Base32
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

context(_: ServerRuntime)
public fun TotpSecret(
    subjectType: String,
    subjectId: String,
    secret: ByteArray,
    label: String,
    issuer: String,
    config: TimeBasedOneTimePasswordConfig,
): TotpSecret = TotpSecret(
    subjectId = subjectId,
    subjectType = subjectType,
    secretBase32 = Base32().encodeToString(secret),
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
    },
    algorithm = when (config.hmacAlgorithm) {
        HmacAlgorithm.SHA1 -> TotpHashAlgorithm.SHA1
        HmacAlgorithm.SHA256 -> TotpHashAlgorithm.SHA256
        HmacAlgorithm.SHA512 -> TotpHashAlgorithm.SHA512
    },
    establishedAt = now()
)

public val TotpSecret.secret: ByteArray get() = Base32().decode(secretBase32)

/**
 * The `otpauth://` URI for this secret. [accountName] becomes the account part of the URI's `Issuer:AccountName`
 * label, which authenticator apps show to tell accounts apart, so it should identify the subject (e.g. an email).
 */
public fun TotpSecret.url(accountName: String): String =
    OtpAuthUriBuilder.forTotp(secretBase32.toByteArray())
        .label(accountName, issuer)
        .issuer(issuer)
        .digits(digits)
        .period(period.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        .algorithm(
            when (algorithm) {
                TotpHashAlgorithm.SHA1 -> HmacAlgorithm.SHA1
                TotpHashAlgorithm.SHA256 -> HmacAlgorithm.SHA256
                TotpHashAlgorithm.SHA512 -> HmacAlgorithm.SHA512
            }
        )
        .buildToString()
        .replace("+", "%20")
        .replace("/?", "?")

public val TotpSecret.config: TimeBasedOneTimePasswordConfig
    get() = TimeBasedOneTimePasswordConfig(
        timeStep = period.inWholeMilliseconds,
        timeStepUnit = TimeUnit.MILLISECONDS,
        codeDigits = digits,
        hmacAlgorithm = when (algorithm) {
            TotpHashAlgorithm.SHA1 -> HmacAlgorithm.SHA1
            TotpHashAlgorithm.SHA256 -> HmacAlgorithm.SHA256
            TotpHashAlgorithm.SHA512 -> HmacAlgorithm.SHA512
        }
    )

public val TotpSecret.generator: TimeBasedOneTimePasswordGenerator
    get() = TimeBasedOneTimePasswordGenerator(
        secret,
        config
    )
public val TotpSecret.code: String get() = generator.generate()
