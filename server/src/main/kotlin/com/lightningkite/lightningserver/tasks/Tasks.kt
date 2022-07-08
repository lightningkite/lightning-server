package com.lightningkite.lightningserver.tasks

import com.lightningkite.lightningserver.engine.engine
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
    operator fun invoke(input: INPUT) = engine.launchTask(this as Task<Any?>, input)
}

object Tasks {
    val tasks = HashMap<String, Task<*>>()
    private val startupActions = HashSet<()->Unit>()
    fun startup(action: ()->Unit) {
        if(isStarted) action()
        else startupActions.add(action)
    }
    var isStarted = false
        private set
    fun startup() {
        if(isStarted) return
        isStarted = true
        startupActions.forEach { it() }
        startupActions.clear()
    }
}
