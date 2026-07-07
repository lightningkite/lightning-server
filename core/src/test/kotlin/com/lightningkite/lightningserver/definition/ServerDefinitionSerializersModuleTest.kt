package com.lightningkite.lightningserver.definition

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.runtime.test.test
import kotlinx.serialization.modules.SerializersModule
import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Verifies that a module whose serializers module getter throws fails fast with a clear error that names the
 * offending module, rather than surfacing an opaque error deep inside a later request.
 */
class ServerDefinitionSerializersModuleTest {

    object BadExternalSerializerServer : ServerBuilder() {
        override val externalSerialization: Runtime<SerializersModule>
            get() = Runtime { throw IllegalArgumentException("serializer registration blew up") }

        val root = path.get bind HttpHandler<PathSpec0> { HttpResponse.plainText("ok") }
    }

    @Test
    fun `throwing external serializers module getter fails fast naming the module`() {
        BadExternalSerializerServer.test(settings = {}) {
            val failure = assertFailsWith<IllegalStateException> {
                // Materializing serialization folds every module's serializers module getter; the bad one throws.
                serverRuntime.externalSerialization
            }
            assertTrue(
                failure.message?.contains("external serializers module") == true,
                "Error should identify which serializers module failed. Was: ${failure.message}",
            )
            assertTrue(
                failure.cause?.message?.contains("serializer registration blew up") == true,
                "Original failure should be preserved as the cause. Was: ${failure.cause?.message}",
            )
        }
    }
}
