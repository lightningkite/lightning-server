@file:UseContextualSerialization(Instant::class)
package com.lightningkite.lightningserver.tasks

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.core.LightningServerDsl
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlinx.serialization.serializer
import java.time.Instant
import kotlin.coroutines.CoroutineContext

@LightningServerDsl
inline fun <reified INPUT> task(name: String, noinline implementation: suspend CoroutineScope.(INPUT)->Unit) = task(name, serializer<INPUT>(), implementation)

@LightningServerDsl
fun <INPUT> task(name: String, serializer: KSerializer<INPUT>, implementation: suspend CoroutineScope.(INPUT)->Unit) = Task(name, serializer, implementation)

@LightningServerDsl
fun startup(priority: Double = 0.0, action: suspend ()->Unit) = Tasks.startup(priority, action)



@DatabaseModel
@Serializable
data class ActionHasOccurred(override val _id: String, val started: Instant? = null, val completed: Instant? = null, val errorMessage: String? = null): HasId<String>

@LightningServerDsl
fun startupOnce(name: String, database: ()-> Database, maxDuration: Long = 60_000, priority: Double = 0.0, action: suspend ()->Unit): StartupAction {
    return startup(priority) {
        val a = database().collection<ActionHasOccurred>()
        val existing = a.get(name)
        val lock = a.updateOne(
            condition { it._id eq name and (it.started eq null or (it.started.notNull lt Instant.now().minusSeconds(maxDuration))) },
            modification { it.started assign Instant.now() }
        )
        if (lock.new == null && existing != null) return@startup
        if(lock.new == null) {
            a.insertOne(ActionHasOccurred(_id = name, started = Instant.now()))
        }
        try {
            action()
            a.updateOneById(
                name,
                modification { it.completed assign Instant.now() }
            )
        } catch(e: Exception) {
            a.updateOneById(
                name,
                modification {
                    (it.errorMessage assign e.message) then
                        (it.started assign null)
                }
            )
        }
    }
}

private fun example(database: ()->Database) {
    startup { println("Happens on every function invocation.  Things that must be configured for operation belong here.") }
    startupOnce("one time action", database) { println("An action that only ever occurs one time.") }
}