package com.lightningkite.lightningserver.auth.old

import com.lightningkite.lightningserver.encryption.checkHash
import com.lightningkite.lightningserver.encryption.secureHash
import com.lightningkite.lightningserver.cache.Cache
import com.lightningkite.lightningserver.cache.get
import com.lightningkite.lightningserver.cache.set
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.utils.BadWordList
import java.security.SecureRandom
import java.time.Duration

open class PinHandler(
    private val cache: () -> Cache,
    val keyPrefix: String,
    val availableCharacters: List<Char> = ('0'..'9').toList(),
    val length: Int = 6,
    val expiration: Duration = Duration.ofMinutes(15),
    val maxAttempts: Int = 5
) {
    private val mixedCaseMode = availableCharacters.filter { it.isLetter() }.let {
        it.any { it.isUpperCase() } && it.any { it.isLowerCase() }
    }
    private fun attemptCacheKey(uniqueIdentifier: String): String = "${keyPrefix}_pin_login_attempts_$uniqueIdentifier"
    private fun cacheKey(uniqueIdentifier: String): String = "${keyPrefix}_pin_login_$uniqueIdentifier"
    suspend fun establish(uniqueIdentifier: String): String {
        val pin = generate()
        val fixedPin = if (mixedCaseMode) pin else pin.lowercase()
        cache().set(cacheKey(uniqueIdentifier), fixedPin.secureHash(), expiration)
        cache().set(attemptCacheKey(uniqueIdentifier), 0, expiration)
        return pin
    }

    fun generate(): String {
        val r = SecureRandom()
        var pin = ""
        do {
            pin = String(CharArray(length) { availableCharacters.get(r.nextInt(availableCharacters.size)) })
        } while (BadWordList.detectParanoid(pin))
        return pin
    }

    suspend fun assert(uniqueIdentifier: String, pin: String) {
        val hashedPin = cache().get<String>(cacheKey(uniqueIdentifier))
            ?: throw NotFoundException(detail = "pin-expired", message = "PIN has expired.")
        val attempts = (cache().get<Int>(attemptCacheKey(uniqueIdentifier)) ?: 0) + 1
        if (attempts >= maxAttempts) {
            cache().remove(cacheKey(uniqueIdentifier))
            cache().remove(attemptCacheKey(uniqueIdentifier))
            throw NotFoundException(detail = "pin-expired", message = "PIN has expired.")
        }
        cache().add(attemptCacheKey(uniqueIdentifier), 1)
        val fixedPin = if(mixedCaseMode) pin else pin.lowercase()
        if (!fixedPin.checkHash(hashedPin)) throw BadRequestException(
            detail = "pin-incorrect",
            message = "Incorrect PIN.  ${maxAttempts - attempts} attempts remain."
        )
        cache().remove(cacheKey(uniqueIdentifier))
        cache().remove(attemptCacheKey(uniqueIdentifier))
    }
}

