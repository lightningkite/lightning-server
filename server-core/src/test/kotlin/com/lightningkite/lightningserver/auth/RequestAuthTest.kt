@file:UseContextualSerialization(UUID::class)
package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.UUIDSerializer
import com.lightningkite.lightningserver.auth.proof.Proof
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
    data class Sample(override val _id: UUID = UUID.randomUUID()): HasId<UUID>
    @Serializable
    data class Sample2(override val _id: UUID = UUID.randomUUID()): HasId<UUID>
    val subject = object: Authentication.SubjectHandler<Sample, UUID> {
        override val name: String
            get() = "sample"
        override val idProofs: Set<String>
            get() = setOf()
        override val authType: AuthType
            get() = AuthType<Sample>()
        override val applicableProofs: Set<String>
            get() = setOf()

        override suspend fun authenticate(vararg proofs: Proof): Authentication.AuthenticateResult<Sample, UUID>? = null

        override val idSerializer: KSerializer<UUID>
            get() = UUIDSerializer
        override val subjectSerializer: KSerializer<Sample>
            get() = Sample.serializer()

        override suspend fun fetch(id: UUID): Sample =  Sample(id)

    }
    @Test fun test(): Unit = runBlocking {
        val sample = RequestAuth(subject, UUID.randomUUID(), issuedAt = Instant.now())
        val myAuth = AuthOption(AuthType<Sample>())
        val otherAuth = AuthOption(AuthType<Sample2>())
        AuthOption(AuthType.any).accepts(sample)
        assertTrue(myAuth.accepts(sample))
        assertFalse(otherAuth.accepts(sample))
        assertTrue(noAuth.accepts(sample))
        assertTrue(anyAuth.accepts(sample))
    }
}