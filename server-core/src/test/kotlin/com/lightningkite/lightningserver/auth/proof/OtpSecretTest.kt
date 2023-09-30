package com.lightningkite.lightningserver.auth.proof

import dev.turingcomplete.kotlinonetimepassword.HmacAlgorithm
import dev.turingcomplete.kotlinonetimepassword.TimeBasedOneTimePasswordConfig
import kotlinx.datetime.Clock
import com.lightningkite.now
import org.bouncycastle.util.encoders.Base32
import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom
import kotlinx.datetime.Instant
import kotlinx.datetime.toJavaInstant
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals

class OtpSecretTest {
    @Test
    fun test() {
        val secret = OtpSecret(
            _id = "Test",
            secret = ByteArray(32).also { SecureRandom.getInstanceStrong().nextBytes(it) },
            label = "test",
            issuer = "test",
            config = TimeBasedOneTimePasswordConfig(30_000, TimeUnit.MILLISECONDS, 6, HmacAlgorithm.SHA1)
        )
        assertContentEquals(Base32.decode(secret.secretBase32), secret.secret)
        assertEquals(Base32.encode(secret.secret).toString(Charsets.UTF_8), secret.secretBase32)
        println(secret.url)
        println(secret.secretBase32)
        repeat(20) {
            val time = now() + secret.period
            val code = secret.generator.generate(time.toJavaInstant())
            assertTrue(secret.generator.isValid(code, time.toJavaInstant()))
            println(code)
        }
    }
}