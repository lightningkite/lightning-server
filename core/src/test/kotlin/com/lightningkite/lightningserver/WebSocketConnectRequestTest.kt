package com.lightningkite.lightningserver

import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.ServerPath
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.websockets.WebSocketConnectRequest
import com.lightningkite.services.data.KotlinBytesFormat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.EmptySerializersModule
import org.junit.Assert.*
import kotlin.io.encoding.Base64
import kotlin.test.Test

class WebSocketConnectRequestTest {
    @Test fun serialization(): Unit = runBlocking {
        val r = WebSocketConnectRequest<PathSpec0>(
            path = ServerPath("a/b/c"),
            queryParameters = listOf("a" to "b", "c" to "d"),
            headers = HttpHeaders {
                setCookie("test", "asdf")
                set(HttpHeader.Location, "https://www.google.com")
            },
            domain = "localhost",
            protocol = "https",
            sourceIp = "127.0.0.1"
        )
        r.roundTripTest()
        object: OldServerDefinition() {

        }.test(
            settings = {}
        ) {
            r[CacheKey]
            r.roundTripTest()
        }
    }

    object CacheKey: KeyedSerializableCache.Key<String> {
        override val id: String
            get() = "cache"
        override val serializer: KSerializer<String>
            get() = String.serializer()

        override suspend fun calculate(
            serverRuntime: ServerRuntime,
            request: Request<*>
        ): String = "asdf"

    }
}

val json = Json { prettyPrint = true; encodeDefaults = true }
val kbytes = KotlinBytesFormat(EmptySerializersModule())
inline fun <reified T> T.roundTripTest() {
    val serializer: KSerializer<T> = serializerOrContextual()
    assertEquals(this, Json.decodeFromString(serializer, Json.encodeToString(serializer, this).also { println(it) }))
    assertEquals(this, kbytes.decodeFromByteArray(serializer, kbytes.encodeToByteArray(serializer, this).also { println(Base64.encode(it)) }))
}
