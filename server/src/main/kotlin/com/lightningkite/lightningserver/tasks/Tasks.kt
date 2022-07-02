package com.lightningkite.lightningserver.tasks

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KFunction

data class Task<INPUT>(
    val name: String,
    val serializer: KSerializer<INPUT>,
    val implementation: suspend CoroutineScope.(INPUT) -> Unit
) {
    init {
        Tasks.tasks[name] = this
    }
    @Suppress("UNCHECKED_CAST")
    operator fun invoke(input: INPUT) = Tasks.engineStartImplementation(this as Task<Any?>, input)
}

object Tasks {
    val tasks = HashMap<String, Task<*>>()
    @Suppress("OPT_IN_USAGE")
    var engineStartImplementation: (Task<Any?>, Any?) -> Unit = { a, b ->
        GlobalScope.launch { a.implementation(this, b) }
    }
}
