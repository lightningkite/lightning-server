package com.lightningkite.lightningserver.typed

import com.lightningkite.ZonedDateTime
import com.lightningkite.atZone
import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.auth.or
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.ScheduledTask
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.PathSpec1
import com.lightningkite.lightningserver.pathing.arg1
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.typed.sdk.SdkModule.Companion.withSdkInfo
import com.lightningkite.lightningserver.typed.sdk.module
import com.lightningkite.services.HealthStatus
import com.lightningkite.services.database.*
import kotlinx.coroutines.flow.toCollection
import kotlinx.datetime.*
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid


public class FunnelEndpoints(
    database: Runtime<Database>,
    read: AuthRequirement<HasId<*>> = AuthRequirement.IsAdmin,
    private val zone: TimeZone = TimeZone.of("America/Denver"),
) : ServerBuilder() {

    public val summaryInfo: ModelInfo<HasId<*>, FunnelSummary, Uuid> = database.modelInfo(
        auth = read,
        permissions = { ModelPermissions.allowAll() }
    )

    public val summaryRest: ModelRestEndpoints<HasId<*>, FunnelSummary, Uuid> =
        path.path("summary").path("rest") module ModelRestEndpoints(summaryInfo).withSdkInfo(valueName = "summaries")

    public val info: ModelInfo<HasId<*>, FunnelInstance, Uuid> = database.modelInfo(
        auth = read,
        permissions = { ModelPermissions.allowAll() }
    )

    public val rest: ModelRestEndpoints<HasId<*>, FunnelInstance, Uuid> =
        path.path("instance").path("rest") module ModelRestEndpoints(info).withSdkInfo(valueName = "instances")


    public val summaries: ApiHttpHandler<PathSpec1<LocalDate>, HasId<*>?, Unit, java.util.HashSet<FunnelSummary>> =
        path.path("summaries").arg<LocalDate>("date").get bind ApiHttpHandler(
            auth = read,
            summary = "Get Funnel Health",
            description = "Gets the current status of the funnels",
            errorCases = listOf(),
            implementation = { _: Unit ->
                summaryInfo.table().find(condition { it.date.eq(arg1) }).toCollection(HashSet())
            }
        )

    context(server: ServerRuntime)
    public suspend fun summarize(targetDate: LocalDate = now().atZone(zone).date.minus(1, DateTimeUnit.DAY)) {
        val dayAfter = targetDate.plus(1, DateTimeUnit.DAY)
        val start = ZonedDateTime(LocalDateTime(targetDate, LocalTime(8, 0, 0)), zone).toInstant()
        val end = ZonedDateTime(LocalDateTime(dayAfter, LocalTime(8, 0, 0)), zone).toInstant()

        class Data {
            var expectedErrorRate: Float = 0f
            var success: Int = 0
            var successAfterError: Int = 0
            var error: Int = 0
            var abandoned: Int = 0
            var count: Int = 0
        }

        val data = HashMap<String, Data>()
        info.table().find(condition { it.started.gt(start) and it.started.lt(end) })
            .collect {
                val d = data.getOrPut(it.funnel) { Data() }
                d.expectedErrorRate = it.expectedErrorRate
                if (it.success != null) {
                    if (it.errors.isEmpty()) {
                        d.success++
                    } else {
                        d.successAfterError++
                    }
                } else {
                    if (it.errors.isEmpty()) {
                        d.abandoned++
                    } else {
                        d.error++
                    }
                }
                d.count++
            }
        summaryInfo.table().deleteMany(condition { it.date.eq(targetDate) })
        summaryInfo.table().insertMany(data.entries.map {
            val errorRate = it.value.error / it.value.count.toFloat()
            FunnelSummary(
                funnel = it.key,
                date = targetDate,
                success = it.value.success / it.value.count.toFloat(),
                successAfterError = it.value.successAfterError / it.value.count.toFloat(),
                error = it.value.error / it.value.count.toFloat(),
                abandoned = it.value.abandoned / it.value.count.toFloat(),
                count = it.value.count,
                status = when {
                    errorRate < it.value.expectedErrorRate.div(2) -> HealthStatus.Level.OK
                    errorRate < it.value.expectedErrorRate -> HealthStatus.Level.WARNING
                    else -> HealthStatus.Level.ERROR
                },
            )
        })
    }

    public val summarizeOnSchedule: ScheduledTask =
        path.path("summarize") bind ScheduledTask(timeOfDay = LocalTime(8, 0, 0), timeZone = zone) {
            summarize()
        }

    public val summarizeNow: ApiHttpHandler<PathSpec0, HasId<*>?, LocalDate?, Unit> =
        path.path("summarize-now").post bind ApiHttpHandler(
            summary = "Summarize Funnels Now",
            description = "",
            auth = read,
            successCode = HttpStatus.OK,
            errorCases = emptyList(),
            implementation = { day: LocalDate? ->
                summarize(day ?: now().atZone(zone).date)
            }
        )

    public val start:  ApiHttpHandler<PathSpec0, HasId<*>?, FunnelStart, Uuid> =
        path.path("start").post bind ApiHttpHandler(
            auth = read or noAuth,
            summary = "Start Funnel Instance"
        ) { input: FunnelStart ->
            info.table().insertOne(
                FunnelInstance(
                    funnel = input.funnel,
                    userAgent = input.userAgent,
                    user = authOrNull?.toString(),
                    version = input.version,
                    started = now(),
                    expiry = now().plus(input.expireAfterMinutes.minutes)
                )
            )!!._id
        }

    public val error: ApiHttpHandler<PathSpec1<Uuid>, HasId<*>?, String, Unit> =
        path.path("error").arg<Uuid>("id").post bind ApiHttpHandler(
            auth = noAuth,
            summary = "Error Funnel Instance"
        ) { input: String ->
            info.table().updateOneById(arg1, modification {
                it.errors += input
            })
            Unit
        }


    public val step: ApiHttpHandler<PathSpec1<Uuid>, HasId<*>?, Int, Unit> =
        path.path("step").arg<Uuid>("id").post bind ApiHttpHandler(
            auth = noAuth,
            summary = "Set Step Funnel Instance"
        ) { step: Int ->
            info.table().updateOneById(arg1, modification {
                it.step.coerceAtLeast(step)
            })
            Unit
        }

    public val success: ApiHttpHandler<PathSpec1<Uuid>, HasId<*>?, Unit, Unit> =
        path.path("success").arg<Uuid>("id").post bind ApiHttpHandler(
            auth = noAuth,
            summary = "Success Funnel Instance"
        ) { _: Unit ->
            info.table().updateOneById(arg1, modification {
                it.success assign now()
            })
            Unit
        }

}