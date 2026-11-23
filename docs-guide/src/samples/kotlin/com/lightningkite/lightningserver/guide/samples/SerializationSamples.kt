package com.lightningkite.lightningserver.guide.samples

// region serialization-imports
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.serialization.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.data.*
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.*
// endregion serialization-imports

// region serialization-raw
@Serializable
data class Salutation(val name: String, val excited: Boolean = false)

object GreetingServer : ServerBuilder() {
    init { registerBasicMediaTypeCoders() }

    // POST /greet — decodes by Content-Type, encodes by Accept
    val greet = path.path("greet").post bind HttpHandler { request ->
        val input = request.body?.parse(Salutation.serializer()) ?: throw BadRequestException("Missing body")
        val output = input.copy(name = input.name.uppercase())
        HttpResponse(body = output.toTypedData(request.headers.accept))
    }

    // GET /greeting?name=Ann&excited=true
    val greeting = path.path("greeting").get bind HttpHandler { request ->
        val input = request.queryParameters(Salutation.serializer())
        HttpResponse.plainText("Hello, ${input.name}" + if (input.excited) "!" else ".")
    }
}
// endregion serialization-raw

// region serialization-raw-test
fun serializationRawTest() = GreetingServer.testBlocking(settings = {}) {
    val json = GreetingServer.greet.test(
        body = TypedData.text("""{"name":"ann"}""", MediaType.Application.Json),
    )
    check(json.body?.mediaType == MediaType.Application.Json)
    check(json.body?.text() == """{"name":"ANN","excited":false}""")

    val form = GreetingServer.greet.test(
        body = TypedData.text("name=ann&excited=true", MediaType.Application.FormUrlEncoded),
        headers = HttpHeaders(HttpHeader.Accept to "application/x-www-form-urlencoded"),
    )
    check(form.body?.mediaType == MediaType.Application.FormUrlEncoded)

    val query = GreetingServer.greeting.test(queryParameters = QueryParameters.parse("name=Ann&excited=true"))
    check(query.body?.text() == "Hello, Ann!")
}
// endregion serialization-raw-test

// region serialization-contextual
// A type you don't control, so it can't carry @Serializable.
class Temperature(val celsius: Double)

object TemperatureSerializer : KSerializer<Temperature> {
    override val descriptor = PrimitiveSerialDescriptor("Temperature", PrimitiveKind.DOUBLE)
    override fun serialize(encoder: Encoder, value: Temperature) = encoder.encodeDouble(value.celsius)
    override fun deserialize(decoder: Decoder) = Temperature(decoder.decodeDouble())
}

@Serializable
data class SensorReading(val sensor: String, @Contextual val temperature: Temperature)

object TemperatureServer : ServerBuilder() {
    override val externalSerialization: Runtime<SerializersModule> = Runtime.Constant(
        SerializersModule { contextual(Temperature::class, TemperatureSerializer) }
    )

    init { registerBasicMediaTypeCoders() }

    val latest = path.path("latest").get bind ApiHttpHandler(
        summary = "Latest reading",
        auth = noAuth,
        implementation = { _: Unit -> SensorReading("roof", Temperature(21.5)) }
    )
}
// endregion serialization-contextual

// region serialization-contextual-test
fun serializationContextualTest() = TemperatureServer.testBlocking(settings = {}) {
    check(TemperatureServer.latest.test().body?.text() == """{"sensor":"roof","temperature":21.5}""")
}
// endregion serialization-contextual-test

// region serialization-coder
object PrettyServer : ServerBuilder() {
    init {
        registerBasicMediaTypeCoders()
        register(
            StringFormatMediaTypeCoder(
                format = { Json { prettyPrint = true; serializersModule = engine.externalSerialization.serializersModule } },
                mediaType = MediaType("application", "vnd.example.pretty+json"),
            )
        )
    }

    val latest = path.path("latest").get bind ApiHttpHandler(
        summary = "Latest greeting",
        auth = noAuth,
        implementation = { _: Unit -> Salutation("Ann") }
    )
}
// endregion serialization-coder

// region serialization-coder-test
fun serializationCoderTest() = PrettyServer.testBlocking(settings = {}) {
    val pretty = PrettyServer.latest.test(headers = HttpHeaders(HttpHeader.Accept to "application/vnd.example.pretty+json"))
    check(pretty.body?.text()?.contains("\n") == true)

    val compact = PrettyServer.latest.test()
    check(compact.body?.mediaType == MediaType.Application.Json)
}
// endregion serialization-coder-test

// region serialization-interceptor
@Serializable
data class SecretApiKey(val key: String)

object GuardedServer : ServerBuilder() {
    init {
        registerBasicMediaTypeCoders()
        install(object : EncodingInterceptor {
            context(runtime: ServerRuntime)
            override suspend fun <T> beforeEncode(serializer: SerializationStrategy<T>, value: T) {
                if (value is SecretApiKey) throw ForbiddenException("API keys are never sent to clients")
            }
        })
    }

    val leak = path.path("leak").get bind ApiHttpHandler(
        summary = "Leak a key",
        auth = noAuth,
        implementation = { _: Unit -> SecretApiKey("secret") }
    )
}
// endregion serialization-interceptor

// region serialization-interceptor-test
fun serializationInterceptorTest() = GuardedServer.testBlocking(settings = {}) {
    check(GuardedServer.leak.test().status == HttpStatus.Forbidden)
}
// endregion serialization-interceptor-test
