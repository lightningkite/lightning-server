package com.lightningkite.lightningserver.tasks

import com.lightningkite.lightningserver.engine.engine
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.KSerializer

data class Task<INPUT>(
    val name: String,
    val serializer: KSerializer<INPUT>,
    val implementation: suspend CoroutineScope.(INPUT) -> Unit
) {
    init {
        Tasks.tasks[name] = this
    }
    @Suppress("UNCHECKED_CAST")
    suspend operator fun invoke(input: INPUT) = engine.launchTask(this as Task<Any?>, input)
}