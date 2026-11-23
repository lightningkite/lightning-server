package com.lightningkite.lightningserver.guide.samples

// region obs-imports
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.runtime.Engine
import com.lightningkite.lightningserver.runtime.currentRequestTrace
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.ClientTelemetryEndpoints
import com.lightningkite.lightningserver.typed.sdk.module
import com.lightningkite.services.LoggingTelemetryBackend
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.otel.OtelTelemetryBackend
import com.lightningkite.services.telemetry.*
// endregion obs-imports

// region audit-imports
import com.lightningkite.lightningserver.audit.*
import com.lightningkite.lightningserver.sessions.install
import com.lightningkite.lightningserver.typed.ModelRestEndpoints
import com.lightningkite.lightningserver.typed.modelInfo
import com.lightningkite.services.data.Queryable
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.ModelPermissions
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid
// endregion audit-imports

// region obs-server
object TracedShopServer : ServerBuilder() {
    init {
        // Registers the "logging" URL schemes.
        LoggingTelemetryBackend
        // Registers "otlp-grpc", "otlp-http", "otlp-https", "console", "log", "dev" and "debounced-dev".
        OtelTelemetryBackend
        registerBasicMediaTypeCoders()
    }

    val cache = setting("cache", Cache.Settings())

    private val cartSize = TelemetryKey.OfLong("shop.cart.size")
    private val ordersPlaced = Runtime.Cached {
        contextOf<Engine>().telemetryCounter("shop.orders.placed", MetricUnit.Occurrences, defaultDimensions = emptySet())
    }

    val checkout = path.path("checkout").post bind ApiHttpHandler(
        summary = "Check out",
        auth = noAuth,
        implementation = { items: List<String> ->
            currentRequestTrace()?.enrich(cartSize to items.size.toLong())

            val total = serverRuntime.telemetryTrace("price-cart") { trace ->
                trace.log(LogLevel.Debug) { "Pricing ${items.size} items" }
                items.size * 500
            }

            ordersPlaced().increment()
            total
        },
    )

    // Lets client apps send their spans and logs through the server.
    val clientTelemetry = path.path("telemetry") module ClientTelemetryEndpoints(cache)
}
// endregion obs-server

// region audit-server
@Queryable
data class PatientChart(
    override val _id: ID = ID(Uuid.random()),
    @Audited val name: String,
    @Audited val diagnosis: String,
    val visits: Int = 0,
) : AuditedModel<PatientChart.ID> {
    @Serializable
    @JvmInline
    value class ID(override val raw: Uuid) : AuditedModel.Id<Uuid, ID> {
        override val asUuid: Uuid get() = raw
    }
}

object ClinicServer : ServerBuilder() {
    val database = setting("database", Database.Settings())

    object Audit : AuditingBuilder.WithStandardRequestInfo(database) {
        val disclosures = install(AuditDisclosureLog())
        val authEvents = install(AuthEventLog())
        val mutations = AuditMutationLog()
        val queries = AuditQueryLog()
    }

    val audit = path.path("audit") include Audit

    val chartInfo = database.modelInfo<HasId<*>?, PatientChart, PatientChart.ID>(
        auth = noAuth,
        tableName = "PatientChart",
        log = { it.logAuditedFieldChanges(Audit.mutations).logQueries(Audit.queries) },
        permissions = { ModelPermissions.allowAll() },
    )

    val charts = path.path("charts") include ModelRestEndpoints(chartInfo)

    init {
        registerBasicMediaTypeCoders()
    }
}
// endregion audit-server
