// by Claude - public API for load testing Lightning Server endpoints
package com.lightningkite.lightningserver.loadtest

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.serialization.FormDataFormat
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.services.data.StringArrayFormat
import com.lightningkite.services.database.HasId
import kotlinx.coroutines.future.await
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import java.net.URI
import java.net.http.HttpClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import java.net.http.HttpRequest as JdkHttpRequest
import java.net.http.HttpResponse as JdkHttpResponse

/**
 * A named, weighted scenario for load testing.
 *
 * @param name Human-readable name for reporting
 * @param weight Relative probability of this scenario being assigned to a virtual user (default 1)
 * @param block The scenario logic, executed in a loop by each assigned virtual user
 */
data class Scenario(
    val name: String,
    val weight: Int = 1,
    val block: suspend ScenarioContext.() -> Unit,
)

/**
 * Summary of a load test run, returned by [loadTest] for programmatic use.
 */
data class LoadTestSummary(
    val totalRequests: Long,
    val totalErrors: Long,
    val totalDurationSeconds: Double,
    val requestsPerSecond: Double,
    val endpoints: List<EndpointSummary>,
) {
    data class EndpointSummary(
        val method: String,
        val path: String,
        val requests: Long,
        val errors: Long,
        val avgMs: Double,
        val minMs: Double,
        val maxMs: Double,
    )
}

/**
 * Runs a load test against a Lightning Server application over real HTTP.
 *
 * Builds the server definition, creates virtual user coroutines, and prints a
 * summary report with per-endpoint metrics and latency histograms.
 *
 * @param server The ServerBuilder defining the application under test
 * @param baseUrl Base URL of the running server (e.g. "http://localhost:8080")
 * @param virtualUsers Number of concurrent virtual users
 * @param sustain Duration to hold steady-state load after ramp-up completes
 * @param rampUp Duration over which virtual users are gradually started (default: no ramp-up)
 * @param headers Default HTTP headers added to every request (e.g. Authorization)
 * @param requestTimeout Per-request timeout (default: 10s)
 * @param scenarios List of scenarios to distribute across virtual users by weight
 */
suspend fun loadTest(
    server: ServerBuilder,
    baseUrl: String,
    virtualUsers: Int,
    sustain: Duration,
    rampUp: Duration = Duration.ZERO,
    headers: Map<String, String> = emptyMap(),
    requestTimeout: Duration = 10.seconds,
    serializersModule: SerializersModule = EmptySerializersModule(),
    scenarios: List<Scenario>,
): LoadTestSummary {
    val definition = server.build()
    val serialization = Serialization(serializersModule)
    val json = serialization.json
    val stringArrayFormat = serialization.stringArrayFormat

    println("Starting load test: $virtualUsers virtual users, ramp-up=${rampUp}, sustain=${sustain}")
    println("Base URL: $baseUrl")
    println("Scenarios: ${scenarios.joinToString { "${it.name}(w=${it.weight})" }}")

    val metrics = runLoadTest(
        definition = definition,
        json = json,
        stringArrayFormat = stringArrayFormat,
        baseUrl = baseUrl,
        virtualUsers = virtualUsers,
        rampUp = rampUp,
        sustain = sustain,
        headers = headers,
        requestTimeout = requestTimeout,
        scenarios = scenarios,
    )

    val totalDuration = rampUp + sustain
    printReport(metrics, totalDuration)
    return toSummary(metrics, totalDuration)
}

private fun toSummary(metrics: LoadTestMetrics, totalDuration: Duration): LoadTestSummary {
    val totalSeconds = totalDuration.inWholeMilliseconds / 1000.0
    var totalRequests = 0L
    var totalErrors = 0L
    val endpoints = metrics.endpoints.entries.map { (key, m) ->
        val requests = m.totalRequests.get()
        val errors = m.errorCount.get()
        totalRequests += requests
        totalErrors += errors
        LoadTestSummary.EndpointSummary(
            method = key.method,
            path = key.path,
            requests = requests,
            errors = errors,
            avgMs = if (requests > 0) m.totalDurationNanos.get() / requests / 1_000_000.0 else 0.0,
            minMs = if (m.minNanos.get() == Long.MAX_VALUE) 0.0 else m.minNanos.get() / 1_000_000.0,
            maxMs = m.maxNanos.get() / 1_000_000.0,
        )
    }
    return LoadTestSummary(
        totalRequests = totalRequests,
        totalErrors = totalErrors,
        totalDurationSeconds = totalSeconds,
        requestsPerSecond = if (totalSeconds > 0) totalRequests / totalSeconds else 0.0,
        endpoints = endpoints,
    )
}

/**
 * Context receiver for scenario lambdas. Provides [call] overloads to invoke
 * typed API endpoints over HTTP, with automatic path resolution, serialization,
 * and metrics recording.
 */
