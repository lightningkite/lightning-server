package com.lightningkite.lightningserver

import kotlinx.serialization.KSerializer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

public interface TaskHandler<Input> {
    public val serializer: KSerializer<Input>
    public val timeout: Duration get() = 30.seconds
    public suspend fun execute(serverRunning: ServerRunning, input: Input)
}

public fun <INPUT> ServerDefinitionBuilder<*>.taskHandler(
    input: KSerializer<INPUT>,
    timeout: Duration = 5.minutes,
    handler: suspend ServerRunning.(INPUT) -> Unit
): TaskHandler<INPUT> =
    object : TaskHandler<INPUT> {
        override val timeout: Duration = timeout
        override val serializer: KSerializer<INPUT> = input
        override suspend fun execute(serverRunning: ServerRunning, input: INPUT) {
            return handler(serverRunning, input)
        }
    }