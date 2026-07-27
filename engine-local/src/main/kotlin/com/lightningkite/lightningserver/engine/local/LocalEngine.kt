package com.lightningkite.lightningserver.engine.local

import com.lightningkite.lightningserver.data.Schedule
import com.lightningkite.lightningserver.data.plus
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.logger
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.path
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.settings.ServerSettings
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionMessage
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionRequest
import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryKey
import com.lightningkite.services.Service
import com.lightningkite.services.cache.*
import com.lightningkite.services.pubsub.PubSub
import com.lightningkite.services.pubsub.PubSubChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.datetime.*
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

private val scheduleNameKey = TelemetryKey.OfString("schedule.name")

/**
 * Server setting for configuring the PubSub implementation used by the local engine.
 * Used for WebSocket subscription messages and inter-process communication.
 */
public val enginePubSub: ServerSetting<PubSub.Settings, PubSub> =
    ServerSetting("engine-pubSub", PubSub.Settings(), PubSub.Settings.serializer())

/**
 * Server setting for configuring the Cache implementation used by the local engine.
 * Used for distributed locking and schedule coordination.
 */
public val engineCache: ServerSetting<Cache.Settings, Cache> =
    ServerSetting("engine-cache", Cache.Settings(), Cache.Settings.serializer())

/**
 * When true, local engines will use pub/sub for WebSocket handlers even when
 * direct execution is available (i.e., when the handler implements [DirectExecutableWebSocketHandler]).
 *
 * This is useful for testing distributed behavior locally, as it forces the same
 * code path that would be used in serverless environments like AWS Lambda.
 *
 * Default is false, meaning local engines will automatically use direct execution
 * when available for better performance.
 */
public val forceWebSocketPubSub: ServerSetting.Direct<Boolean> = ServerSetting(
    "forceWebSocketPubSub",
    false,
    kotlinx.serialization.serializer(),
    optional = true
)

/**
 * Base class for local server engines that run within a single JVM process.
 *
 * This engine is primarily used for:
 * - Unit testing with the `LocalEngine` implementation
 * - Local development servers
 * - Engines that need in-process task execution and schedule management
 *
 * The engine handles:
 * - WebSocket subscriptions via PubSub
 * - Background task execution
 * - Scheduled task coordination using distributed locks
 *
 * @param server The server definition to run
 */
public abstract class LocalEngine(server: ServerDefinition) : ServerRuntimeBase(server) {
    /**
     * The coroutine scope used for launching background tasks and schedules.
     * Defaults to GlobalScope but can be overridden for testing or custom lifecycle management.
     */
    @OptIn(DelicateCoroutinesApi::class)
    protected open val scope: CoroutineScope = GlobalScope

    /**
     * A unique identifier for this server instance, derived from the network interface's hardware address.
     * Falls back to "?" if no network interfaces are available.
     */
    public override val serverId: String = NetworkInterface.getNetworkInterfaces().toList()
        .minByOrNull { it.name }
        ?.hardwareAddress
        ?.sumOf { it.hashCode() }
        ?.toString(16)
        ?: "?"

    /**
     * The version of this server instance. Always "Unknown" for local engines.
     */
    public override val serverVersion: String = "Unknown"

    override val settings: ServerSettings = super.settings + listOf(
        enginePubSub,
        engineCache,
        forceWebSocketPubSub,
    )

    /**
     * The PubSub instance used for WebSocket subscriptions and inter-process messaging.
     */
    public val pubSub: PubSub by lazy { enginePubSub() }

    /**
     * The Cache instance used for distributed locking and schedule coordination.
     */
    public val cache: Cache by lazy { engineCache() }

    /**
     * Jobs for the schedule-polling coroutines launched by [startSchedules], retained so that
     * [gracefulShutdown] can cancel them before draining in-flight work.
     * TODO: This is kinda weird - why not just use a normal parent job and cancel from there?  That's what it's for!
     */
    private val scheduleJobs = mutableListOf<Job>()

    /** Guards [gracefulShutdown] so it runs at most once even if invoked from both a hook and an engine stop. */
    private val shutdownStarted = AtomicBoolean(false)

