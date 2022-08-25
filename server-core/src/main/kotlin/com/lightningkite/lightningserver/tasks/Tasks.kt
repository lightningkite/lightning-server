package com.lightningkite.lightningserver.tasks

import com.lightningkite.lightningdb.*
import kotlinx.serialization.Serializable

object Tasks {
    val tasks = HashMap<String, Task<*>>()
    private val startupActions = HashSet<StartupAction>()
    fun startup(priority: Double = 0.0, action: suspend ()->Unit): StartupAction {
        if(isStarted) throw IllegalStateException()
        val result = StartupAction(priority, action)
        startupActions.add(result)
        return result
    }
    var isStarted = false
        private set
    suspend fun startup() {
        if(isStarted) return
        isStarted = true
        startupActions.sortedByDescending { it.priority }.forEach { it.action() }
        startupActions.clear()
    }
}
