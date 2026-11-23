package com.lightningkite.lightningserver.guide.samples

// region cors-imports
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.cors.*
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
// endregion cors-imports

// region cors-server
object CorsServer : ServerBuilder() {
    val cors = setting("cors", CorsSettings.forProduction("https://app.example.com"))

    init {
        registerBasicMediaTypeCoders()
        install(CorsInterceptor(cors))
    }

    val hello = path.path("api").path("hello").get bind HttpHandler {
        HttpResponse.plainText("Hello!")
    }
}
// endregion cors-server

// region cors-test
fun corsAllowedOriginTest() = CorsServer.testBlocking(settings = {}) {
    val allowed = CorsServer.hello.test(headers = HttpHeaders { add(HttpHeader.Origin, "https://app.example.com") })
    assertEquals("https://app.example.com", allowed.headers[HttpHeader.AccessControlAllowOrigin]?.root)

    val rejected = CorsServer.hello.test(headers = HttpHeaders { add(HttpHeader.Origin, "https://evil.example") })
    assertEquals(HttpStatus.Forbidden, rejected.status)
}
// endregion cors-test

// region interceptor-served-by
class ServedBy(private val instance: String) : HttpInterceptor {
    context(runtime: ServerRuntime)
    override suspend fun <PATH : PathSpec> intercept(
        request: HttpRequest<PATH>,
        cont: suspend context(ServerRuntime) (HttpRequest<PATH>) -> HttpResponse,
    ): HttpResponse {
        val response = cont(request)
        return response.copy(headers = response.headers.copy { add("X-Served-By", instance) })
    }
}
// endregion interceptor-served-by

// region interceptor-maintenance
class MaintenanceMode(private val enabled: Runtime<Boolean>) : HttpInterceptor {
    context(runtime: ServerRuntime)
    override suspend fun <PATH : PathSpec> intercept(
        request: HttpRequest<PATH>,
        cont: suspend context(ServerRuntime) (HttpRequest<PATH>) -> HttpResponse,
    ): HttpResponse {
        if (enabled()) return HttpResponse.plainText("Down for maintenance", status = HttpStatus.ServiceUnavailable)
        return cont(request)
    }
}
// endregion interceptor-maintenance

// region interceptor-execution
object SlowExecutionLog : ExecutionInterceptor {
    context(runtime: ServerRuntime)
    override suspend fun <T> intercept(
        invocation: Invocation,
        cont: suspend context(ServerRuntime) () -> T,
    ): T {
        val started = TimeSource.Monotonic.markNow()
        try {
            return cont()
        } finally {
            val elapsed = started.elapsedNow()
            if (elapsed > 1.seconds) println("Slow execution ${invocation.execution}: $elapsed")
        }
    }
}
// endregion interceptor-execution

// region interceptor-server
object InterceptedServer : ServerBuilder() {
    val cors = setting("cors", CorsSettings.forProduction("https://app.example.com"))
    val maintenance = setting("maintenance", false)

    init {
        registerBasicMediaTypeCoders()
        install(SecurityHeadersInterceptor())
        install(CorsInterceptor(cors))
        install(MaintenanceMode(maintenance))
        install(ServedBy("api-1"))
        install(SlowExecutionLog)
    }

    val hello = path.path("api").path("hello").get bind HttpHandler {
        HttpResponse.plainText("Hello!")
    }
}
// endregion interceptor-server

// region interceptor-test
fun interceptorOrderTest() = InterceptedServer.testBlocking(settings = { maintenance set true }) {
    val response = InterceptedServer.hello.test(headers = HttpHeaders { add(HttpHeader.Origin, "https://app.example.com") })
    assertEquals(HttpStatus.ServiceUnavailable, response.status)
    // MaintenanceMode answered, but CORS is outside it, so the browser can still read the 503.
    assertEquals("https://app.example.com", response.headers[HttpHeader.AccessControlAllowOrigin]?.root)
    // ServedBy is inside MaintenanceMode, so it never ran.
    assertNull(response.headers["X-Served-By"])
}
// endregion interceptor-test
