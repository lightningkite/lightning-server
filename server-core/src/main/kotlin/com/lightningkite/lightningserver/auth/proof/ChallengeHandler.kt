package com.lightningkite.lightningserver.auth.proof

import com.lightningkite.lightningserver.cache.Cache
import com.lightningkite.lightningserver.cache.get
import com.lightningkite.lightningserver.cache.set
import com.lightningkite.lightningserver.exceptions.NotFoundException
import java.security.SecureRandom
import java.util.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class ChallengeHandler(
    private val cache: () -> Cache,
    val keyPrefix: String,
    val length: Int = 64,
    val expiration: Duration = 5.minutes
) {
    private fun challengeCacheKey(challenge: String): String =
        "${keyPrefix}_passkey_register_challenge_${challenge.substring(0, 10)}"
    private fun subjectNameCacheKey(challenge: String): String =
        "${keyPrefix}_passkey_register_name_${challenge.substring(0, 10)}"
    private fun subjectIdCacheKey(challenge: String): String =
        "${keyPrefix}_passkey_register_id_${challenge.substring(0, 10)}"

    suspend fun establish(subjectName: String, subjectId: String): String {
        val challenge = generate()
        cache().set(challengeCacheKey(challenge), challenge, expiration)
        cache().set(subjectNameCacheKey(challenge), subjectName, expiration)
        cache().set(subjectIdCacheKey(challenge), subjectId, expiration)
        return challenge
    }

    fun generate(): String {
        val bytes = ByteArray(length)
        SecureRandom().nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    suspend fun assert(challenge: String): Pair<String, String> {
        cache().get<String>(challengeCacheKey(challenge))
            ?.takeIf { it == challenge }
            ?: throw NotFoundException(detail = "unknown-challenge", message = "Challenge has expired.")
        val subjectName = cache().get<String>(subjectNameCacheKey(challenge))
            ?: throw IllegalStateException("No saved subject name")
        val subjectId = cache().get<String>(subjectIdCacheKey(challenge))
            ?: throw IllegalStateException("No saved subject id")
        cache().remove(challengeCacheKey(challenge))
        cache().remove(subjectNameCacheKey(challenge))
        cache().remove(subjectIdCacheKey(challenge))
        return subjectName to subjectId
    }
}