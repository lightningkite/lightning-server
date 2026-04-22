package com.lightningkite.lightningserver.typed

import com.lightningkite.DataSize
import kotlinx.serialization.Serializable
import com.lightningkite.services.HealthStatus
import com.lightningkite.services.data.Description
import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.VirtualAlias
import com.lightningkite.services.database.VirtualEnum
import com.lightningkite.services.database.VirtualStruct
import com.lightningkite.services.database.VirtualTypeReference
import kotlinx.datetime.LocalDate
import kotlin.math.roundToInt
import kotlin.time.Instant
import kotlin.uuid.Uuid
import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.auth.RequiredScope
import com.lightningkite.services.database.VirtualSealed

/**
 * Request to start tracking a new user funnel instance.
 *
 * @property funnel Identifier for the funnel being tracked
 * @property userAgent User agent string of the client
 * @property version Application version
 * @property expireAfterMinutes Time in minutes after which the funnel instance expires if not completed (default: 20)
 * @property expectedErrorRate Expected rate of errors for this funnel, used for health monitoring (default: 0.05 or 5%)
 */
@Serializable
public data class FunnelStart(
    val funnel: String,
    val userAgent: String,
    val version: String,
    val expireAfterMinutes: Int = 20,
    val expectedErrorRate: Float = 0.05f
)
/**
 * Aggregated summary of funnel completion metrics for a specific date.
 *
 * Provides health status and completion statistics for monitoring funnel performance.
 *
 * @property funnel Identifier for the funnel
 * @property date Date for which these metrics apply
 * @property status Overall health status level for this funnel on this date
 * @property success Percentage of funnels completed successfully without errors (0.0-1.0)
 * @property successAfterError Percentage of funnels completed successfully after encountering errors (0.0-1.0)
 * @property error Percentage of funnels that failed with errors (0.0-1.0)
 * @property abandoned Percentage of funnels that were started but never completed (0.0-1.0)
 * @property count Total number of funnel instances tracked
 */
@Serializable
@GenerateDataClassPaths
public data class FunnelSummary(
    override val _id: Uuid = Uuid.random(),
    val funnel: String,
    val date: LocalDate,
    val status: HealthStatus.Level,
    val success: Float = 0f,
    val successAfterError: Float = 0f,
    val error: Float = 0f,
    val abandoned: Float = 0f,
    val count: Int = 0,
): HasId<Uuid>

/**
 * Represents an individual user's journey through a funnel.
 *
 * Tracks the current state, errors encountered, and completion status of a single funnel execution.
 *
 * @property funnel Identifier for the funnel being tracked
 * @property userAgent User agent string of the client
 * @property user Optional user identifier associated with this funnel instance
 * @property version Application version
 * @property errors Set of error identifiers encountered during this funnel execution
 * @property step Current step number in the funnel (0-indexed)
 * @property success Timestamp when the funnel was successfully completed, null if not completed
 * @property started Timestamp when the funnel instance was created
 * @property expiry Timestamp after which this funnel instance expires if not completed
 * @property expectedErrorRate Expected error rate for this funnel, used for health monitoring
 */
@Serializable
@GenerateDataClassPaths
public data class FunnelInstance(
    override val _id: Uuid = Uuid.random(),
    val funnel: String,
    val userAgent: String,
    val user: String? = null,
    val version: String,
    val errors: Set<String> = setOf(),
    val step: Int = 0,
    val success: Instant? = null,
    val started: Instant,
    val expiry: Instant,
    val expectedErrorRate: Float = 0.05f
): HasId<Uuid>


/**
 * Server health status report containing system resource usage and feature-level health.
 *
 * Provides comprehensive health monitoring including CPU, memory, and individual feature status.
 *
 * @property serverId Unique identifier for this server instance
 * @property version Server application version
 * @property memory Memory usage statistics
 * @property features Map of feature names to their health status
 * @property loadAverageCpu CPU load average as a percentage (0.0-1.0+, can exceed 1.0 on overload)
 */
