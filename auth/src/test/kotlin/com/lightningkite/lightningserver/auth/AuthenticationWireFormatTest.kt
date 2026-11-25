package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.data.Expiring
import com.lightningkite.lightningserver.data.SerializableCache
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.engine
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.services.database.HasId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

class AuthenticationWireFormatTest {
    @Serializable
    data class WireUser(override val _id: Uuid = Uuid.random()) : HasId<Uuid> {
        companion object : PrincipalType<WireUser, Uuid> {
            override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
            override val subjectSerializer: KSerializer<WireUser> = serializer()

            context(server: ServerRuntime)
            override suspend fun fetch(id: Uuid): WireUser = WireUser(id)
        }
    }

    object TestServer : ServerBuilder() {
        init {
            register(WireUser)
        }
    }

    // The request cache stores Authentication.CacheKey's value as an Expiring in the wire format; that serializer
    // is Authentication.serializer(NothingSerializer()).nullable, cast to Authentication<*>?.
    @Test
    fun authenticationCacheKeyEntryRoundTrips() = TestServer.test(settings = {}) {
        val wire = engine.internalSerialization.kotlinWireFormat
        val entrySerializer = Expiring.serializer(Authentication.CacheKey.serializer)
        val note = SerializableCache.Key<Any?, _>("note", String.serializer())

        val admin = Authentication(
            WireUser,
            id = Uuid.random(),
            sessionId = "session",
            sessionIssuedAt = Instant.fromEpochSeconds(1_700_000_000, 123),
            tokenIssuedAt = Instant.fromEpochSeconds(1_700_000_100),
            expiration = Instant.fromEpochSeconds(1_700_003_600),
            scopes = setOf(GrantedScope("api:read"), GrantedScope("api:write")),
        )
        admin.cache[note] = "cached on the admin"
        val masquerade = Authentication(
            WireUser,
            id = Uuid.random(),
            sessionId = null,
            sessionIssuedAt = admin.sessionIssuedAt,
            expiration = admin.expiration,
            fromMasquerade = admin,
            audience = "https://example.com/mcp",
        )

        for (value in listOf(admin, masquerade, null)) {
            val entry = Expiring<Authentication<*>?>(value, expiresAt = Instant.fromEpochSeconds(1_700_000_000) + 1.hours)
            val decoded = wire.decodeFromByteArray(entrySerializer, wire.encodeToByteArray(entrySerializer, entry))
            assertEquals(entry, decoded)
        }

        val decodedMasquerade = wire.decodeFromByteArray(
            Authentication.CacheKey.serializer,
            wire.encodeToByteArray(Authentication.CacheKey.serializer, masquerade),
        )!!
        assertEquals("cached on the admin", decodedMasquerade.fromMasquerade!!.cache[note])
    }
}
