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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*

class TinyTokenFormatTest {
    @Serializable
    data class Sample(override val _id: UUID = UUID.randomUUID()): HasId<UUID>
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
    @Test fun test() {
        TestSettings
        val format = TinyTokenFormat(SecureHasherSettings())
        val a = RequestAuth<Sample>(subject, UUID.randomUUID(), UUID.randomUUID(), Instant.now().truncatedTo(ChronoUnit.SECONDS), setOf("test", "test2"), thirdParty = "thirdparty")
        assertEquals(a, format.read(subject, format.create(subject, a).also { println(it) }))
        assertNull(format.read(subject, format.create(subject, a).let { it.dropLast(1).plus(it.last().plus(1)) }))
    }
}