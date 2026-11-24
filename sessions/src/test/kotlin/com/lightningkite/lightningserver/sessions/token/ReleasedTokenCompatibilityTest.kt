package com.lightningkite.lightningserver.sessions.token

import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.data.Expiring
import com.lightningkite.lightningserver.data.SerializableCache
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.encryption.*
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.runtime.test.execute
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.services.database.HasId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.Test
import kotlin.io.encoding.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Authentications serialized by the released version (auth and sessions 1001.0.0, OldKotlinBytesFormat) must still
 * decode: clients hold access tokens for minutes, and many don't refresh on a 401.  Each fixture is built the way the
 * released code built it, independently of today's classes.
 *
 * Released masquerade fixtures are gone: tokens never carry `fromMasquerade` (a masquerade is applied per request
 * from a header), and OldKotlinBytesFormat can't read a nested released Authentication now that the class has a
 * trailing field the nested one lacks.  The AWS socket rows that did store masquerades are no longer read at all.
 */
class ReleasedTokenCompatibilityTest {
    @Serializable
    data class TestUser(override val _id: Uuid = Uuid.random()) : HasId<Uuid> {
        companion object : PrincipalType<TestUser, Uuid> {
            override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
            override val subjectSerializer: KSerializer<TestUser> = serializer()

            context(server: ServerRuntime)
            override suspend fun fetch(id: Uuid): TestUser = TestUser(id)
        }
    }

    object TestServer : ServerBuilder() {
        init {
            registerBasicMediaTypeCoders()
            register(TestUser)
        }
    }

    /** `Authentication`'s fields as released, in order, with the same defaults; `cache` serializes as this map. */
    @Serializable
    data class ReleasedAuthentication(
        val principalName: String,
        val rawId: String,
        val sessionId: String?,
        val sessionIssuedAt: Instant,
        val tokenIssuedAt: Instant? = null,
        val expiration: Instant? = null,
        val scopes: Set<GrantedScope> = setOf(GrantedScope.root),
        val fromMasquerade: ReleasedAuthentication? = null,
        val cache: Map<String, ByteArray> = mapOf(),
    )

    private val basis = SecretBasis()
    private val userId = Uuid.random()
    private val adminId = Uuid.random()
    private val note = SerializableCache.Key<Any?, String>("note", String.serializer())

    context(server: ServerRuntime)
    private fun releasedNoteBytes(text: String) = mapOf(
        note.id to server.internalSerialization.oldKotlinBytesFormat.encodeToByteArray(Expiring.serializer(String.serializer()), Expiring(text, expiresAt = null))
    )

    context(server: ServerRuntime)
    private fun released(now: Instant) = ReleasedAuthentication(
        principalName = "TestUser",
        rawId = TestUser.idString(userId),
        sessionId = "session-1",
        sessionIssuedAt = now,
        tokenIssuedAt = now,
        expiration = now + 5.minutes,
        scopes = setOf(GrantedScope("mcp")),
        cache = releasedNoteBytes("user's"),
    )

    context(server: ServerRuntime)
    private fun Authentication<TestUser>.assertIsTheReleasedOne() {
        assertEquals(userId, id)
        assertEquals("session-1", sessionId)
        assertEquals(setOf(GrantedScope("mcp")), scopes)
        assertEquals("user's", cache[note])
        assertNull(audience, "A released authentication is not bound to a resource")
        assertNull(fromMasquerade)
    }

    @Test
    fun `PrivateTinyTokenFormat reads a released token`() = runBlocking {
        TestServer.test({}) {
            val format = PrivateTinyTokenFormat(cipher = RuntimeDeferred.Cached { basis.cipher("tiny") })
            val token = execute {
                val bytes = serverRuntime.internalSerialization.oldKotlinBytesFormat.encodeToByteArray(ReleasedAuthentication.serializer(), released(now()))
                "TestUser/" + Base64.UrlSafe.encode(format.cipher.await().encrypt(bytes))
            }
            execute { format.read(TestUser, token)!!.assertIsTheReleasedOne() }
        }
    }

    @Test
    fun `PublicTinyTokenFormat reads a released token`() = runBlocking {
        TestServer.test({}) {
            val format = PublicTinyTokenFormat(hasher = RuntimeDeferred.Cached { basis.HS256("public-tiny") })
            val token = execute {
                val bytes = serverRuntime.internalSerialization.oldKotlinBytesFormat.encodeToByteArray(ReleasedAuthentication.serializer(), released(now()))
                "tt/TestUser/" + Base64.UrlSafe.encode(format.hasher.await().sign(bytes) + bytes)
            }
            execute { format.read(TestUser, token)!!.assertIsTheReleasedOne() }
        }
    }

    // A released token's cache entries are read in the old format, but a token made from that authentication carries
    // them in the new one.
    @Test
    fun `a token made from a released authentication carries its cache in the new format`() = runBlocking {
        TestServer.test({}) {
            val format = PublicTinyTokenFormat(hasher = RuntimeDeferred.Cached { basis.HS256("public-tiny") })
            val token = execute {
                val bytes = serverRuntime.internalSerialization.oldKotlinBytesFormat.encodeToByteArray(ReleasedAuthentication.serializer(), released(now()))
                "tt/TestUser/" + Base64.UrlSafe.encode(format.hasher.await().sign(bytes) + bytes)
            }
            execute {
                val releasedAuth = format.read(TestUser, token)!!
                assertEquals("user's", releasedAuth.cache[note])
                format.read(TestUser, format.create(TestUser, releasedAuth))!!.assertIsTheReleasedOne()
            }
        }
    }