class ScenarioContext internal constructor(
    private val definition: ServerDefinition,
    private val json: Json,
    private val stringArrayFormat: StringArrayFormat,
    private val formDataFormat: FormDataFormat = FormDataFormat(json.serializersModule),
    private val client: HttpClient,
    private val baseUrl: String,
    private val defaultHeaders: Map<String, String>,
    private val requestTimeout: Duration,
    internal val metrics: LoadTestMetrics,
) {

    // --- PathSpec0 overloads ---

    /** Call a typed endpoint with no path arguments. */
    suspend fun <USER : HasId<*>?, INPUT, OUTPUT> call(
        endpoint: ApiHttpHandler<PathSpec0, USER, INPUT, OUTPUT>,
        input: INPUT,
    ): OUTPUT {
        val location = definition.location(endpoint)
            ?: throw IllegalArgumentException("Endpoint not found in server definition")
        val path = ResolvedPath(location.path).path(stringArrayFormat)
        return execute(location.method, path, endpoint.inputType, endpoint.outputType, input)
    }

    // --- PathSpec1 overloads ---

    /** Call a typed endpoint with one path argument. */
    suspend fun <A, USER : HasId<*>?, INPUT, OUTPUT> call(
        endpoint: ApiHttpHandler<PathSpec1<A>, USER, INPUT, OUTPUT>,
        arg1: A,
        input: INPUT,
    ): OUTPUT {
        val location = definition.location(endpoint)
            ?: throw IllegalArgumentException("Endpoint not found in server definition")
        val path = ResolvedPath(location.path, arg1).path(stringArrayFormat)
        return execute(location.method, path, endpoint.inputType, endpoint.outputType, input)
    }

    // --- PathSpec2 overloads ---

    /** Call a typed endpoint with two path arguments. */
    suspend fun <A, B, USER : HasId<*>?, INPUT, OUTPUT> call(
        endpoint: ApiHttpHandler<PathSpec2<A, B>, USER, INPUT, OUTPUT>,
        arg1: A,
        arg2: B,
        input: INPUT,
    ): OUTPUT {
        val location = definition.location(endpoint)
            ?: throw IllegalArgumentException("Endpoint not found in server definition")
        val path = ResolvedPath(location.path, arg1, arg2).path(stringArrayFormat)
        return execute(location.method, path, endpoint.inputType, endpoint.outputType, input)
    }

    // --- PathSpec3 overloads ---

    /** Call a typed endpoint with three path arguments. */
    suspend fun <A, B, C, USER : HasId<*>?, INPUT, OUTPUT> call(
        endpoint: ApiHttpHandler<PathSpec3<A, B, C>, USER, INPUT, OUTPUT>,
        arg1: A,
        arg2: B,
        arg3: C,
        input: INPUT,
    ): OUTPUT {
        val location = definition.location(endpoint)
            ?: throw IllegalArgumentException("Endpoint not found in server definition")
        val path = ResolvedPath(location.path, arg1, arg2, arg3).path(stringArrayFormat)
        return execute(location.method, path, endpoint.inputType, endpoint.outputType, input)
    }

    // --- Raw escape hatch ---

    /** Send a raw HTTP request, bypassing typed endpoint resolution. */
    suspend fun raw(method: String, path: String, body: String? = null): String {
        val url = "$baseUrl$path"
        val builder = JdkHttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(java.time.Duration.ofMillis(requestTimeout.inWholeMilliseconds))
        defaultHeaders.forEach { (k, v) -> builder.header(k, v) }

        val bodyPublisher = body?.let { JdkHttpRequest.BodyPublishers.ofString(it) }
            ?: JdkHttpRequest.BodyPublishers.noBody()
        builder.method(method, bodyPublisher)
        if (body != null) builder.header("Content-Type", "application/json")

        val startNanos = System.nanoTime()
        val response = client.sendAsync(builder.build(), JdkHttpResponse.BodyHandlers.ofString()).await()
        val durationNanos = System.nanoTime() - startNanos

        metrics.record(method, path, durationNanos, response.statusCode() >= 400)
        return response.body()
    }

    // --- Internal ---

    private suspend fun <INPUT, OUTPUT> execute(
        method: HttpMethod,
        path: String,
        inputType: KSerializer<INPUT>,
        outputType: KSerializer<OUTPUT>,
        input: INPUT,
    ): OUTPUT {
        val isGetLike = method == HttpMethod.GET || method == HttpMethod.HEAD
        val methodStr = method.toString()

        val url: String
        val bodyString: String?

        if (isGetLike) {
            // GET/HEAD: encode input as query parameters
            if (inputType == Unit.serializer()) {
                url = "$baseUrl$path"
                bodyString = null
            } else {
                val queryString = formDataFormat.encodeToString(inputType, input)
                url = if (queryString.isNotEmpty()) "$baseUrl$path?$queryString" else "$baseUrl$path"
                bodyString = null
            }
        } else {
            // POST/PUT/PATCH/DELETE: encode input as JSON body
            url = "$baseUrl$path"
            bodyString = if (inputType == Unit.serializer()) null
            else json.encodeToString(inputType, input)
        }

        val builder = JdkHttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(java.time.Duration.ofMillis(requestTimeout.inWholeMilliseconds))
        defaultHeaders.forEach { (k, v) -> builder.header(k, v) }
        builder.header("Accept", "application/json")

        val bodyPublisher = bodyString?.let { JdkHttpRequest.BodyPublishers.ofString(it) }
            ?: JdkHttpRequest.BodyPublishers.noBody()
        builder.method(methodStr, bodyPublisher)
        if (bodyString != null) builder.header("Content-Type", "application/json")

        val startNanos = System.nanoTime()
        try {
            val response = client.sendAsync(builder.build(), JdkHttpResponse.BodyHandlers.ofString()).await()
            val durationNanos = System.nanoTime() - startNanos
            val isError = response.statusCode() >= 400

            metrics.record(methodStr, path, durationNanos, isError)

            if (isError) {
                throw LoadTestHttpException(response.statusCode(), response.body())
            }

            @Suppress("UNCHECKED_CAST")
            if (outputType == Unit.serializer()) return Unit as OUTPUT
            return json.decodeFromString(outputType, response.body())
        } catch (e: LoadTestHttpException) {
            throw e
        } catch (e: Exception) {
            metrics.recordException(methodStr, path)
            throw e
        }
    }
}

/** Exception thrown when a load test HTTP request returns an error status code. */
class LoadTestHttpException(val statusCode: Int, val body: String) :
    RuntimeException("HTTP $statusCode: ${body.take(200)}")
