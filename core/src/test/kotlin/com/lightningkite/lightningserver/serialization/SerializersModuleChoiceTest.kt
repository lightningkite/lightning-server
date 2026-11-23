package com.lightningkite.lightningserver.serialization

import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.QueryParameters
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.runtime.test.testBlocking
import com.lightningkite.services.database.validation.AnnotationValidators
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class SerializersModuleChoiceTest {

    class Shouted(val text: String)

    object ShoutedSerializer : KSerializer<Shouted> {
        override val descriptor: SerialDescriptor = SerialDescriptor("Shouted", String.serializer().descriptor)
        override fun serialize(encoder: Encoder, value: Shouted) = encoder.encodeString(value.text.lowercase())
        override fun deserialize(decoder: Decoder): Shouted = Shouted(decoder.decodeString().uppercase())
    }

    @Serializable
    data class Input(@Contextual val word: Shouted)

    object TestServer : ServerBuilder() {
        override val externalSerialization: Runtime<SerializersModule> =
            Runtime.Constant(SerializersModule { contextual(Shouted::class, ShoutedSerializer) })

        val echo = path.path("echo").get bind HttpHandler { request ->
            HttpResponse.plainText(request.queryParameters(Input.serializer()).word.text)
        }

        init {
            registerBasicMediaTypeCoders()
        }
    }

    @Test
    fun queryParametersDecodeWithTheExternalModule() = TestServer.testBlocking(settings = {}) {
        val response = TestServer.echo.test(queryParameters = QueryParameters(listOf("word" to "hello")))
        assertEquals(200, response.status.code)
        assertEquals("HELLO", response.body?.text())
    }

    @Test
    fun validatorModulesMatchTheirNames() = TestServer.testBlocking(settings = {}) {
        assertSame(ShoutedSerializer, AnnotationValidators.StandardWithExternalModule().serializersModule.getContextual(Shouted::class))
        assertNull(AnnotationValidators.StandardWithInternalModule().serializersModule.getContextual(Shouted::class))
    }
}
