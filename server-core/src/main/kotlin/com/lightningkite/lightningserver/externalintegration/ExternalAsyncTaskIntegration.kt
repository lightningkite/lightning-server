package com.lightningkite.lightningserver.externalintegration

import com.lightningkite.prepareModelsServerCore
import com.lightningkite.lightningdb.*
import com.lightningkite.serialization.*
import com.lightningkite.lightningserver.auth.AuthOptions
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.db.modelInfoWithDefault
import com.lightningkite.lightningserver.db.ModelRestEndpoints
import com.lightningkite.lightningserver.db.ModelSerializationInfo
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.exceptions.report
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.routes.docName
import com.lightningkite.lightningserver.schedule.schedule
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.tasks.Task
import com.lightningkite.lightningserver.tasks.Tasks
import com.lightningkite.lightningserver.tasks.task
import com.lightningkite.lightningserver.typed.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Clock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.serializer
import kotlin.time.Duration
import java.util.*
import com.lightningkite.UUID
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

class ExternalAsyncTaskIntegration<REQUEST, RESPONSE : HasId<String>, RESULT>(
    path: ServerPath,
    val authOptions: AuthOptions<*> = Authentication.isDeveloper,
    val responseSerializer: KSerializer<RESPONSE>,
    val resultSerializer: KSerializer<RESULT>,
    val database: () -> Database,
    val api: () -> Api<REQUEST, RESPONSE, RESULT>,
    val checkFrequency: Duration = 15.minutes,
    val taskTimeout: Duration = 2.minutes,
    val checkChunking: Int = 15,
    val name: String = path.segments.lastOrNull()?.toString() ?: "Task",
    val idempotentBasedOnOurData: Boolean = false,
) : ServerPathGroup(path) {
    init {
        prepareModelsServerCore()
    }

    // Collection exposed to admins only for tasks
    @Suppress("UNCHECKED_CAST")
    val info = modelInfoWithDefault<HasId<*>, ExternalAsyncTaskRequest, String>(
        authOptions = authOptions as AuthOptions<HasId<*>>,
        serialization = ModelSerializationInfo(
            serializer = ExternalAsyncTaskRequest.serializer(),
            idSerializer = String.serializer()
        ),
        getBaseCollection = {
            database().collection<ExternalAsyncTaskRequest>(name = "$path/ExternalTaskRequest")
        },
        defaultItem = {
            ExternalAsyncTaskRequest(
                _id = "",
                expiresAt = Clock.System.now().plus(7.days),
                ourData = ""
            )
        },
        forUser = { it },
        modelName = "${name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }}Request"
    )

    init {
        path.docName = path.toString()
            .replace(Regex("""[^0-9a-zA-Z]+(?<following>.)?""")) { match ->
                match.groups["following"]?.value?.uppercase() ?: ""
            }
            .replaceFirstChar { it.lowercase() }
    }

    val rest = ModelRestEndpoints(path("rest").apply { docName = this@ExternalAsyncTaskIntegration.path.docName }, info)

    // Kick it off
    suspend fun <OURDATA> start(request: REQUEST, ourData: OURDATA, action: ResultAction<OURDATA, RESULT>): RESPONSE {
        val ourDataString = Serialization.Internal.json.encodeToString(action.ourDataSerialization, ourData)
        if (idempotentBasedOnOurData) {
            info.collection().findOne(condition { it.ourData.eq(ourDataString) })?.response?.let {
                return Serialization.Internal.json.decodeFromString(responseSerializer, it)
            }
        }
        val response = api().begin(request)
        info.collection().insertOne(
            ExternalAsyncTaskRequest(
                _id = response._id,
                response = Serialization.Internal.json.encodeToString(responseSerializer, response),
                ourData = ourDataString,
                expiresAt = Clock.System.now().plus(action.expiration),
                action = action.key
            )
        )
        return response
    }

    // Callback storage and handling
    data class ResultAction<OURDATA, RESULT> internal constructor(
        val key: String,
        val ourDataSerialization: KSerializer<OURDATA>,
        val expiration: Duration = 1.days,
        val expired: suspend (OURDATA) -> Unit = {},
        val action: suspend (OURDATA, RESULT) -> Unit,
    )

    val resultActions = HashMap<String, ResultAction<*, RESULT>>()
    fun <OURDATA> resultAction(
        key: String,
        ourDataSerializer: KSerializer<OURDATA>,
        expiration: Duration = 1.days,
        expired: suspend (OURDATA) -> Unit = {},
        action: suspend (OURDATA, RESULT) -> Unit,
    ): ResultAction<OURDATA, RESULT> {
        val actionObject = ResultAction(key, ourDataSerializer, expiration, expired, action)
        resultActions[key] = actionObject
        return actionObject
    }

    inline fun <reified OURDATA> resultAction(
        key: String,
        expiration: Duration = 1.days,
        noinline expired: suspend (OURDATA) -> Unit = {},
        noinline action: suspend (OURDATA, RESULT) -> Unit,
    ): ResultAction<OURDATA, RESULT> =
        resultAction(key, Serialization.Internal.module.serializer(), expiration, expired, action)

    val runActionResult: Task<ExternalAsyncTaskRequest> =
        task("$path/runActionResult") { sig: ExternalAsyncTaskRequest ->
            @Suppress("UNCHECKED_CAST")
            val task = sig.action?.let {
                (resultActions[it] as? ResultAction<Any?, RESULT>) ?: run {
                    Exception("No such handler '${sig.action}'").report()
                    info.collection()
                        .updateOneById(
                            sig._id,
                            modification {
                                it.processingError assign "No such handler '${sig.action}'"
                                it.lastAttempt assign Clock.System.now()
                            })
                    return@task
                }
            } ?: return@task
            try {
                info.collection().updateOneById(sig._id, modification {
                    it.lastAttempt assign Clock.System.now()
                })
                val result = sig.result?.let { Serialization.Internal.json.decodeFromString(resultSerializer, it) }
                val ourData = Serialization.Internal.json.decodeFromString(task.ourDataSerialization, sig.ourData)
                if (result == null) {
                    //expired
                    task.expired(ourData)
                } else {
                    task.action(
                        ourData,
                        result
                    )
                }
                info.collection().deleteOneById(sig._id)
            } catch (e: Exception) {
                e.report()
                info.collection().updateOneById(sig._id, modification {
                    it.processingError assign e.stackTraceToString()
                })
            }
        }

    // Regular re-check test
    suspend fun check(id: String) {
        api().check(id)?.let { result -> handleResult(id, result) }
    }

    suspend fun check(ids: List<String>) = coroutineScope {
        recheckSet.invokeImmediate(
            this, info.collection().find(
                condition { it._id inside ids }).toList()
        )
    }

    val manualRecheck = path("recheck").post.api(
        summary = "Manually recheck tasks",
        errorCases = listOf(),
        authOptions = authOptions,
        inputType = Unit.serializer(),
        outputType = Unit.serializer(),
        implementation = { _: Unit ->
            recheck.handler.invoke()
        }
    )
    val manualRecheckSingle = path("recheck").arg<String>("id").post.api(
        summary = "Manually recheck a task",
        errorCases = listOf(),
        authOptions = authOptions,
        inputType = Unit.serializer(),
        outputType = Unit.serializer(),
        implementation = { _: Unit ->
            coroutineScope {
                recheckSet.invokeImmediate(this, listOf(info.collection().get(path1) ?: throw NotFoundException()))
            }
        }
    )

    val recheck = schedule("$path/recheck", checkFrequency) {
        info.collection().find(condition {
            (it.result neq null) and (it.expiresAt lt Clock.System.now()) and (it.lastAttempt lt Clock.System.now()
                .minus(taskTimeout))
        }).collect {
            runActionResult(it)
        }
        info.collection().find(condition {
            val notLocked = it.lastAttempt.lte(Clock.System.now().minus(taskTimeout))
            val noError = it.processingError eq null
            val hasResult = it.result neq null
            notLocked and noError and hasResult
        }).collectChunked(checkChunking) {
            recheckSet(it)
        }
    }
    val recheckSet = task(
        "$path/recheckSet",
        ListSerializer(ExternalAsyncTaskRequest.serializer())
    ) { ids: List<ExternalAsyncTaskRequest> ->
        api().check(ids.map { it._id }).forEach { result -> handleResult(result.key, result.value) }
    }

    /**
     * @return Whether the task ID was round.
     */
    suspend fun handleResult(id: String, result: RESULT): Boolean {
        val r = info.collection().updateOneById(id, modification {
            it.result assign Serialization.Internal.json.encodeToString(resultSerializer, result)
        })
        r.new?.let { runActionResult(it) }
        return r.new != null
    }

    /**
     * @return Whether the task ID was round.
     */
    suspend fun handleExpire(id: String): Boolean {
        runActionResult(
            info.collection().get(id) ?: return false
        )
        return true
    }

    init {
        Tasks.onSettingsReady { api().ready(this) }
    }

    interface Api<REQUEST, RESPONSE : HasId<String>, RESULT> {
        suspend fun ready(integration: ExternalAsyncTaskIntegration<REQUEST, RESPONSE, RESULT>) {}
        suspend fun begin(request: REQUEST): RESPONSE
        suspend fun check(ids: List<String>): Map<String, RESULT>
    }
}

suspend fun <REQUEST, RESPONSE : HasId<String>, RESULT> ExternalAsyncTaskIntegration.Api<REQUEST, RESPONSE, RESULT>.check(
    id: String,
): RESULT? {
    return check(listOf(id)).get(id)
}