@Serializable
public data class ServerHealth(
    val serverId: String,
    val version: String,
    val memory: Memory,
    val features: Map<String, HealthStatus>,
    val loadAverageCpu: Double,
) {
    /**
     * Overall health status determined by the worst health status of any feature.
     */
    val overall: HealthStatus.Level get() = features.maxOf { it.value.level }

    /**
     * Health status based on CPU load average.
     * - OK: < 70% utilization
     * - WARNING: 70-95% utilization
     * - URGENT: 95-100% utilization
     * - ERROR: > 100% utilization (overloaded)
     */
    public val loadAverageCpuHealth: HealthStatus
        get() = when (val amount = loadAverageCpu) {
            in 0.0..<0.7 -> HealthStatus(HealthStatus.Level.OK)
            in 0.7..<0.95 -> HealthStatus(
                HealthStatus.Level.WARNING,
                additionalMessage = "CPU utilization: ${amount.times(100).roundToInt()}%"
            )

            in 0.95..<1.0 -> HealthStatus(
                HealthStatus.Level.URGENT,
                additionalMessage = "CPU utilization: ${amount.times(100).roundToInt()}%"
            )

            else -> HealthStatus(
                HealthStatus.Level.ERROR,
                additionalMessage = "CPU utilization: ${amount.times(100).roundToInt()}%"
            )
        }

    /**
     * Memory usage statistics for the server.
     *
     * @property max Maximum memory available to the JVM
     * @property total Total memory currently allocated by the JVM
     * @property free Free memory within the allocated total
     * @property systemAllocated Memory allocated from the system perspective
     * @property usage Memory usage as a percentage (0.0-1.0)
     */
    @Serializable
    public data class Memory(
        val max: DataSize,
        val total: DataSize,
        val free: DataSize,
        val systemAllocated: DataSize,
        val usage: Float,
    ) {
        /**
         * Health status based on memory usage percentage.
         * - OK: < 70% utilization
         * - WARNING: 70-95% utilization
         * - URGENT: 95-100% utilization
         * - ERROR: >= 100% utilization (critically low memory)
         */
        public val status: HealthStatus
            get() = when (val amount = usage) {
                in 0f..<0.7f -> HealthStatus(HealthStatus.Level.OK)
                in 0.7f..<0.95f -> HealthStatus(
                    HealthStatus.Level.WARNING,
                    additionalMessage = "Memory utilization: ${amount.times(100).roundToInt()}%"
                )

                in 0.95f..<1f -> HealthStatus(
                    HealthStatus.Level.URGENT,
                    additionalMessage = "Memory utilization: ${amount.times(100).roundToInt()}%"
                )

                else -> HealthStatus(
                    HealthStatus.Level.ERROR,
                    additionalMessage = "Memory utilization: ${amount.times(100).roundToInt()}%"
                )
            }
    }
}

/**
 * Complete API schema definition for a Lightning Server application.
 *
 * This schema can be used to generate client SDKs and documentation. It describes all types,
 * endpoints, and interfaces exposed by the server.
 *
 * @property baseUrl Base HTTP URL for the API (e.g., "https://api.example.com")
 * @property baseWsUrl Base WebSocket URL for the API (e.g., "wss://api.example.com")
 * @property structures Map of data class type names to their structure definitions
 * @property enums Map of enum type names to their enum definitions
 * @property aliases Map of type alias names to their alias definitions
 * @property endpoints List of all API endpoint definitions
 * @property interfaces List of client interface definitions for SDK generation
 */
@Serializable
public data class LightningServerKSchema(
    val baseUrl: String,
    val baseWsUrl: String,
    val structures: Map<String, VirtualStruct>,
    val sealedStructures: Map<String, VirtualSealed> = mapOf(),
    val enums: Map<String, VirtualEnum>,
    val aliases: Map<String, VirtualAlias> = mapOf(),
    val endpoints: List<LightningServerKSchemaEndpoint>,
    val interfaces: List<LightningServerKSchemaInterface>,
)

/**
 * Describes a client interface definition for SDK generation.
 *
 * Interfaces group related endpoints together in generated client code (e.g., a REST CRUD interface).
 *
 * @property matches Type reference that this interface applies to
 * @property docGroup Optional documentation grouping identifier
 * @property path Base path for this interface's endpoints
 */
@Serializable
public data class LightningServerKSchemaInterface(
    val matches: VirtualTypeReference,
    val docGroup: String? = null,
    val path: String,
)

/**
 * Complete definition of a single API endpoint.
 *
 * Contains all information needed to call the endpoint and generate client code for it.
 *
 * @property docGroup Optional documentation grouping identifier
 * @property description Detailed description of what this endpoint does
 * @property summary Short summary of the endpoint's purpose
 * @property method HTTP method (GET, POST, PUT, PATCH, DELETE, etc.)
 * @property path URL path template for this endpoint
 * @property scopes Required authentication scopes to access this endpoint
 * @property routes Map of path parameter names to their type definitions
 * @property input Type definition for the request input
 * @property output Type definition for the response output
 * @property belongsToInterface Optional interface this endpoint belongs to for SDK generation
 */
@Serializable
public data class LightningServerKSchemaEndpoint(
    val docGroup: String? = null,
    val description: String,
    val summary: String,
    val method: String,
    val path: String,
    val scopes: Set<RequiredScope> = setOf(RequiredScope.root),
    val routes: Map<String, VirtualTypeReference>,
    val input: VirtualTypeReference,
    val output: VirtualTypeReference,
    val belongsToInterface: VirtualTypeReference?,
)

/**
 * Request for executing a single operation in a bulk batch.
 *
 * @property path API endpoint path
 * @property method HTTP method to use
 * @property body JSON-encoded request body (null for GET/DELETE)
 */
@Serializable
public data class BulkRequest(
    val path: String,
    val method: String,
    @Description("JSON")
    val body: String? = null
)

/**
 * Response from a single operation in a bulk batch.
 *
 * Contains either a successful result or an error, along with execution duration.
 *
 * @property result JSON-encoded response body if successful
 * @property error Error information if the operation failed
 * @property durationMs Time taken to execute this operation in milliseconds
 */
@Serializable
public data class BulkResponse(
    @Description("JSON")
    val result: String? = null,
    val error: LSError? = null,
    val durationMs: Long = 0L
)

/*
 * TODO: API Improvements
 *
 * 2. ServerHealth could benefit from a timestamp field to know when the health check was performed
 * 5. LightningServerKSchema could include version information for schema evolution tracking
 */
