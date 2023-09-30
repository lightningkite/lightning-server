@file:UseContextualSerialization(UUID::class)
package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.testmodels.TestUser
import com.lightningkite.uuid
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import com.lightningkite.now
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlinx.datetime.Instant
import com.lightningkite.UUID
import com.lightningkite.uuid
import kotlin.test.assertTrue

class RequestAuthTest {
    @Serializable
    data class Sample2(override val _id: UUID = uuid()): HasId<UUID>
    @Test fun test(): Unit = runBlocking {
        val sample = RequestAuth(TestSettings.subjectHandler, uuid(), rawId = uuid(), issuedAt = now())
        val myAuth = AuthOption(AuthType<TestUser>())
        val otherAuth = AuthOption(AuthType<Sample2>())
        AuthOption(AuthType.any).accepts(sample)
        assertTrue(myAuth.accepts(sample))
        assertFalse(otherAuth.accepts(sample))
        assertTrue(noAuth.accepts(sample))
        assertTrue(anyAuth.accepts(sample))
    }
}