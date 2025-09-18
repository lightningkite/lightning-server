package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.LightningServerDsl
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.StartupTask
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.location
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.typed.started
import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.and
import com.lightningkite.services.database.condition
import com.lightningkite.services.database.eq
import com.lightningkite.services.database.get
import com.lightningkite.services.database.insertOne
import com.lightningkite.services.database.lt
import com.lightningkite.services.database.modification
import com.lightningkite.services.database.notNull
import com.lightningkite.services.database.or
import com.lightningkite.services.database.table
import com.lightningkite.services.database.updateOneById
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@GenerateDataClassPaths
@Serializable
public data class ActionHasOccurred(
    override val _id: String,
    val started: Instant? = null,
    val completed: Instant? = null,
    val errorMessage: String? = null,
) : HasId<String>

context(runtime: ServerRuntime)
public suspend fun doOnce(
    key: String,
    database: Runtime<Database>,
    timeout: Duration = 60.seconds,
    action: suspend context(ServerRuntime) () -> Unit,
) {
    val a = database().table<ActionHasOccurred>()

    val existing = a.get(key)
    if (existing == null) {
        a.insertOne(ActionHasOccurred(_id = key, started = now()))
    } else {
        val lock = a.updateOne(
            condition<ActionHasOccurred> {
                (it._id eq key) and (it.completed eq null) and (it.started eq null or (it.started.notNull lt (now() - timeout)))
            },
            modification { it.started assign now() }
        )
        if (lock.new == null) return
    }

    try {
        action(runtime)
        a.updateOneById(
            key,
            modification {
                it.completed assign now()
                it.errorMessage assign null
            }
        )
    } catch (e: Exception) {
        KotlinLogging.logger("com.lightningkite.lightningserver.typed.doOnce").error(e) { "doOnce($key) failed to complete" }
        a.updateOneById(
            key,
            modification {
                (it.errorMessage assign e.message)
                (it.started assign null)
            }
        )
    }
}

/**
 * Creates a [StartupTask] that calls [doOnce] with its bound path as its key.
 * */
@Suppress("FunctionName")
public fun StartupOnce(
    database: Runtime<Database>,
    dependencies: List<StartupTask> = emptyList(),
    timeout: Duration = 60.seconds,
    action: suspend context(ServerRuntime) () -> Unit
): StartupTask {
    var task: StartupTask? = null
    task = StartupTask(dependencies, timeout) {
        doOnce(
            key = task?.location?.toString() ?: throw IllegalStateException("StartupOnce not bound to path"),
            database = database,
            timeout = timeout,
            action = action
        )
    }
    return task
}

/**
 * Creates a [StartupTask] that calls [doOnce] with the provided key.
 * */
@Suppress("FunctionName")
public fun StartupOnce(
    key: String,
    database: Runtime<Database>,
    dependencies: List<StartupTask> = emptyList(),
    timeout: Duration = 60.seconds,
    action: suspend context(ServerRuntime) () -> Unit
): StartupTask = StartupTask(dependencies, timeout) {
    doOnce(
        key = key,
        database = database,
        timeout = timeout,
        action = action
    )
}