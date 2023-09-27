@file:UseContextualSerialization(UUID::class)
package com.lightningkite.lightningserver.auth.token

import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.UUIDSerializer
import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.auth.AuthType
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.RequestAuth
import com.lightningkite.lightningserver.auth.proof.Proof
import com.lightningkite.lightningserver.encryption.SecureHasherSettings
import com.lightningkite.lightningserver.encryption.TokenException
import com.lightningkite.lightningserver.testmodels.TestUser
import kotlinx.coroutines.*
import kotlinx.serialization.ContextualSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlinx.serialization.builtins.serializer
import org.junit.Assert
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

abstract class TokenFormatTest {

    abstract fun format(expiration: Duration = Duration.ofMinutes(5)): TokenFormat

    @OptIn(DelicateCoroutinesApi::class)
    val sampleAuth = GlobalScope.async(start = CoroutineStart.LAZY) {
        RequestAuth<TestUser>(
            TestSettings.subject,
            UUID.randomUUID(),
            TestSettings.testUser.await()._id,
            Instant.now().truncatedTo(ChronoUnit.SECONDS),
            setOf("test", "test2"),
            thirdParty = "thirdparty"
        ).precache(listOf(TestSettings.EmailCacheKey))
    }

    @Test
    fun testCycle(): Unit = runBlocking {
        val format = format()
        Assert.assertEquals(sampleAuth.await(), format.read(TestSettings.subject, format.create(TestSettings.subject, sampleAuth.await()).also { println(it) }))
    }
    @Test
    fun testDifferentHashFails(): Unit = runBlocking {
        assertFailsWith<TokenException> {
            format().read(TestSettings.subject, format().create(TestSettings.subject, sampleAuth.await()))
        }
    }

    @Test fun expires(): Unit = runBlocking {
        assertFailsWith<TokenException> {
            val format = format(Duration.ofMillis(1))
            val created = format.create(TestSettings.subject, sampleAuth.await())
            Thread.sleep(1)
            format.read(TestSettings.subject, created)
        }
    }
}