    /**
     * Gets a PubSub channel for a WebSocket subscription message.
     */
    protected fun <PATH : PathSpec, T> pubSubChannel(event: WebSocketSubscriptionMessage<PATH, T>): PubSubChannel<T> =
        pubSub.get(event.path(), event.topic.type)

    /**
     * Gets a PubSub channel for a WebSocket subscription request.
     */
    protected fun <PATH : PathSpec, T> pubSubChannel(event: WebSocketSubscriptionRequest<PATH, T>): PubSubChannel<T> =
        pubSub.get(event.path(), event.topic.type)

    /**
     * Sends a WebSocket subscription message by emitting it to the appropriate PubSub channel.
     */
    override suspend fun <PATH : PathSpec, T> sendWebSocketSubscriptionMessage(event: WebSocketSubscriptionMessage<PATH, T>) {
        pubSubChannel(event).emit(event.value)
    }

    /**
     * Collects WebSocket events from a subscription request.
     *
     * @param event The subscription request
     * @param collector The function to call for each received event
     */
    protected suspend fun <PATH : PathSpec, T> webSocketEventCollect(
        event: WebSocketSubscriptionRequest<PATH, T>,
        collector: suspend (T) -> Unit,
    ) {
        pubSubChannel(event).collect(collector)
    }

    /**
     * Invokes a background task asynchronously.
     * The task is launched in the engine's coroutine scope and errors are caught and reported.
     *
     * @param input The input value for the task
     */
    override suspend fun <T> Task<T>.invoke(input: T) {
        scope.launch {
            try {
                logger.debug { "Handling task: $location" }
                executeWithMetrics(location, input)
            } catch (e: Exception) {
                /*squish; already reported*/
            }
        }
    }

    /**
     * Starts all scheduled tasks defined in the server.
     *
     * Each schedule runs in its own coroutine and uses distributed locking via cache
     * to ensure only one instance runs the task at a time. This allows multiple server
     * instances to coordinate without duplicate execution.
     *
     * The schedule's next run time is stored in cache to persist across server restarts.
     *
     * @param scheduleLockTtl Expiry of the per-tick distributed lock (see
     *   [EngineReliabilitySettings.scheduleLockTtl]). The lock is released as soon as the tick
     *   finishes or on graceful shutdown, so this only matters as a backstop after a hard crash.
     */
    protected fun startSchedules(scheduleLockTtl: Duration = 1.hours) {
        server.schedules.forEach { locationed ->
            val location = locationed.key
            val it = locationed.value
            val name = location.toString()

            fun Schedule.calculateNextRun(now: Instant): Long {
                return when (this) {
                    is Schedule.Frequency -> now.toEpochMilliseconds() + gap.inWholeMilliseconds
                    is Schedule.Daily -> {
                        val local = now.toLocalDateTime(zone)
                        LocalDateTime(
                            if (local.time > time)
                                local.date.plus(DatePeriod(days = 1))
                            else
                                local.date,
                            time
                        )
                            .toInstant(zone)
                            .toEpochMilliseconds()
                    }

                    is Schedule.Cron -> now
                        .toLocalDateTime(zone)
                        .plus(cron)
                        .toInstant(zone)
                        .toEpochMilliseconds()
                }
            }


            @Suppress("OPT_IN_USAGE")
            val job = scope.launch {
                while (true) {
                    val upcomingRun = instrument("schedule.poll $name", TelemetryAttributes { put(scheduleNameKey, name) }) {
                        cache.get<Long>("$name-nextRun") ?: run {
                            val time = it.schedule.calculateNextRun(clock.now())
                            cache.set<Long>("$name-nextRun", time)
                            time
                        }
                    }
                    delay((upcomingRun - System.currentTimeMillis()).coerceAtLeast(1L))
                    val nextRun = it.schedule.calculateNextRun(clock.now())
                    val lockAcquired = instrument("schedule.tick $name", TelemetryAttributes { put(scheduleNameKey, name) }) {
                        if (cache.setIfNotExists("$name-lock", true)) {
                            cache.set("$name-lock", true, scheduleLockTtl)
                            try {
                                logger.debug { "Running Schedule: $name" }
                                it.executeWithMetrics(location)
                                cache.set<Long>("$name-nextRun", nextRun)
                            } catch (e: CancellationException) {
                                // Shutdown (or scope cancellation) interrupted this tick — honor it and stop the
                                // loop rather than swallowing it. The lock is still released in `finally` below.
                                throw e
                            } catch (e: Exception) {
                                /*squish; already reported*/
                            } finally {
                                // Always release the lock, even if the tick was cancelled, so a mid-tick shutdown
                                // can't leave "$name-lock" stuck until its TTL expires. NonCancellable guards ONLY this
                                // fast cleanup — the task itself stays cooperatively cancellable, so a tick longer
                                // than the shutdown window is interrupted (not run to completion uninterruptibly,
                                // which would just be hard-killed dirty when the process exits).
                                withContext(NonCancellable) { cache.remove("$name-lock") }
                            }
                            true
                        } else {
                            false
                        }
                    }
                    if (!lockAcquired) delay(1000L)
                }
            }
            scheduleJobs.add(job)
        }
    }

