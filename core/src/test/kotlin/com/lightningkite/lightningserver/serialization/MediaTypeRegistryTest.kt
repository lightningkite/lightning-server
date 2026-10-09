// by Claude
package com.lightningkite.lightningserver.serialization

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.execute
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlin.test.*

/**
 * Tests for MediaTypeEncoderRegistry and MediaTypeDecoderRegistry.
 */
class MediaTypeRegistryTest {

    object TestServer : ServerBuilder() {
        init {
            registerBasicMediaTypeCoders()
        }
    }

    // ========== MediaTypeEncoderRegistry Tests ==========

    @Test
    fun `encoder registry can be created empty`() {
        val registry = MediaTypeEncoderRegistry()
        assertTrue(registry.isEmpty())
    }

    @Test
    fun `encoder registry can register encoder`() = runBlocking {
        TestServer.test({}) {
            val registry = MediaTypeEncoderRegistry()
            val encoder = TestEncoder(MediaType.Application.Json, priority = 1f)

            registry.register(encoder)

            assertEquals(1, registry.size)
            assertEquals(listOf(encoder), registry[MediaType.Application.Json])
        }
    }

    @Test
    fun `encoder registry puts the highest priority first`() = runBlocking {
        TestServer.test({}) {
            val registry = MediaTypeEncoderRegistry()
            val lowPriority = TestEncoder(MediaType.Application.Json, priority = 1f)
            val highPriority = TestEncoder(MediaType.Application.Json, priority = 10f)

            registry.register(lowPriority)
            registry.register(highPriority)

            val encoders = registry[MediaType.Application.Json]
            assertNotNull(encoders)
            assertEquals(2, encoders.size)
            assertEquals(listOf(highPriority, lowPriority), encoders)
        }
    }

    @Test
    fun `encoder registry supports multiple media types`() = runBlocking {
        TestServer.test({}) {
            val registry = MediaTypeEncoderRegistry()
            val jsonEncoder = TestEncoder(MediaType.Application.Json)
            val xmlEncoder = TestEncoder(MediaType.Application.Xml)

            registry.register(jsonEncoder)
            registry.register(xmlEncoder)

            assertEquals(2, registry.size)
            assertEquals(listOf(jsonEncoder), registry[MediaType.Application.Json])
            assertEquals(listOf(xmlEncoder), registry[MediaType.Application.Xml])
        }
    }

    @Test
    fun `encoder registry can include from another registry`() = runBlocking {
        TestServer.test({}) {
            val registry1 = MediaTypeEncoderRegistry()
            val registry2 = MediaTypeEncoderRegistry()
            val encoder1 = TestEncoder(MediaType.Application.Json)
            val encoder2 = TestEncoder(MediaType.Application.Xml)

            registry1.register(encoder1)
            registry2.register(encoder2)

            registry1.include(registry2)

            assertEquals(2, registry1.size)
            assertNotNull(registry1[MediaType.Application.Json])
            assertNotNull(registry1[MediaType.Application.Xml])
        }
    }

    @Test
    fun `encoder registry keeps priority order across include`() {
        val registry = MediaTypeEncoderRegistry()
        val other = MediaTypeEncoderRegistry()
        val lowPriority = TestEncoder(MediaType.Application.Json, priority = 1f)
        val highPriority = TestEncoder(MediaType.Application.Json, priority = 10f)

        registry.register(lowPriority)
        other.register(highPriority)
        registry.include(other)

        assertEquals(listOf(highPriority, lowPriority), registry[MediaType.Application.Json])
    }

    @Test
    fun `encoders of equal priority keep registration order`() {
        val registry = MediaTypeEncoderRegistry()
        val first = TestEncoder(MediaType.Application.Json)
        val second = TestEncoder(MediaType.Application.Json)

        registry.register(first)
        registry.register(second)

        assertEquals(listOf(first, second), registry[MediaType.Application.Json])
    }

    @Test
    fun `encoder registry returns null for unregistered media type`() {
        val registry = MediaTypeEncoderRegistry()
        assertNull(registry[MediaType.Application.Json])
    }

    // ========== MediaTypeDecoderRegistry Tests ==========

    @Test
    fun `decoder registry can be created empty`() {
        val registry = MediaTypeDecoderRegistry()
        assertTrue(registry.isEmpty())
    }

    @Test
    fun `decoder registry can register decoder`() = runBlocking {
        TestServer.test({}) {
            val registry = MediaTypeDecoderRegistry()
            val decoder = TestDecoder(MediaType.Application.Json, priority = 1f)

            registry.register(decoder)

            assertEquals(1, registry.size)
            assertEquals(listOf(decoder), registry[MediaType.Application.Json])
        }
    }

