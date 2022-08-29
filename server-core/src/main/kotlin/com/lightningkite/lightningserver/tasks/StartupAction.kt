package com.lightningkite.lightningserver.tasks

data class StartupAction(
    /**
     * Higher priorities occur first
     */
    val priority: Double,
    val action: suspend ()->Unit
)