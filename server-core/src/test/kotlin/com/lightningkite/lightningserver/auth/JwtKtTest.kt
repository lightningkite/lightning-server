package com.lightningkite.lightningserver.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.generalSettings
import kotlinx.serialization.builtins.serializer
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.*

class JwtKtTest {
    @Test
    fun test() {
        TestSettings
        val secret = "THIS is My SECRET"
        val subject = "subby"

        val time = Instant.now()
        val auth0Checker = JWT
            .require(Algorithm.HMAC256(secret))
            .withIssuer(generalSettings().publicUrl)
            .build()
        val auth0Made = JWT.create()
            .withAudience(generalSettings().publicUrl)
            .withIssuer(generalSettings().publicUrl)
            .withIssuedAt(Date(time.toEpochMilli()))
            .withExpiresAt(Date(time.toEpochMilli() + 60_000L))
            .withSubject(subject)
            .sign(Algorithm.HMAC256(secret))
        println(auth0Made)
        val hasher = SecureHasher.HS256(secret.toByteArray())
        val myToken =
            Serialization.json.encodeJwt(hasher, String.serializer(), subject, Duration.ofSeconds(60), issuedAt = time)
        println(myToken)
        Serialization.json.decodeJwt(hasher, String.serializer(), auth0Made)
        auth0Checker.verify(myToken)
    }
}
