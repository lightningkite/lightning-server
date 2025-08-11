package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.Schedule
import com.lightningkite.lightningserver.Task
import com.lightningkite.lightningserver.definition.Locationed
import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.path
import com.lightningkite.lightningserver.plus
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionMessage
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionRequest
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant


public abstract class SingleMachineEngine(server: ServerDefinition): ServerRuntimeBase(server) {
    private val subscriptions = ConcurrentHashMap<WebSocketSubscriptionRequest<*, *>, ArrayList<suspend (WebSocketSubscriptionMessage<*, *>)->Unit>>()
    override suspend fun <PATH : PathSpec, T> sendWebSocketSubscriptionMessage(event: WebSocketSubscriptionMessage<PATH, T>) {
        subscriptions[WebSocketSubscriptionRequest(topic = event.topic, rawPathArguments = event.rawPathArguments)]?.forEach {
            GlobalScope.launch {
                it(event)
            }
        }
    }

    override suspend fun <T> Locationed<PathSpec0, Task<T>>.invoke(input: T) {
        GlobalScope.launch {
            item.executeWithMetrics(location, input)
        }
    }

    private val task_nextRun = ConcurrentHashMap<String, Long>()
    private val task_lock = ConcurrentHashMap<String, Boolean>()
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
            GlobalScope.launch {
                while (true) {
                    val upcomingRun = task_nextRun["$name-nextRun"] ?: run {
                        val time = it.schedule.calculateNextRun(clock.now())
                        task_nextRun["$name-nextRun"] = time
                        time
                    }
                    delay((upcomingRun - System.currentTimeMillis()).coerceAtLeast(1L))
                    val nextRun = it.schedule.calculateNextRun(clock.now())
                    if (task_lock.putIfAbsent("$name-lock", true) == null) {
                        it.executeWithMetrics(location)
                        task_nextRun["$name-nextRun"] = nextRun
                        task_lock.remove("$name-lock")
                    } else {
                        delay(1000L)
                    }
                }
            }
        }
    }
}