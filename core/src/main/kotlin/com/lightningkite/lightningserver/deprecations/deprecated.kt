package com.lightningkite.lightningserver.deprecations

import com.lightningkite.lightningserver.data.Schedule
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.serialization.KSerializer
import kotlin.time.Duration


@Deprecated("Use standard system", ReplaceWith("path(string).get"))
public fun PathSpec0.get(string: String): HttpEndpoint<PathSpec0> = path(string).get

@Deprecated("Use standard system", ReplaceWith("path(string).post"))
public fun PathSpec0.post(string: String): HttpEndpoint<PathSpec0> = path(string).post

@Deprecated("Use standard system", ReplaceWith("path(string).put"))
public fun PathSpec0.put(string: String): HttpEndpoint<PathSpec0> = path(string).put

@Deprecated("Use standard system", ReplaceWith("path(string).patch"))
public fun PathSpec0.patch(string: String): HttpEndpoint<PathSpec0> = path(string).patch

@Deprecated("Use standard system", ReplaceWith("path(string).delete"))
public fun PathSpec0.delete(string: String): HttpEndpoint<PathSpec0> = path(string).delete

@Deprecated("Use standard system", ReplaceWith("path(string).options"))
public fun PathSpec0.options(string: String): HttpEndpoint<PathSpec0> = path(string).options

@Deprecated("Use standard system", ReplaceWith("path(string).head"))
public fun PathSpec0.head(string: String): HttpEndpoint<PathSpec0> = path(string).head


@Deprecated("Use standard syntax", ReplaceWith("path.path(string)"), DeprecationLevel.ERROR)
context(builder: ServerBuilder)
public fun PathSpec0.websocket(handler: WebSocketHandler<PathSpec0, *>): WebSocketHandler<PathSpec0, *> = TODO()

@Deprecated("Use standard syntax", ReplaceWith("path.path(string)"), DeprecationLevel.ERROR)
public fun ServerBuilder.path(string: String): PathSpec0 = TODO()

@Deprecated("Use standard syntax", ReplaceWith("path.path(string).get"))
public fun ServerBuilder.get(string: String): HttpEndpoint<PathSpec0> = PathSpec.root.path(string).get

@Deprecated("Use standard syntax", ReplaceWith("path.path(string).post"))
public fun ServerBuilder.post(string: String): HttpEndpoint<PathSpec0> = PathSpec.root.path(string).post

@Deprecated("Use standard syntax", ReplaceWith("path.path(string).put"))
public fun ServerBuilder.put(string: String): HttpEndpoint<PathSpec0> = PathSpec.root.path(string).put

@Deprecated("Use standard syntax", ReplaceWith("path.path(string).patch"))
public fun ServerBuilder.patch(string: String): HttpEndpoint<PathSpec0> = PathSpec.root.path(string).patch

@Deprecated("Use standard syntax", ReplaceWith("path.path(string).delete"))
public fun ServerBuilder.delete(string: String): HttpEndpoint<PathSpec0> = PathSpec.root.path(string).delete

@Deprecated("Use standard syntax", ReplaceWith("path.path(string).options"))
public fun ServerBuilder.options(string: String): HttpEndpoint<PathSpec0> = PathSpec.root.path(string).options

@Deprecated("Use standard syntax", ReplaceWith("path.path(string).head"))
public fun ServerBuilder.head(string: String): HttpEndpoint<PathSpec0> = PathSpec.root.path(string).head

@Deprecated("Use standard syntax", ReplaceWith("path.get"))
public val ServerBuilder.get: HttpEndpoint<PathSpec0> get() = PathSpec.root.get

@Deprecated("Use standard syntax", ReplaceWith("path.post"))
public val ServerBuilder.post: HttpEndpoint<PathSpec0> get() = PathSpec.root.post

@Deprecated("Use standard syntax", ReplaceWith("path.put"))
public val ServerBuilder.put: HttpEndpoint<PathSpec0> get() = PathSpec.root.put

@Deprecated("Use standard syntax", ReplaceWith("path.patch"))
public val ServerBuilder.patch: HttpEndpoint<PathSpec0> get() = PathSpec.root.patch

@Deprecated("Use standard syntax", ReplaceWith("path.delete"))
public val ServerBuilder.delete: HttpEndpoint<PathSpec0> get() = PathSpec.root.delete

@Deprecated("Use standard syntax", ReplaceWith("path.options"))
public val ServerBuilder.options: HttpEndpoint<PathSpec0> get() = PathSpec.root.options

@Deprecated("Use standard syntax", ReplaceWith("path.head"))
public val ServerBuilder.head: HttpEndpoint<PathSpec0> get() = PathSpec.root.head

@Deprecated(
    "Use the standard syntax",
    ReplaceWith("path.path(name) bind ScheduledTask(schedule, handler = action)"),
    DeprecationLevel.ERROR
)
public fun ServerBuilder.schedule(
    name: String,
    schedule: Schedule,
    action: suspend ServerRuntime.() -> Unit,
): ScheduledTask = TODO()

@Deprecated(
    "Use the standard syntax",
    ReplaceWith("path.path(name) bind ScheduledTask(frequency = frequency, handler = action)"),
)
public fun ServerBuilder.schedule(
    name: String,
    frequency: Duration,
    action: suspend context(ServerRuntime) ScheduledTask.() -> Unit,
): ScheduledTask =
    path.path(name) bind ScheduledTask(frequency = frequency, handler = action)

@Deprecated(
    "Use the standard syntax",
    ReplaceWith("path.path(name) bind ScheduledTask(timeOfDay, timezone, handler = action)")
)
public fun ServerBuilder.schedule(
    name: String,
    timeOfDay: LocalTime,
    timezone: TimeZone,
    action: suspend context(ServerRuntime) ScheduledTask.() -> Unit,
): ScheduledTask =
    path.path(name) bind ScheduledTask(timeOfDay, timezone, handler = action)

@Deprecated("Use PathSpec instead", ReplaceWith("PathSpec"))
public typealias ServerPath = PathSpec

@Deprecated(
    "Use ServerBuilder instead. Also, make your endpoints an object if possible.",
    ReplaceWith("ServerBuilder()", "com.lightningkite.lightningserver.definition.builder")
)
public abstract class ServerPathGroup(path: PathSpec) : ServerBuilder()

@Deprecated("Use \"launch\" directly", ReplaceWith("launch"))
context(_: ServerRuntime)
public suspend fun <Input> Task<Input>.restart(input: Input): Unit = launch(input)

@Deprecated(
    "Use the standard syntax.",
    ReplaceWith("path.path(name) bind Task(serializer, handler = action)")
)
context(builder: ServerBuilder)
public fun <Input> task(
    name: String,
    serializer: KSerializer<Input>,
    action: suspend context(ServerRuntime) Task<Input>.(Input) -> Unit,
): Task<Input> =
    with(builder) {
        path.path(name) bind Task(serializer, handler = action)
    }

@Deprecated(
    "Use the standard syntax.",
    ReplaceWith("path.path(name) bind Task(handler = action)")
)
context(builder: ServerBuilder)
public inline fun <reified Input> task(
    name: String,
    noinline action: suspend context(ServerRuntime) Task<Input>.(Input) -> Unit,
): Task<Input> =
    with(builder) {
        path.path(name) bind Task(handler = action)
    }
