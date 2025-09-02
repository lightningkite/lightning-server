package com.lightningkite.lightningserver.definition

import com.lightningkite.lightningserver.runtime.ServerRuntime
import kotlinx.serialization.KSerializer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

public interface StartupTask {
    /**
     * Higher priority items occur earlier.
     */
    public val dependencies: Collection<StartupTask> get() = emptyList()
    public val timeout: Duration get() = 30.seconds

    context(server: ServerRuntime)
    public suspend fun execute()
}

public fun StartupTask(
    dependencies: Collection<StartupTask> = emptyList(),
    timeout: Duration = 5.minutes,
    handler: suspend context(ServerRuntime) () -> Unit
): StartupTask =
    object : StartupTask {
        override val timeout: Duration = timeout
        override val dependencies: Collection<StartupTask> = dependencies

        context(server: ServerRuntime)
        override suspend fun execute() {
            return handler(server)
        }
    }