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

public val enginePubSub: ServerSetting<PubSub.Settings, PubSub> =
    ServerSetting("pubSub", PubSub.Settings(), PubSub.Settings.serializer())
public val engineCache: ServerSetting<Cache.Settings, Cache> =
    ServerSetting("cache", Cache.Settings(), Cache.Settings.serializer())

public abstract class LocalEngine(server: ServerDefinition) : ServerRuntimeBase(server) {
    @OptIn(DelicateCoroutinesApi::class)
    protected open val scope: CoroutineScope = GlobalScope

    public override val serverId:String = NetworkInterface.getNetworkInterfaces().toList()
        .minByOrNull { it.name }
        ?.hardwareAddress
        ?.sumOf { it.hashCode() }
        ?.toString(16)
        ?: "?"
    public override val serverVersion:String =  "Unknown"

    override val settings: ServerSettings = ServerSettings(
        server.settings.plus(
            listOf(
                generalSettings,
                metricsSettings,
                secretBasis,
                enginePubSub,
                engineCache,
            )
        ).distinctBy { it.name }.toSet()
    )

    public val pubSub: PubSub by lazy { enginePubSub() }
    public val cache: Cache by lazy { engineCache() }

    protected fun <PATH : PathSpec, T> pubSubChannel(event: WebSocketSubscriptionMessage<PATH, T>): PubSubChannel<T> =
        pubSub.get(event.path(internalSerialization.stringArrayFormat), event.topic.type)

    protected fun <PATH : PathSpec, T> pubSubChannel(event: WebSocketSubscriptionRequest<PATH, T>): PubSubChannel<T> =
        pubSub.get(event.path(internalSerialization.stringArrayFormat), event.topic.type)

    override suspend fun <PATH : PathSpec, T> sendWebSocketSubscriptionMessage(event: WebSocketSubscriptionMessage<PATH, T>) {
        pubSubChannel(event).emit(event.value)
    }

    protected suspend fun <PATH : PathSpec, T> webSocketEventCollect(
        event: WebSocketSubscriptionRequest<PATH, T>,
        collector: suspend (T) -> Unit,
    ) {
        pubSubChannel(event).collect(collector)
    }

    override suspend fun <T> Task<T>.invoke(input: T) {
        scope.launch {
            try {
                executeWithMetrics(location, input)
            } catch (e: Exception) {
                /*squish; already reported*/
            }
        }
    }

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