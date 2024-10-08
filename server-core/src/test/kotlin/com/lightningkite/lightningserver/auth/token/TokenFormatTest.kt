@file:UseContextualSerialization(UUID::class)
package com.lightningkite.lightningserver.auth.token

import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.auth.RequestAuth
import com.lightningkite.lightningserver.encryption.TokenException
import com.lightningkite.lightningserver.metrics.roundTo
import com.lightningkite.lightningserver.testmodels.TestUser
import com.lightningkite.uuid
import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import com.lightningkite.now
import kotlinx.serialization.UseContextualSerialization
import org.junit.Assert
import org.junit.Test
import kotlin.time.Duration
import kotlinx.datetime.Instant
import java.util.*
import com.lightningkite.UUID
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

abstract class TokenFormatTest {

    abstract fun format(expiration: Duration = 5.minutes): TokenFormat

    @OptIn(DelicateCoroutinesApi::class)
    val sampleAuth = GlobalScope.async(start = CoroutineStart.LAZY) {
        RequestAuth<TestUser>(
            TestSettings.subjectHandler,
            uuid(),
            TestSettings.testUser.await()._id,
            now().roundTo(1.seconds),
            setOf("test", "test2"),
            thirdParty = "thirdparty"
        ).precache(TestSettings.subjectHandler.knownCacheTypes)
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
    @Test
    fun testMulticache(): Unit = runBlocking {
        val format = format()
        var auth = sampleAuth.await()
        Assert.assertEquals(auth, format.read(TestSettings.subjectHandler, format.create(TestSettings.subjectHandler, auth).also { println(it) }))
        repeat(100) {
            auth = auth.precache(TestSettings.subjectHandler.knownCacheTypes)
            Assert.assertEquals(auth, format.read(TestSettings.subjectHandler, format.create(TestSettings.subjectHandler, auth).also { println(it) }))
        }
    }

    @Test fun expires(): Unit = runBlocking {
        assertFailsWith<TokenException> {
            val format = format(1.milliseconds)
            val created = format.create(TestSettings.subjectHandler, sampleAuth.await())
            Thread.sleep(1)
            format.read(TestSettings.subjectHandler, created)
        }
    }
}