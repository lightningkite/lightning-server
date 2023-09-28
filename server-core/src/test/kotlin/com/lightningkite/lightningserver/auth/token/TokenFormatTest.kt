@file:UseContextualSerialization(UUID::class)
package com.lightningkite.lightningserver.auth.token

import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.auth.RequestAuth
import com.lightningkite.lightningserver.encryption.TokenException
import com.lightningkite.lightningserver.testmodels.TestUser
import kotlinx.coroutines.*
import kotlinx.serialization.UseContextualSerialization
import org.junit.Assert
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import kotlin.test.assertFailsWith

abstract class TokenFormatTest {

    abstract fun format(expiration: Duration = Duration.ofMinutes(5)): TokenFormat

    @OptIn(DelicateCoroutinesApi::class)
    val sampleAuth = GlobalScope.async(start = CoroutineStart.LAZY) {
        RequestAuth<TestUser>(
            TestSettings.subjectHandler,
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
        Assert.assertEquals(sampleAuth.await(), format.read(TestSettings.subjectHandler, format.create(TestSettings.subjectHandler, sampleAuth.await()).also { println(it) }))
    }
    @Test
    fun testDifferentHashFails(): Unit = runBlocking {
        assertFailsWith<TokenException> {
            format().read(TestSettings.subjectHandler, format().create(TestSettings.subjectHandler, sampleAuth.await()))
        }
    }

    @Test fun expires(): Unit = runBlocking {
        assertFailsWith<TokenException> {
            val format = format(Duration.ofMillis(1))
            val created = format.create(TestSettings.subjectHandler, sampleAuth.await())
            Thread.sleep(1)
            format.read(TestSettings.subjectHandler, created)
        }
    }
}