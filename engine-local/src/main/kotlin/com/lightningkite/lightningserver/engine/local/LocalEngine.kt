package com.lightningkite.lightningserver.engine.local

import com.lightningkite.lightningserver.data.Schedule
import com.lightningkite.lightningserver.data.plus
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.path
import com.lightningkite.lightningserver.runtime.ServerRuntimeBase
import com.lightningkite.lightningserver.runtime.executeWithMetrics
import com.lightningkite.lightningserver.runtime.location
import com.lightningkite.lightningserver.settings.ServerSettings
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionMessage
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionRequest
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.cache.get
import com.lightningkite.services.cache.set
import com.lightningkite.services.cache.setIfNotExists
import com.lightningkite.services.pubsub.PubSub
import com.lightningkite.services.pubsub.PubSubChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.*
import java.net.NetworkInterface
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

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

    override val settings: ServerSettings = super.settings + listOf(enginePubSub, engineCache)

    /**
     * The PubSub instance used for WebSocket subscriptions and inter-process messaging.
     */
    public val pubSub: PubSub by lazy { enginePubSub() }

    /**
     * The Cache instance used for distributed locking and schedule coordination.
     */
    public val cache: Cache by lazy { engineCache() }

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
     */
    protected fun startSchedules() {
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
            scope.launch {
                while (true) {
                    val upcomingRun = cache.get<Long>("$name-nextRun") ?: run {
                        val time = it.schedule.calculateNextRun(clock.now())
                        cache.set<Long>("$name-nextRun", time)
                        time
                    }
                    delay((upcomingRun - System.currentTimeMillis()).coerceAtLeast(1L))
                    val nextRun = it.schedule.calculateNextRun(clock.now())
                    if (cache.setIfNotExists("$name-lock", true)) {
                        cache.set("$name-lock", true, 1.hours)
                        try {
                            it.executeWithMetrics(location)
                        } catch (e: Exception) {
                            /*squish; already reported*/
                        }
                        cache.set<Long>("$name-nextRun", nextRun)
                        cache.remove("$name-lock")
                    } else {
                        delay(1000L)
                    }
                }
            }
        }
    }
}

/*
 * TODO: API Recommendations
 *
 * 1. The serverId generation could fail or produce "?" - consider documenting this behavior more clearly
 *    or providing a way to override it for testing
 * 2. The startSchedules() function launches infinite loops but provides no way to stop them. Consider
 *    adding a shutdown/cleanup method
 * 3. The 1-hour lock timeout in startSchedules() is hardcoded - consider making this configurable
 * 4. Consider adding a method to manually trigger a schedule for testing purposes
 * 5. The schedule lock mechanism could lead to missed executions if a task takes longer than 1 hour.
 *    Consider documenting this limitation or adding monitoring
 * 6. GlobalScope usage is marked as DelicateCoroutinesApi - consider providing guidance on when/how
 *    to override the scope property for proper structured concurrency
 */