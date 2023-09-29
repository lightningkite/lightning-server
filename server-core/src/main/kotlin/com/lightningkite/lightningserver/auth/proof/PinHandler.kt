package com.lightningkite.lightningserver.auth.proof

import com.lightningkite.lightningserver.encryption.checkHash
import com.lightningkite.lightningserver.encryption.secureHash
import com.lightningkite.lightningserver.cache.Cache
import com.lightningkite.lightningserver.cache.get
import com.lightningkite.lightningserver.cache.set
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.utils.BadWordList
import com.lightningkite.uuid
import java.security.SecureRandom
import kotlin.time.Duration
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

open class PinHandler(
    private val cache: () -> Cache,
    val keyPrefix: String,
    val availableCharacters: List<Char> = ('0'..'9').toList(),
    val length: Int = 6,
    val expiration: Duration = 15.minutes,
    val maxAttempts: Int = 5
) {
    private val mixedCaseMode = availableCharacters.filter { it.isLetter() }.let {
        it.any { it.isUpperCase() } && it.any { it.isLowerCase() }
    }
    private fun attemptCacheKey(uniqueIdentifier: String): String = "${keyPrefix}_pin_login_attempts_$uniqueIdentifier"
    private fun cacheKey(uniqueIdentifier: String): String = "${keyPrefix}_pin_login_$uniqueIdentifier"
    private fun valueCacheKey(uniqueIdentifier: String): String = "${keyPrefix}_pin_login_value_$uniqueIdentifier"

    data class PinAndKey(val pin: String, val key: String)

    suspend fun establish(identifier: String): PinAndKey {
        val pin = generate()
        val key = uuid().toString()
        val fixedPin = if (mixedCaseMode) pin else pin.lowercase()
        cache().set(cacheKey(key), fixedPin.secureHash(), expiration)
        cache().set(attemptCacheKey(key), 0, expiration)
        cache().set(valueCacheKey(key), identifier, expiration)
        return PinAndKey(pin, key)
    }

    fun generate(): String {
        val r = SecureRandom()
        var pin = ""
        do {
            pin = String(CharArray(length) { availableCharacters.get(r.nextInt(availableCharacters.size)) })
        } while (BadWordList.detectParanoid(pin))
        return pin
    }

    suspend fun assert(key: String, pin: String): String {
        val hashedPin = cache().get<String>(cacheKey(key))
            ?: throw NotFoundException(detail = "pin-expired", message = "PIN has expired.")
        val attempts = (cache().get<Int>(attemptCacheKey(key)) ?: 0) + 1
        if (attempts >= maxAttempts) {
            cache().remove(cacheKey(key))
            cache().remove(attemptCacheKey(key))
            throw NotFoundException(detail = "pin-expired", message = "PIN has expired.")
        }
        cache().add(attemptCacheKey(key), 1)
        val fixedPin = if(mixedCaseMode) pin else pin.lowercase()
        if (!fixedPin.checkHash(hashedPin)) throw BadRequestException(
            detail = "pin-incorrect",
            message = "Incorrect PIN.  ${maxAttempts - attempts} attempts remain."
        )
        cache().remove(cacheKey(key))
        cache().remove(attemptCacheKey(key))
        val value = cache().get<String>(valueCacheKey(key))!!
        cache().remove(valueCacheKey(key))
        return value
    }
}

