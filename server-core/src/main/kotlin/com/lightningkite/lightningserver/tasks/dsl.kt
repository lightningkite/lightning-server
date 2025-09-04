@file:UseContextualSerialization(Instant::class)

package com.lightningkite.lightningserver.tasks

import com.lightningkite.prepareModelsServerCore
import com.lightningkite.lightningdb.*
import com.lightningkite.serialization.*
import com.lightningkite.lightningserver.core.LightningServerDsl
import com.lightningkite.lightningserver.exceptions.exceptionSettings
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.now
import com.lightningkite.serialization.contextualSerializerIfHandled
import com.lightningkite.serialization.notNull
import kotlinx.datetime.Instant
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@LightningServerDsl
inline fun <reified INPUT> task(
    name: String,
    noinline implementation: suspend Task.RunningTask<INPUT>.(INPUT) -> Unit,
) =
    task(name, Serialization.module.contextualSerializerIfHandled<INPUT>(), implementation)

@LightningServerDsl
fun <INPUT> task(
    name: String,
    serializer: KSerializer<INPUT>,
    implementation: suspend Task.RunningTask<INPUT>.(INPUT) -> Unit,
) =
    Task(name, serializer, implementation)

@LightningServerDsl
fun startup(priority: Double = 0.0, action: suspend () -> Unit) = Tasks.onEngineReady(priority, action)

@LightningServerDsl
fun defineAfterSettings(priority: Double = 0.0, action: suspend () -> Unit) = Tasks.onSettingsReady(priority, action)


@GenerateDataClassPaths
@Serializable
data class ActionHasOccurred(
    override val _id: String,
    @Contextual val started: Instant? = null,
    @Contextual val completed: Instant? = null,
    val errorMessage: String? = null,
) : HasId<String>

@LightningServerDsl
fun startupOnce(
    name: String,
    database: () -> Database,
    maxDuration: Duration = 60.seconds,
    priority: Double = 0.0,
    action: suspend () -> Unit,
): StartupAction {
    prepareModelsServerCore()
    return startup(priority) {
        doOnce(name, database, maxDuration, priority, action)
    }
}

private class DoOnceException(message: String? = null, cause: Throwable? = null) : Exception(message, cause)

@LightningServerDsl
suspend fun doOnce(
    name: String,
    database: () -> Database,
    maxDuration: Duration = 60.seconds,
    @Suppress("UNUSED_PARAMETER") priority: Double = 0.0,
    action: suspend () -> Unit,
) {
    prepareModelsServerCore()
    val a = database().collection<ActionHasOccurred>()
    val existing = a.get(name)
    if (existing == null) {
        a.insertOne(ActionHasOccurred(_id = name, started = now()))
    } else {
        val lock = a.updateOne(
            condition {
                it._id eq name and (it.completed eq null) and (it.started eq null or (it.started.notNull lt (now() - maxDuration)))
            },
            modification { it.started assign now() }
        )
        if (lock.new == null) return
    }
    try {
        action()
        a.updateOneById(
            name,
            modification {
                it.completed assign now()
                it.errorMessage assign null
            }
        )
    } catch (e: Exception) {
        exceptionSettings().report(DoOnceException(cause = e), "doOnce: $name")
        a.updateOneById(
            name,
            modification {
                (it.errorMessage assign e.message)
                (it.started assign null)
            }
        )
    }
}
