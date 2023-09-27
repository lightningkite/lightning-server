@file:UseContextualSerialization(UUID::class)
package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.UUIDSerializer
import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.auth.proof.Proof
import com.lightningkite.lightningserver.testmodels.TestUser
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertTrue

class RequestAuthTest {
    @Serializable
    data class Sample2(override val _id: UUID = UUID.randomUUID()): HasId<UUID>
    @Test fun test(): Unit = runBlocking {
        val sample = RequestAuth(TestSettings.subject, UUID.randomUUID(), rawId = UUID.randomUUID(), issuedAt = Instant.now())
        val myAuth = AuthOption(AuthType<TestUser>())
        val otherAuth = AuthOption(AuthType<Sample2>())
        AuthOption(AuthType.any).accepts(sample)
        assertTrue(myAuth.accepts(sample))
        assertFalse(otherAuth.accepts(sample))
        assertTrue(noAuth.accepts(sample))
        assertTrue(anyAuth.accepts(sample))
    }
}