    @Test
    fun `JwtTokenFormat reads a released token`() = runBlocking {
        TestServer.test({}) {
            val format = JwtTokenFormat(
                hasher = RuntimeDeferred.Cached { basis.HS256("jwt") },
                issuer = Runtime { "https://example.com" },
                audience = Runtime { "https://example.com" },
            )
            val token = execute {
                val json = serverRuntime.internalSerialization.json
                val cacheClaim = json.encodeToString(
                    String.serializer(),
                    json.encodeToString(MapSerializer(String.serializer(), ByteArraySerializer()), releasedNoteBytes("user's"))
                )
                val signer = format.hasher.await()
                val b64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
                val seconds = now().epochSeconds
                val header = """{"typ":"JWT","alg":"${signer.name}","prn":"TestUser"}"""
                val claims = """{"iss":"https://example.com","sub":"\"$userId\"","aud":"https://example.com","exp":${seconds + 300},"nbf":$seconds,"iat":$seconds,"scope":"mcp","sid":"session-1","siat":$seconds,"cache":$cacheClaim}"""
                val signingInput = b64.encode(header.encodeToByteArray()) + "." + b64.encode(claims.encodeToByteArray())
                signingInput + "." + b64.encode(signer.sign(signingInput.encodeToByteArray()))
            }
            execute { format.read(TestUser, token)!!.assertIsTheReleasedOne() }
        }
    }

    @Test
    fun `tiny formats round-trip in the new format`() = runBlocking {
        TestServer.test({}) {
            val formats = listOf(
                PrivateTinyTokenFormat(cipher = RuntimeDeferred.Cached { basis.cipher("tiny") }) to "w/TestUser/",
                PublicTinyTokenFormat(hasher = RuntimeDeferred.Cached { basis.HS256("public-tiny") }) to "tw/TestUser/",
            )
            for ((format, prefix) in formats) {
                for (audience in listOf("https://example.com/mcp", null)) {
                    execute {
                        val admin = Authentication(principalType = TestUser, id = adminId, sessionId = "admin-session")
                        admin.cache[note] = "admin's"
                        val auth = Authentication(
                            principalType = TestUser, id = userId, sessionId = "s", audience = audience,
                            fromMasquerade = admin,
                        )
                        auth.cache[note] = "user's"
                        val token = format.create(TestUser, auth)
                        assertTrue(token.startsWith(prefix), token)
                        val read = format.read(TestUser, token)!!
                        assertEquals(audience, read.audience, format::class.simpleName)
                        assertEquals(userId, read.id)
                        assertEquals("s", read.sessionId)
                        assertEquals("user's", read.cache[note])
                        val original = assertNotNull(read.fromMasquerade)
                        assertEquals(TestUser.idString(adminId), original.rawId)
                        assertEquals("admin's", original.cache[note])
                    }
                }
            }
        }
    }

    // During a rolling deploy a released server is handed new tokens.  It must not recognize them, so it declines them
    // (another format may try) instead of failing to decode.
    @Test
    fun `released readers do not recognize new tokens`() = runBlocking {
        TestServer.test({}) {
            execute {
                val auth = Authentication(principalType = TestUser, id = userId, sessionId = "s")
                val private = PrivateTinyTokenFormat(cipher = RuntimeDeferred.Cached { basis.cipher("tiny") }).create(TestUser, auth)
                val public = PublicTinyTokenFormat(hasher = RuntimeDeferred.Cached { basis.HS256("public-tiny") }).create(TestUser, auth)
                for (token in listOf(private, public)) {
                    assertTrue(!token.startsWith("TestUser/") && !token.startsWith("tt/TestUser/"), token)
                }
            }
        }
    }

    @Test
    fun `typical root session token sizes`() = runBlocking {
        TestServer.test({}) {
            execute {
                val now = now()
                val auth = Authentication(principalType = TestUser, id = userId, sessionId = Uuid.random().toString(), sessionIssuedAt = now)
                val serialization = serverRuntime.internalSerialization
                val oldBytes = serialization.oldKotlinBytesFormat.encodeToByteArray(
                    Authentication.serializer(TestUser.serializer()), auth.copy(tokenIssuedAt = now, expiration = now + 5.minutes)
                )
                val private = PrivateTinyTokenFormat(cipher = RuntimeDeferred.Cached { basis.cipher("tiny") })
                val public = PublicTinyTokenFormat(hasher = RuntimeDeferred.Cached { basis.HS256("public-tiny") })
                val oldPrivate = "TestUser/" + Base64.UrlSafe.encode(private.cipher.await().encrypt(oldBytes))
                val oldPublic = "tt/TestUser/" + Base64.UrlSafe.encode(public.hasher.await().sign(oldBytes) + oldBytes)
                val newPrivate = private.create(TestUser, auth)
                val newPublic = public.create(TestUser, auth)
                println("Private tiny token: old ${oldPrivate.length} chars, new ${newPrivate.length} chars")
                println("Public tiny token: old ${oldPublic.length} chars, new ${newPublic.length} chars")
                assertTrue(newPrivate.length < oldPrivate.length)
                assertTrue(newPublic.length < oldPublic.length)
            }
        }
    }
}
