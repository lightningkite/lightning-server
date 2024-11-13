package com.lightningkite.lightningserver.tasks

object Tasks {
    val tasks = HashMap<String, Task<*>>()

    private val onSettingsReadyActions = HashSet<StartupAction>()
    private var onSettingsReadyStackTrace: Exception? = null
    fun onSettingsReady(priority: Double = 0.0, action: suspend () -> Unit): StartupAction {
        onSettingsReadyStackTrace?.let {
            throw IllegalStateException(
                "onSettingsReady called too late; already ready at:",
                it
            )
        }
        val result = StartupAction(priority, action)
        onSettingsReadyActions.add(result)
        return result
    }

    suspend fun onSettingsReady() {
        if (onSettingsReadyStackTrace != null) return
        onSettingsReadyStackTrace = Exception("onSettingsReady run here")
        onSettingsReadyActions.sortedByDescending { it.priority }.forEach { it.action() }
        onSettingsReadyActions.clear()
    }

    private val onEngineReadyActions = HashSet<StartupAction>()
    private var onEngineReadyStackTrace: Exception? = null
    fun onEngineReady(priority: Double = 0.0, action: suspend () -> Unit): StartupAction {
        onEngineReadyStackTrace?.let {
            throw IllegalStateException(
                "onEngineReady called too late; already ready at:",
                it
            )
        }
        val result = StartupAction(priority, action)
        onEngineReadyActions.add(result)
        return result
    }

    suspend fun onEngineReady() {
        if (onEngineReadyStackTrace != null) return
        onEngineReadyStackTrace = Exception("onEngineReady run here")
        onEngineReadyActions.sortedByDescending { it.priority }.forEach { it.action() }
        onEngineReadyActions.clear()
    }
}
