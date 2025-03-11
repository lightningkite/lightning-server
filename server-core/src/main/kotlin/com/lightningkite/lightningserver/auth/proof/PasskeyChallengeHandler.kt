package com.lightningkite.lightningserver.auth.proof

import com.lightningkite.lightningserver.cache.Cache
import com.lightningkite.lightningserver.cache.get
import com.lightningkite.lightningserver.cache.set
import com.lightningkite.lightningserver.exceptions.NotFoundException
import java.security.SecureRandom
import java.util.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class PasskeyChallengeHandler(
    private val cache: () -> Cache,
    val keyPrefix: String,
    val challengeLength: Int = 64,
    val expiration: Duration = 5.minutes
) {

    private fun registerChallengeCacheKey(challenge: String): String =
        "${keyPrefix}_passkey_register_challenge_${challenge.substring(0, 10)}"
    private fun subjectNameCacheKey(challenge: String): String =
        "${keyPrefix}_passkey_register_name_${challenge.substring(0, 10)}"
    private fun subjectIdCacheKey(challenge: String): String =
        "${keyPrefix}_passkey_register_id_${challenge.substring(0, 10)}"

    private fun loginChallengeCacheKey(challenge: String): String =
        "${keyPrefix}_passkey_login_challenge_${challenge.substring(0, 10)}"

    suspend fun establishForRegistration(subjectName: String, subjectId: String): String {
        val challenge = generate()
        cache().set(registerChallengeCacheKey(challenge), challenge, expiration)
        cache().set(subjectNameCacheKey(challenge), subjectName, expiration)
        cache().set(subjectIdCacheKey(challenge), subjectId, expiration)
        return challenge
    }

    suspend fun establishForLogin(): String {
        val challenge = generate()
        cache().set(loginChallengeCacheKey(challenge), challenge, expiration)
        return challenge
    }

    private fun generate(): String {
        val bytes = ByteArray(challengeLength)
        SecureRandom().nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    suspend fun assertForRegistration(challenge: String): Pair<String, String> {
        cache().get<String>(registerChallengeCacheKey(challenge))
            ?.takeIf { it == challenge }
            ?: throw NotFoundException(detail = "unknown-challenge", message = "Challenge has expired.")
        val subjectName = cache().get<String>(subjectNameCacheKey(challenge))
            ?: throw IllegalStateException("No saved subject name")
        val subjectId = cache().get<String>(subjectIdCacheKey(challenge))
            ?: throw IllegalStateException("No saved subject id")
        cache().remove(registerChallengeCacheKey(challenge))
        cache().remove(subjectNameCacheKey(challenge))
        cache().remove(subjectIdCacheKey(challenge))
        return subjectName to subjectId
    }

    suspend fun assertForLogin(challenge: String) {
        cache().get<String>(loginChallengeCacheKey(challenge))
            ?.takeIf { it == challenge }
            ?: throw NotFoundException(detail = "unknown-challenge", message = "Challenge has expired.")
    }
}