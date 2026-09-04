package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.encryption.checkAgainstHash
import com.lightningkite.lightningserver.encryption.secureHash
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.cache.*
import java.security.SecureRandom
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

public open class PinHandler(
    public val cache: Runtime<Cache>,
    private val keyPrefix: String,
    private val availableCharacters: List<Char> = ('A'..'Z').toList() - setOf('I', 'O'),
    private val length: Int = 6,
    public val expiration: Duration = 15.minutes,
    public val maxAttempts: Int = 5,
) {
    private val mixedCaseMode = availableCharacters.filter { it.isLetter() }.let {
        it.any { it.isUpperCase() } && it.any { it.isLowerCase() }
    }

    private fun attemptCacheKey(uniqueIdentifier: String): String = "${keyPrefix}_pin_login_attempts_$uniqueIdentifier"
    private fun cacheKey(uniqueIdentifier: String): String = "${keyPrefix}_pin_login_$uniqueIdentifier"
    private fun valueCacheKey(uniqueIdentifier: String): String = "${keyPrefix}_pin_login_value_$uniqueIdentifier"

    public data class PinAndKey(val pin: String, val key: String)

    context(server: ServerRuntime)
    public suspend fun establish(identifier: String): PinAndKey {
        val pin = generate()
        val key = Uuid.random().toString()
        val fixedPin = if (mixedCaseMode) pin else pin.lowercase()
        cache().set(cacheKey(key), fixedPin.secureHash(), expiration)
        cache().set(attemptCacheKey(key), 0, expiration)
        cache().set(valueCacheKey(key), identifier, expiration)
        return PinAndKey(pin, key)
    }

    public fun generate(): String {
        val r = SecureRandom()
        var pin: String
        do {
            pin = String(CharArray(length) { availableCharacters.get(r.nextInt(availableCharacters.size)) })
        } while (BadWordList.detectParanoid(pin))
        return pin
    }

    /**
     * The identifier a pending PIN was established for, or null when [key] is unknown or expired.
     *
     * Exists so that a failed [assert] can say *what* the attempt was against: the key on its own is a
     * random UUID, which cannot be counted or alerted on. Reads the same entry [assert] consumes on
     * success, without disturbing it.
     */
    context(server: ServerRuntime)
    public suspend fun pendingTarget(key: String): String? = cache().get<String>(valueCacheKey(key))

    context(server: ServerRuntime)
    public suspend fun assert(key: String, pin: String): String {
        val hashedPin = cache().get<String>(cacheKey(key))
            ?: throw NotFoundException(detail = "pin-expired", message = "PIN has expired.")
        val attempts = (cache().get<Int>(attemptCacheKey(key)) ?: 0) + 1
        if (attempts >= maxAttempts) {
            cache().remove(cacheKey(key))
            cache().remove(attemptCacheKey(key))
            throw NotFoundException(detail = "pin-expired", message = "PIN has expired.")
        }
        cache().add(attemptCacheKey(key), 1)
        val fixedPin = if (mixedCaseMode) pin else pin.lowercase()
        // Use generic error message to avoid leaking attempt count information
        if (!fixedPin.checkAgainstHash(hashedPin)) throw BadRequestException(
            detail = "pin-incorrect",
            message = "Incorrect PIN. Please try again."
        )
        cache().remove(cacheKey(key))
        cache().remove(attemptCacheKey(key))
        val value = cache().get<String>(valueCacheKey(key))!!
        cache().remove(valueCacheKey(key))
        return value
    }
}

