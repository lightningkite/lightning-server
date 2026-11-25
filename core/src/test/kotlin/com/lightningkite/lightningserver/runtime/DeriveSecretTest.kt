package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.encryption.SecretBasis
import com.lightningkite.lightningserver.runtime.test.testBlocking
import com.lightningkite.lightningserver.settings.set
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Services' deployment-wide keys ([Engine.deriveSecret]) come from the secret basis. */
class DeriveSecretTest {

    object TestServer : ServerBuilder()

    private val basis = SecretBasis()

    context(engine: Engine)
    private fun derived(purpose: String): ByteArray = assertNotNull(engine.deriveSecret(purpose))

    /** What a server holding [basis] derives for each of [purposes]. */
    private fun deriveOn(basis: SecretBasis, vararg purposes: String): List<ByteArray> {
        var keys = emptyList<ByteArray>()
        TestServer.testBlocking(settings = { secretBasis set basis }) {
            keys = purposes.map { derived(it) }
        }
        return keys
    }

    @Test
    fun serversSharingTheBasisDeriveTheSameKey() {
        val (first) = deriveOn(basis, "otherService/example-bucket")
        val (second) = deriveOn(basis, "otherService/example-bucket")
        assertContentEquals(first, second)
        assertTrue(first.size >= 32)
    }

    @Test
    fun keysDifferByPurposeAndBasis() {
        val (bucket, otherBucket) = deriveOn(basis, "otherService/example-bucket", "otherService/other-bucket")
        val (otherBasis) = deriveOn(SecretBasis(), "otherService/example-bucket")
        assertFalse(bucket.contentEquals(otherBucket))
        assertFalse(bucket.contentEquals(otherBasis))
    }

    @Test
    fun keysAreSeparateFromTheBasisOwnDerivations() {
        // Namespaced, so a service's purpose can't collide with a key the server derives for itself.
        val (key) = deriveOn(basis, "proof")
        assertFalse(key.contentEquals(basis.deriveBlocking("proof")))
    }
}
