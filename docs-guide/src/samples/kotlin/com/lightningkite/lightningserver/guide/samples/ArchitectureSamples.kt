package com.lightningkite.lightningserver.guide.samples.architecture

// region architecture-imports
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.data.*
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.services.cache.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.test.*
// endregion architecture-imports

// region execution-chain
@Serializable
data class TaskAuditRecord(val parent: Execution.ID?, val origin: Execution.ID, val tenant: String?)

val tenantKey = SerializableCache.Key<Execution, String>("my-app/tenant", String.serializer())

object TenantAuditServer : ServerBuilder() {
    val cache = setting("cache", Cache.Settings())

    val audit = path.path("tasks").path("audit") bind Task { _: Unit ->
        val execution = serverRuntime.execution
        cache().set("last-audit", TaskAuditRecord(execution.parent, execution.origin, execution.context[tenantKey]))
    }

    val hello = path.get bind HttpHandler {
        serverRuntime.execution.context[tenantKey] = "acme"
        audit.launch(Unit)
        HttpResponse.plainText("Hello")
    }
}
// endregion execution-chain

// region execution-chain-test
fun executionChainTest() = TenantAuditServer.testBlocking(settings = {}) {
    val requestId = Execution.ID.generate()
    TenantAuditServer.hello.test(requestId = requestId)

    val record = TenantAuditServer.cache().get<TaskAuditRecord>("last-audit")!!
    assertEquals(requestId, record.parent)
    assertEquals(requestId, record.origin)
    assertEquals("acme", record.tenant)
}
// endregion execution-chain-test

// region extension-key
object ApiVersionKey : MutableExtensions.Key<String>

var ServerBuilder.apiVersion: String? by ApiVersionKey
val ServerDefinition.apiVersion: String? by ApiVersionKey
// endregion extension-key

// region writable-key
object FeatureFlagsKey : ListRegistryExtension<String>

val ServerBuilder.featureFlags: ListRegistry<String> by FeatureFlagsKey
val ServerDefinition.featureFlags: List<String> by FeatureFlagsKey
// endregion writable-key

// region build-check
object VersionedApi : ServerBuilder() {
    init {
        featureFlags.register("versioned-api")
        buildCheck { definition ->
            checkNotNull(definition.apiVersion) { "VersionedApi needs apiVersion set on the root server" }
        }
    }
}

object FlaggedServer : ServerBuilder() {
    init {
        apiVersion = "2"
        featureFlags.register("core")
    }

    val api = path.path("api") include VersionedApi
}
// endregion build-check

// region extension-read
fun readExtensions() {
    val definition = FlaggedServer.build()
    assertEquals("2", definition.apiVersion)
    assertEquals(listOf("core", "versioned-api"), definition.featureFlags)
}
// endregion extension-read

// region request-cache-key
object ClientKindKey : Request.CacheKey<PathSpec, String> {
    override val id = "my-app/client-kind"
    override val serializer = String.serializer()

    context(server: ServerRuntime)
    override suspend fun calculate(input: Request<PathSpec>): String {
        val agent = input.headers[HttpHeader.UserAgent]?.root ?: return "unknown"
        return if ("Mobile" in agent) "mobile" else "desktop"
    }
}

object ClientKindServer : ServerBuilder() {
    val kind = path.path("kind").get bind HttpHandler { request ->
        HttpResponse.plainText(request[ClientKindKey])
    }
}
// endregion request-cache-key

fun requestCacheKeyTest() = ClientKindServer.testBlocking(settings = {}) {
    val response = ClientKindServer.kind.test(headers = HttpHeaders(HttpHeader.UserAgent to "Mobile Safari"))
    assertEquals("mobile", response.body?.text())
}
