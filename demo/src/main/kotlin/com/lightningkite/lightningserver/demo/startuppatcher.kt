package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.exceptionSettings
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.collection
import com.lightningkite.services.database.condition
import com.lightningkite.services.database.get
import com.lightningkite.services.database.insertOne
import com.lightningkite.services.database.modification
import com.lightningkite.services.database.updateOneById
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant


@GenerateDataClassPaths
@Serializable
data class ActionHasOccurred(
    override val _id: String,
    @Contextual val started: Instant? = null,
    @Contextual val completed: Instant? = null,
    val errorMessage: String? = null,
) : HasId<String>



private class DoOnceException(message: String? = null, cause: Throwable? = null) : Exception(message, cause)

context(runtime: ServerRuntime)
suspend fun doOnce(
    name: String,
    database: Runtime<Database>,
    maxDuration: Duration = 60.seconds,
    @Suppress("UNUSED_PARAMETER") priority: Double = 0.0,
    action: suspend ServerRuntime.() -> Unit,
) {
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
