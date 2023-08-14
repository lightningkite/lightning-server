package com.lightningkite.lightningserver.externalintegration

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.auth.AuthInfo
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.db.ModelInfoWithDefault
import com.lightningkite.lightningserver.db.ModelRestEndpoints
import com.lightningkite.lightningserver.db.ModelSerializationInfo
import com.lightningkite.lightningserver.routes.docName
import com.lightningkite.lightningserver.schedule.schedule
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.tasks.Task
import com.lightningkite.lightningserver.tasks.Tasks
import com.lightningkite.lightningserver.tasks.task
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.serializer
import java.time.Duration
import java.time.Instant
import java.util.*

class ExternalAsyncTaskIntegration<USER, REQUEST, RESPONSE : HasId<String>, RESULT>(
    path: ServerPath,
    val authInfo: AuthInfo<USER>,
    val responseSerializer: KSerializer<RESPONSE>,
    val resultSerializer: KSerializer<RESULT>,
    val isAdmin: (user: USER) -> Boolean,
    val database: () -> Database,
    val api: () -> Api<REQUEST, RESPONSE, RESULT>,
    val checkFrequency: Duration = Duration.ofMinutes(15),
    val taskTimeout: Duration = Duration.ofMinutes(2),
    val checkChunking: Int = 15,
    val name: String = path.segments.lastOrNull()?.toString() ?: "Task",
    val idempotentBasedOnOurData: Boolean = false,
) : ServerPathGroup(path) {
    init {
        prepareModels()
    }

    // Collection exposed to admins only for tasks
    val info = ModelInfoWithDefault<USER, ExternalAsyncTaskRequest, String>(
        serialization = ModelSerializationInfo(
            authInfo = authInfo,
            serializer = ExternalAsyncTaskRequest.serializer(),
            idSerializer = String.serializer()
        ),
        getCollection = {
            database().collection<ExternalAsyncTaskRequest>(name = "$path/ExternalTaskRequest")
        },
        defaultItem = {
            ExternalAsyncTaskRequest(
                _id = "",
                expiresAt = Instant.now().plus(Duration.ofDays(7)),
                ourData = ""
            )
        },
        forUser = { user ->
            val admin: Condition<ExternalAsyncTaskRequest> =
                if (isAdmin(user)) Condition.Always() else Condition.Never()
            this
                .withPermissions(
                    ModelPermissions(
                        create = admin,
                        read = admin,
                        update = admin,
                        delete = admin,
                    )
                )
        },
        modelName = "${name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }}Request"
    )

    init {
        path.docName = path.toString().replace("/", "_")
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
                expiresAt = Instant.now().plus(action.expiration),
                action = action.key
            )
        )
        return response
    }

    // Callback storage and handling
    data class ResultAction<OURDATA, RESULT> internal constructor(
        val key: String,
        val ourDataSerialization: KSerializer<OURDATA>,
        val expiration: Duration = Duration.ofDays(1),
        val expired: suspend (OURDATA) -> Unit = {},
        val action: suspend (OURDATA, RESULT) -> Unit,
    )

    val resultActions = HashMap<String, ResultAction<*, RESULT>>()
    fun <OURDATA> resultAction(
        key: String,
        ourDataSerializer: KSerializer<OURDATA>,
        expiration: Duration = Duration.ofDays(1),
        expired: suspend (OURDATA) -> Unit = {},
        action: suspend (OURDATA, RESULT) -> Unit,
    ): ResultAction<OURDATA, RESULT> {
        val actionObject = ResultAction(key, ourDataSerializer, expiration, expired, action)
        resultActions[key] = actionObject
        return actionObject
    }

    inline fun <reified OURDATA> resultAction(
        key: String,
        expiration: Duration = Duration.ofDays(1),
        noinline expired: suspend (OURDATA) -> Unit = {},
        noinline action: suspend (OURDATA, RESULT) -> Unit,
    ): ResultAction<OURDATA, RESULT> =
        resultAction(key, Serialization.Internal.module.serializer(), expiration, expired, action)

    val runActionResult: Task<ExternalAsyncTaskRequest> =
        task("$path/runActionResult") { sig: ExternalAsyncTaskRequest ->
            @Suppress("UNCHECKED_CAST")
            val task = sig.action?.let {
                (resultActions[it] as? ResultAction<Any?, RESULT>) ?: run {
                    info.collection()
                        .updateOneById(
                            sig._id,
                            modification {
                                it.processingError assign "No such handler '${sig.action}'"
                                it.lastAttempt assign Instant.now()
                            })
                    return@task
                }
            } ?: return@task
            try {
                info.collection().updateOneById(sig._id, modification {
                    it.lastAttempt assign Instant.now()
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
        recheckSet.implementation(
            this, info.collection().find(
                condition { it._id inside ids }).toList()
        )
    }

    val recheck = schedule("$path/recheck", checkFrequency) {
        info.collection().find(condition {
            (it.result neq null) and (it.expiresAt lt Instant.now()) and (it.lastAttempt lt Instant.now()
                .plus(taskTimeout))
        }).collect {
            runActionResult(it)
        }
        info.collection().find(condition {
            val notLocked = it.lastAttempt.lte(Instant.now().minus(taskTimeout))
            val noError = it.processingError eq null
            val hasResult = it.result neq null
            notLocked and noError and hasResult
        }).collectChunked(checkChunking) {
            recheckSet(it)
        }
    }
    val recheckSet = task("$path/recheckSet", ListSerializer(ExternalAsyncTaskRequest.serializer())) { ids: List<ExternalAsyncTaskRequest> ->
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
        suspend fun <USER> ready(integration: ExternalAsyncTaskIntegration<USER, REQUEST, RESPONSE, RESULT>) {}
        suspend fun begin(request: REQUEST): RESPONSE
        suspend fun check(ids: List<String>): Map<String, RESULT>
    }
}

suspend fun <REQUEST, RESPONSE : HasId<String>, RESULT> ExternalAsyncTaskIntegration.Api<REQUEST, RESPONSE, RESULT>.check(
    id: String,
): RESULT? {
    return check(listOf(id)).get(id)
}