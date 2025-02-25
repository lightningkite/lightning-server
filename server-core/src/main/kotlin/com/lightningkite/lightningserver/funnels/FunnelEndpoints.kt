package com.lightningkite.lightningserver.funnels

import com.lightningkite.UUID
import com.lightningkite.ZonedDateTime
import com.lightningkite.atZone
import com.lightningkite.lightningdb.Condition
import com.lightningkite.lightningdb.Database
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.ModelPermissions
import com.lightningkite.lightningdb.and
import com.lightningkite.lightningdb.condition
import com.lightningkite.lightningdb.eq
import com.lightningkite.lightningdb.gt
import com.lightningkite.lightningdb.insertMany
import com.lightningkite.lightningdb.insertOne
import com.lightningkite.lightningdb.lt
import com.lightningkite.lightningdb.modification
import com.lightningkite.lightningdb.sort
import com.lightningkite.lightningdb.updateOneById
import com.lightningkite.lightningserver.auth.AuthOption
import com.lightningkite.lightningserver.auth.AuthOptions
import com.lightningkite.lightningserver.auth.AuthType
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.anyAuth
import com.lightningkite.lightningserver.auth.authOptions
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.db.ModelRestEndpoints
import com.lightningkite.lightningserver.db.ModelSerializationInfo
import com.lightningkite.lightningserver.db.modelInfo
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.monitoring.FunnelInstance
import com.lightningkite.lightningserver.monitoring.FunnelStart
import com.lightningkite.lightningserver.monitoring.FunnelSummary
import com.lightningkite.lightningserver.monitoring.date
import com.lightningkite.lightningserver.monitoring.errors
import com.lightningkite.lightningserver.monitoring.started
import com.lightningkite.lightningserver.monitoring.step
import com.lightningkite.lightningserver.monitoring.success
import com.lightningkite.lightningserver.routes.docName
import com.lightningkite.lightningserver.schedule.schedule
import com.lightningkite.lightningserver.serverhealth.HealthStatus
import com.lightningkite.lightningserver.typed.api
import com.lightningkite.lightningserver.typed.arg
import com.lightningkite.lightningserver.typed.get
import com.lightningkite.lightningserver.typed.path1
import com.lightningkite.lightningserver.typed.post
import com.lightningkite.now
import kotlinx.coroutines.flow.toCollection
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.time.Duration.Companion.minutes

class FunnelEndpoints(
    database: () -> Database,
    read: AuthOptions<*> = Authentication.isDeveloper,
    val zone: TimeZone = TimeZone.of("America/Denver"),
    path: ServerPath = ServerPath.root.path("meta/funnels")
): ServerPathGroup(path) {

    val summaryInfo = database.modelInfo(
        serialization = ModelSerializationInfo<FunnelSummary, UUID>(),
        authOptions = read as AuthOptions<HasId<*>?>,
        permissions = { ModelPermissions.allowAll() }
    )
    val summaryRest = ModelRestEndpoints(path("summary/rest"), summaryInfo)

    val info = database.modelInfo(
        serialization = ModelSerializationInfo<FunnelInstance, UUID>(),
        authOptions = read as AuthOptions<HasId<*>?>,
        permissions = { ModelPermissions.allowAll() }
    )

    val rest = ModelRestEndpoints(path("instance/rest"), info)
    init { path.docName = rest.path.docName }

    val summaries = path("summaries").arg<LocalDate>("date").get.api(
        authOptions = read as AuthOptions<HasId<*>?>,
        summary = "Get Funnel Health",
        description = "Gets the current status of the funnels",
        errorCases = listOf(),
        implementation = { _: Unit ->
            summaryInfo.collection().find(condition { it.date.eq(path1) }).toCollection(HashSet())
        }
    )

    suspend fun summarize(targetDate: LocalDate = now().atZone(zone).date.minus(1, DateTimeUnit.DAY)) {
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
        info.collection().find(condition { it.started.gt(start) and it.started.lt(end) })
            .collect {
                val d = data.getOrPut(it.funnel) { Data() }
                d.expectedErrorRate = it.expectedErrorRate
                if(it.success != null) {
                    if(it.errors.isEmpty()) {
                        d.success++
                    } else {
                        d.successAfterError++
                    }
                } else {
                    if(it.errors.isEmpty()) {
                        d.abandoned++
                    } else {
                        d.error++
                    }
                }
                d.count++
            }
        summaryInfo.collection().deleteMany(condition { it.date.eq(targetDate) })
        summaryInfo.collection().insertMany(data.entries.map {
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

    val summarizeOnSchedule = schedule("$path/summarize", LocalTime(8, 0, 0), zone) {
        summarize()
    }

    val summarizeNow = path("summarize-now").post.api(
        summary = "Summarize Funnels Now",
        authOptions = read as AuthOptions<HasId<*>?>,
    ) { day: LocalDate? ->
        summarize(day ?: now().atZone(zone).date)
    }

    val start = path("start").post.api(
        authOptions = AuthOptions(setOf(null, AuthOption(AuthType.any, scopes = setOf()))),
        summary = "Start Funnel Instance"
    ) { input: FunnelStart ->
        info.collection().insertOne(FunnelInstance(
            funnel = input.funnel,
            userAgent = input.userAgent,
            user = authOrNull?.toString(),
            version = input.version,
            expiry = now().plus(input.expireAfterMinutes.minutes)
        ))!!._id
    }

    val error = path("error").arg<UUID>("id").post.api(
        authOptions = noAuth,
        summary = "Error Funnel Instance"
    ) { input: String ->
        info.collection().updateOneById(path1, modification {
            it.errors += input
        })
        Unit
    }
    val step = path("step").arg<UUID>("id").post.api(
        authOptions = noAuth,
        summary = "Set Step Funnel Instance"
    ) { step: Int ->
        info.collection().updateOneById(path1, modification {
            it.step.coerceAtLeast(step)
        })
        Unit
    }
    val success = path("success").arg<UUID>("id").post.api(
        authOptions = noAuth,
        summary = "Success Funnel Instance"
    ) { _: Unit ->
        info.collection().updateOneById(path1, modification {
            it.success assign now()
        })
        Unit
    }

}