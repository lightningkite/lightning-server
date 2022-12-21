package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.cache.CacheInterface
import com.lightningkite.lightningserver.cache.get
import com.lightningkite.lightningserver.cache.set
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.exceptions.NotFoundException
import java.security.SecureRandom
import java.time.Duration
import java.util.*

open class PinHandler(
    private val cache: () -> CacheInterface,
    val keyPrefix: String,
    val expiration: Duration = Duration.ofMinutes(15),
    val maxAttempts: Int = 5
) {
    private fun attemptCacheKey(uniqueIdentifier: String): String = "${keyPrefix}_pin_login_attempts_$uniqueIdentifier"
    private fun cacheKey(uniqueIdentifier: String): String = "${keyPrefix}_pin_login_$uniqueIdentifier"
    suspend fun generate(uniqueIdentifier: String): String {
        val pin = SecureRandom().nextInt(1000000).toString().padStart(6, '0')
        cache().set(cacheKey(uniqueIdentifier), pin.secureHash(), expiration)
        cache().set(attemptCacheKey(uniqueIdentifier), 0, expiration)
        return pin
    }
    suspend fun assert(uniqueIdentifier: String, pin: String) {
        val hashedPin = cache().get<String>(cacheKey(uniqueIdentifier))
            ?: throw NotFoundException(detail = "pin-expired", message = "PIN has expired.")
        val attempts = (cache().get<Int>(attemptCacheKey(uniqueIdentifier)) ?: 0) + 1
        if(attempts >= maxAttempts) {
            cache().remove(cacheKey(uniqueIdentifier))
            cache().remove(attemptCacheKey(uniqueIdentifier))
            throw NotFoundException(detail = "pin-expired", message = "PIN has expired.")
        }
        cache().add(attemptCacheKey(uniqueIdentifier), 1)
        if (!pin.checkHash(hashedPin)) throw BadRequestException(detail = "pin-incorrect", message ="Incorrect PIN.  ${maxAttempts - attempts} attempts remain.")
        cache().remove(cacheKey(uniqueIdentifier))
        cache().remove(attemptCacheKey(uniqueIdentifier))
    }
}