    @Test
    fun `decoder registry puts the highest priority first`() = runBlocking {
        TestServer.test({}) {
            val registry = MediaTypeDecoderRegistry()
            val lowPriority = TestDecoder(MediaType.Application.Json, priority = 1f)
            val highPriority = TestDecoder(MediaType.Application.Json, priority = 10f)

            registry.register(lowPriority)
            registry.register(highPriority)

            val decoders = registry[MediaType.Application.Json]
            assertNotNull(decoders)
            assertEquals(2, decoders.size)
            assertEquals(listOf(highPriority, lowPriority), decoders)
        }
    }

    @Test
    fun `decoder registry supports multiple media types`() = runBlocking {
        TestServer.test({}) {
            val registry = MediaTypeDecoderRegistry()
            val jsonDecoder = TestDecoder(MediaType.Application.Json)
            val xmlDecoder = TestDecoder(MediaType.Application.Xml)

            registry.register(jsonDecoder)
            registry.register(xmlDecoder)

            assertEquals(2, registry.size)
            assertEquals(listOf(jsonDecoder), registry[MediaType.Application.Json])
            assertEquals(listOf(xmlDecoder), registry[MediaType.Application.Xml])
        }
    }

    @Test
    fun `decoder registry can include from another registry`() = runBlocking {
        TestServer.test({}) {
            val registry1 = MediaTypeDecoderRegistry()
            val registry2 = MediaTypeDecoderRegistry()
            val decoder1 = TestDecoder(MediaType.Application.Json)
            val decoder2 = TestDecoder(MediaType.Application.Xml)

            registry1.register(decoder1)
            registry2.register(decoder2)

            registry1.include(registry2)

            assertEquals(2, registry1.size)
            assertNotNull(registry1[MediaType.Application.Json])
            assertNotNull(registry1[MediaType.Application.Xml])
        }
    }

    @Test
    fun `decoder registry keeps priority order across include`() {
        val registry = MediaTypeDecoderRegistry()
        val other = MediaTypeDecoderRegistry()
        val lowPriority = TestDecoder(MediaType.Application.Json, priority = 1f)
        val highPriority = TestDecoder(MediaType.Application.Json, priority = 10f)

        registry.register(lowPriority)
        other.register(highPriority)
        registry.include(other)

        assertEquals(listOf(highPriority, lowPriority), registry[MediaType.Application.Json])
    }

    @Test
    fun `decoder registry returns null for unregistered media type`() {
        val registry = MediaTypeDecoderRegistry()
        assertNull(registry[MediaType.Application.Json])
    }

    // ========== Lookup through a server ==========

    /** Registered in a module, so the server only sees it after its registry is merged with the root's. */
    object PreferredCoders : ServerBuilder() {
        val encoder: MediaTypeEncoder = TestEncoder(MediaType.Application.Json, priority = 10f)
        val decoder: MediaTypeDecoder = TestDecoder(MediaType.Application.Json, priority = 10f)

        init {
            register(encoder)
            register(decoder)
        }
    }

    object PriorityServer : ServerBuilder() {
        init {
            registerBasicMediaTypeCoders()
        }

        val preferred = path.path("preferred") include PreferredCoders
    }

    @Test
    fun `lookup picks the highest priority coder for a media type`() = runBlocking {
        PriorityServer.test({}) {
            execute {
                assertSame(PreferredCoders.encoder, MediaType.Application.Json.encoder)
                assertSame(PreferredCoders.encoder, listOf(MediaType.Application.Json).encoder?.second)
                assertSame(PreferredCoders.decoder, MediaType.Application.Json.decoder)
            }
        }
    }

    // ========== Test Helpers ==========

    private class TestEncoder(
        override val mediaType: MediaType,
        override val priority: Float = 0f,
    ) : MediaTypeEncoder {
        context(runtime: ServerRuntime)
        override fun accepts(parameters: Map<String, String>): Boolean = true

        context(runtime: ServerRuntime)
        override suspend fun <T> encode(
            mediaType: MediaType,
            serializer: SerializationStrategy<T>,
            value: T,
        ): TypedData = TypedData.text("test", mediaType)
    }

    private class TestDecoder(
        override val mediaType: MediaType,
        override val priority: Float = 0f,
    ) : MediaTypeDecoder {
        context(runtime: ServerRuntime)
        override fun accepts(parameters: Map<String, String>): Boolean = true

        context(runtime: ServerRuntime)
        override suspend fun <T> decode(content: TypedData, serializer: DeserializationStrategy<T>): T {
            @Suppress("UNCHECKED_CAST")
            return Unit as T
        }
    }
}
