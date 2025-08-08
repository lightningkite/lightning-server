package com.lightningkite.lightningserver

import com.lightningkite.lightningserver.runtime.ServerRuntime
import kotlinx.serialization.KSerializer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

public interface Task<Input> {
    public val serializer: KSerializer<Input>
    public val timeout: Duration get() = 30.seconds

    context(server: ServerRuntime)
    public suspend fun execute(input: Input)
}

public fun <INPUT> Task(
    input: KSerializer<INPUT>,
    timeout: Duration = 5.minutes,
    handler: suspend ServerRuntime.(INPUT) -> Unit
): Task<INPUT> =
    object : Task<INPUT> {
        override val timeout: Duration = timeout
        override val serializer: KSerializer<INPUT> = input

        context(server: ServerRuntime)
        override suspend fun execute(input: INPUT) {
            return handler(server, input)
        }
    }