    /**
     * Creates the bounded inbound channel used to buffer WebSocket frames between the socket reader
     * and a [com.lightningkite.lightningserver.websockets.DirectExecutableWebSocketHandler].
     *
     * The capacity and overflow behavior come from [EngineReliabilitySettings.webSocketInboundBuffer]
     * and [EngineReliabilitySettings.webSocketOversizePolicy]. For [WsOversizePolicy.CLOSE] the
     * channel is created with [kotlinx.coroutines.channels.BufferOverflow.SUSPEND] and the engine's
     * reader is expected to detect a full channel and close the socket with code 1009; see each
     * engine's reader loop. [WsOversizePolicy.DROP_OLDEST] uses the channel's drop-oldest overflow,
     * and [WsOversizePolicy.SUSPEND] applies natural backpressure by suspending the sender.
     */
    protected fun <T> newWebSocketInboundChannel(reliability: EngineReliabilitySettings): Channel<T> {
        val capacity = reliability.webSocketInboundBuffer.coerceAtLeast(1)
        return when (reliability.webSocketOversizePolicy) {
            WsOversizePolicy.CLOSE, WsOversizePolicy.SUSPEND ->
                Channel(capacity, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.SUSPEND)
            WsOversizePolicy.DROP_OLDEST ->
                Channel(capacity, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
        }
    }

    /**
     * Performs a graceful shutdown shared by all local engines:
     *
     * 1. Cancels the schedule-polling coroutines so no new scheduled work starts.
     * 2. Invokes [drainInFlight] to stop accepting new connections and wait (up to
     *    [EngineReliabilitySettings.shutdownDrainTimeout]) for in-flight requests to finish. Each
     *    engine supplies its own drain because the mechanism is engine-specific (Netty event-loop
     *    shutdown, JDK `HttpServer.stop`, Ktor `ApplicationEngine.stop`).
     * 3. Disconnects every service goal (mirrors the AWS adapter's connect/disconnect loop) so
     *    pooled connections are released cleanly.
     * 4. Cancels the engine [scope].
     *
     * Idempotent: only the first invocation runs; subsequent calls return immediately.
     *
     * Note: this does not close [sharedResources] — closing those is owned by service-abstractions
     * and is intentionally out of scope here. Engines close only the resources they themselves own
     * (e.g. the JDK thread pool).
     *
     * @param drainTimeout Maximum time to wait for in-flight requests during [drainInFlight].
     * @param drainInFlight Engine-specific routine that stops accepting connections and drains
     *   in-flight work; it is given [drainTimeout] as a hint.
     */
    protected fun gracefulShutdown(drainTimeout: Duration, drainInFlight: (Duration) -> Unit) {
        if (!shutdownStarted.compareAndSet(false, true)) return
        logger.info { "Graceful shutdown started." }
        scheduleJobs.forEach { it.cancel() }
        val cancelledScheduleJobs = scheduleJobs.toList()
        scheduleJobs.clear()
        try {
            drainInFlight(drainTimeout)
        } catch (e: Throwable) {
            logger.warn(e) { "Error while draining in-flight requests during shutdown." }
        }
        runBlocking {
            // Give in-flight schedule ticks a bounded chance to unwind and release their locks WHILE the
            // services they depend on are still connected. A tick longer than the window is abandoned — a
            // bounded shutdown grace period cannot guarantee completion of arbitrarily long tasks.
            withTimeoutOrNull(drainTimeout) { cancelledScheduleJobs.joinAll() }
            settings.allGoals().values.forEach { goal ->
                (goal as? Service)?.let {
                    try {
                        logger.debug { "Disconnecting ${it.name}..." }
                        it.disconnect()
                    } catch (e: Throwable) {
                        logger.warn(e) { "Error disconnecting service ${it.name} during shutdown." }
                    }
                }
            }
        }
        // Cancel the engine scope's Job if it has one. The default scope is GlobalScope, which has no
        // Job and cannot be cancelled; engines with a managed scope (e.g. Netty) get cancelled here.
        scope.coroutineContext[Job]?.cancel()
        logger.info { "Graceful shutdown complete." }
    }

    /**
     * Runs all pre-deploy tasks exactly once and returns, without ever binding a port or starting
     * schedules. Intended as the body of a `predeploy` command invoked once per deploy, before the
     * new version is cut over.
     *
     * Settings must already be loaded (e.g. via [com.lightningkite.lightningserver.settings.loadFromFile]).
     * If any pre-deploy task fails the exception propagates, so the caller can exit non-zero and the
     * deploy pipeline can abort the cutover. Services are disconnected afterwards so the process can
     * exit cleanly.
     */
    /**
     * Runs all pre-deploy tasks and returns, leaving services connected. Intended to be called just
     * before [start] in a combined "prepare then serve" dev command, so a single local process
     * reconciles the database and then serves. Settings must already be ready.
     */
    public fun runPreDeployTasksBlocking() {
        runBlocking { runPreDeployTasks() }
    }

    public fun runPreDeploy() {
        settings.ready()
        try {
            runBlocking { runPreDeployTasks() }
        } finally {
            runBlocking {
                settings.allGoals().values.forEach { goal ->
                    (goal as? Service)?.let {
                        try {
                            it.disconnect()
                        } catch (e: Throwable) {
                            logger.warn(e) { "Error disconnecting service ${it.name} after pre-deploy." }
                        }
                    }
                }
            }
        }
    }

    /**
     * Registers a JVM shutdown hook (fired on SIGTERM/SIGINT) that runs [action] exactly once.
     * Use this to wire [gracefulShutdown] into the process lifecycle so rolling deploys drain
     * in-flight requests instead of dropping them.
     */
    protected fun registerShutdownHook(action: () -> Unit) {
        val fired = AtomicBoolean(false)
        // Fully-qualified: bare `Runtime` resolves to Lightning Server's own Runtime type here.
        java.lang.Runtime.getRuntime().addShutdownHook(Thread {
            if (fired.compareAndSet(false, true)) {
                try {
                    action()
                } catch (e: Throwable) {
                    logger.warn(e) { "Error in shutdown hook." }
                }
            }
        })
    }
}

/*
 * TODO: API Recommendations
 *
 * 1. The serverId generation could fail or produce "?" - consider documenting this behavior more clearly
 *    or providing a way to override it for testing
 * 2. The startSchedules() function launches infinite loops but provides no way to stop them. Consider
 *    adding a shutdown/cleanup method
 * 3. Consider adding a method to manually trigger a schedule for testing purposes
 * 4. The schedule lock mechanism could lead to missed executions if a task takes longer than the
 *    configured scheduleLockTtl. Consider documenting this limitation or adding monitoring
 * 6. GlobalScope usage is marked as DelicateCoroutinesApi - consider providing guidance on when/how
 *    to override the scope property for proper structured concurrency
 */