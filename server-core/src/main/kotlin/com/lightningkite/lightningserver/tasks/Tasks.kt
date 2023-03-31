package com.lightningkite.lightningserver.tasks

object Tasks {
    val tasks = HashMap<String, Task<*>>()

    private val onSettingsReadyActions = HashSet<StartupAction>()
    fun onSettingsReady(priority: Double = 0.0, action: suspend () -> Unit): StartupAction {
        if (isSettingsReady) throw IllegalStateException()
        val result = StartupAction(priority, action)
        onSettingsReadyActions.add(result)
        return result
    }

    var isSettingsReady = false
        private set

    suspend fun onSettingsReady() {
        if (isSettingsReady) return
        isSettingsReady = true
        onSettingsReadyActions.sortedByDescending { it.priority }.forEach { it.action() }
        onSettingsReadyActions.clear()
    }

    private val onEngineReadyActions = HashSet<StartupAction>()
    fun onEngineReady(priority: Double = 0.0, action: suspend () -> Unit): StartupAction {
        if (isEngineReady) throw IllegalStateException()
        val result = StartupAction(priority, action)
        onEngineReadyActions.add(result)
        return result
    }

    var isEngineReady = false
        private set

    suspend fun onEngineReady() {
        if (isEngineReady) return
        isEngineReady = true
        onEngineReadyActions.sortedByDescending { it.priority }.forEach { it.action() }
        onEngineReadyActions.clear()
    }
}
