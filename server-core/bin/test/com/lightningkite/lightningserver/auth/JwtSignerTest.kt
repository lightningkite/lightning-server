package com.lightningkite.lightningserver.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.settings.generalSettings
import org.junit.Assert.*
import org.junit.Test

class JwtSignerTest {
    @Test
    fun primitiveTest() {
        TestSettings
        val signer = TestSettings.jwtSigner.invoke()
        val token = signer.token("test")
        println(token)
        val decoded = JWT
            .require(Algorithm.HMAC256(signer.secret))
            .withIssuer(generalSettings().publicUrl)
            .build()
            .verify(token)
        assertEquals("test", decoded.subject)
        signer.verify<String>(token)
    }

    @kotlinx.serialization.Serializable
    data class Compound(val a: Int, val b: Int)

    @Test
    fun complexTest() {
        TestSettings
        val signer = TestSettings.jwtSigner.invoke()
        val token = signer.token(Compound(1, 2))
        println(token)
        val decoded = JWT
            .require(Algorithm.HMAC256(signer.secret))
            .withIssuer(generalSettings().publicUrl)
            .build()
            .verify(token)
        println(decoded.subject)
        signer.verify<Compound>(token)
    }
}