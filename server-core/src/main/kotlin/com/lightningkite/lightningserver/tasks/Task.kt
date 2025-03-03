package com.lightningkite.lightningserver.tasks

import com.lightningkite.lightningserver.core.ServerContext
import com.lightningkite.lightningserver.core.ServerEntryPoint
import com.lightningkite.lightningserver.engine.engine
import com.lightningkite.lightningserver.http.Request
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.KSerializer

data class Task<INPUT>(
    val name: String,
    val serializer: KSerializer<INPUT>,
    val implementation: suspend RunningTask<INPUT>.(INPUT) -> Unit
): ServerEntryPoint, ServerContext {
    override suspend fun logString(): String = toString()
    override val entryPoint: ServerEntryPoint
        get() = this
    override val request: Request?
        get() = null

    interface RunningTask<T> : CoroutineScope {
        suspend fun restart(input: T)
    }

    init {
        Tasks.tasks[name] = this
    }

    @Suppress("UNCHECKED_CAST")
    suspend operator fun invoke(input: INPUT) = engine.launchTask(this as Task<Any?>, input)

    suspend fun invokeImmediate(coroutineScope: CoroutineScope, input: INPUT) =
        implementation(object : Task.RunningTask<INPUT>, CoroutineScope by coroutineScope {
            override suspend fun restart(input: INPUT) {
                this@Task.invoke(input)
            }
        }, input)

    override fun toString(): String {
        return "TASK $name"
    }
}