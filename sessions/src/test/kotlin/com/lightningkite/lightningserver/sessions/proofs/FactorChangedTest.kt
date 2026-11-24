package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.auth.register
import com.lightningkite.lightningserver.auth.testAuth
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.encryption.SecretBasis
import com.lightningkite.lightningserver.encryption.signer
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.runtime.test.execute
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.sessions.*
import com.lightningkite.lightningserver.sessions.AuthEvent.FactorChanged.Change
import com.lightningkite.lightningserver.typed.test
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.database.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import com.lightningkite.lightningserver.runtime.Engine
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

class FactorChangedTest {

    @Serializable
    data class FactorUser(override val _id: Uuid = Uuid.random(), val email: String = "") : HasId<Uuid> {
        companion object : PrincipalType<FactorUser, Uuid> {
            override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
            override val subjectSerializer: KSerializer<FactorUser> = serializer()
            val users = mutableMapOf<Uuid, FactorUser>()

            context(server: ServerRuntime)
            override suspend fun fetch(id: Uuid): FactorUser = users[id] ?: FactorUser(id)

            context(server: ServerRuntime)
            override suspend fun fetchByProperty(property: String, value: String): FactorUser? =
                if (property == "email") users.values.find { it.email == value } else super.fetchByProperty(property, value)
        }
    }

    private class Capturing : AuthEventListener {
        val changes = mutableListOf<AuthEvent.FactorChanged>()

        context(runtime: ServerRuntime)
        override suspend fun onEvent(event: AuthEvent) {
            if (event is AuthEvent.FactorChanged) changes += event
        }
    }

    private class Throwing : AuthEventListener {
        context(runtime: ServerRuntime)
        override suspend fun onEvent(event: AuthEvent): Unit = throw IllegalStateException("notification failed")
    }

    private val basis = SecretBasis()

    private inner class FactorServer : ServerBuilder() {
        val database = setting("database", Database.Settings("ram"))
        val cache = setting("cache", Cache.Settings("ram"))

        init {
            registerBasicMediaTypeCoders()
            register(FactorUser)
            install(Throwing())
        }

        val capturing = install(Capturing())

        val password = path.path("password") include PasswordProofEndpoints(
            database = database,
            cache = cache,
            proofSigner = RuntimeDeferred.Cached { basis.signer("proof") },
        )
        val knownDevice = path.path("known-device") include KnownDeviceProofEndpoints(
            database = database,
            cache = cache,
            proofSigner = RuntimeDeferred.Cached { basis.signer("proof") },
        )
        val backupCodes = path.path("backup") include BackupCodeEndpoints(
            database = database,
            cache = cache,
            proofSigner = RuntimeDeferred.Cached { basis.signer("proof") },
            generateCount = 2,
        )
    }

    private val user = FactorUser(email = "factor@example.com").also { FactorUser.users[it._id] = it }

    @Suppress("UNCHECKED_CAST")
    context(_: Engine)
    private fun FactorServer.auth() = FactorUser.testAuth(user) as Authentication<HasId<*>>

    private fun FactorServer.changes() = capturing.changes.map { it.method.via to it.change }

    @Test
    fun `password set, changed and removed are each reported once, and the change stands though a listener throws`() = runBlocking {
        val server = FactorServer()
        server.test({}) {
            execute { server.password.establish(FactorUser, user._id, EstablishPassword("first password")) }
            assertEquals(listOf("password" to Change.Added), server.changes())
            assertEquals(user._id.toString(), server.capturing.changes.single().principal)
            assertEquals("FactorUser", server.capturing.changes.single().principalType)

            execute { server.password.establish(FactorUser, user._id, EstablishPassword("second password")) }
            assertEquals(listOf("password" to Change.Added, "password" to Change.Replaced), server.changes())

            val active = execute {
                server.password.modelInfo.table().find(Condition<PasswordSecret> { it.disabledAt eq null }).toList()
            }.single()
            server.password.rest.modify.test(active._id, server.auth(), Modification<PasswordSecret> { it.disabledAt assign now() })
            assertEquals(Change.Removed, server.capturing.changes.last().change)
            assertEquals(3, server.capturing.changes.size)
        }
    }

    @Test
    fun `failed password attempts report no change`() = runBlocking {
        val server = FactorServer()
        server.test({}) {
            assertFailsWith<BadRequestException> {
                execute { server.password.establish(FactorUser, user._id, EstablishPassword("secret", hint = "it is secret")) }
            }
            execute { server.password.establish(FactorUser, user._id, EstablishPassword("real password")) }
            server.capturing.changes.clear()

            assertFailsWith<BadRequestException> {
                server.password.prove.test(null, IdentificationAndPassword("FactorUser", "email", user.email, "wrong"))
            }
            server.password.prove.test(null, IdentificationAndPassword("FactorUser", "email", user.email, "real password"))
            assertEquals(listOf(), server.changes())
        }
    }

    @Test
    fun `known device established and revoked are reported, using it is not`() = runBlocking {
        val server = FactorServer()
        server.test({}) {
            val device = execute { server.knownDevice.establish(FactorUser, user._id, "test device") }
            server.knownDevice.prove.test(null, device.secret)
            assertEquals(listOf("known-device" to Change.Added), server.changes())

            server.knownDevice.rest.modify.test(
                Uuid.parse(device.secret.substringBefore('/')),
                server.auth(),
                Modification<KnownDeviceSecret> { it.disabledAt assign now() },
            )
            assertEquals(listOf("known-device" to Change.Added, "known-device" to Change.Removed), server.changes())
        }
    }

    @Test
    fun `backup codes reset and cleared are reported, clearing nothing is not`() = runBlocking {
        val server = FactorServer()
        server.test({}) {
            server.backupCodes.resetCodes.test(server.auth(), Unit)
            server.backupCodes.resetCodes.test(server.auth(), Unit)
            server.backupCodes.clearCodes.test(server.auth(), Unit)
            server.backupCodes.clearCodes.test(server.auth(), Unit)
            assertEquals(
                listOf("backupcode" to Change.Added, "backupcode" to Change.Replaced, "backupcode" to Change.Removed),
                server.changes(),
            )
        }
    }
}
