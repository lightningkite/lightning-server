package com.lightningkite.lightningserver.definition

import com.lightningkite.lightningserver.Locationed
import com.lightningkite.lightningserver.ScheduledTask
import com.lightningkite.lightningserver.ServerRuntime
import com.lightningkite.lightningserver.ServerSetting
import com.lightningkite.lightningserver.Task
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketTopic
import kotlinx.serialization.KSerializer

@DslMarker
public annotation class LightningServerDsl

@LightningServerDsl
context(builder: ServerBuilder)
public infix fun <PATH : PathSpec> HttpEndpoint<PATH>.bind(handler: HttpHandler<PATH>): Locationed<HttpEndpoint<PATH>, HttpHandler<PATH>> =
    builder.http.register(this, handler)

@LightningServerDsl
context(builder: ServerBuilder)
public infix fun <PATH : PathSpec, STORAGE> PATH.bind(handler: WebSocketHandler<PATH, STORAGE>): Locationed<PATH, WebSocketHandler<PATH, STORAGE>> =
    builder.websockets.register(this, handler)

@LightningServerDsl
context(builder: ServerBuilder)
public infix fun PathSpec0.bind(task: Task<*>): Locationed<PathSpec0, Task<*>> = builder.tasks.register(this, task)

@LightningServerDsl
context(builder: ServerBuilder)
public infix fun PathSpec0.bind(schedule: ScheduledTask): Locationed<PathSpec0, ScheduledTask> = builder.schedules.register(this, schedule)

@LightningServerDsl
context(builder: ServerBuilder)
public fun <PATH : PathSpec, T> PATH.topic(type: KSerializer<T>): WebSocketTopic<PATH, T> =
    WebSocketTopic(this, type).also { builder.websockets.topics.register(this, it) }

@LightningServerDsl
context(builder: ServerBuilder)
public fun <Setting, Result> PathSpec0.setting(setting: ServerSetting<Setting, Result>): Locationed<PathSpec0, ServerSetting<Setting, Result>> =
    builder.settings.register(this, setting)

@LightningServerDsl
context(builder: ServerBuilder)
public fun <Setting, Result> PathSpec0.setting(
    name: String,
    default: Setting,
    serializer: KSerializer<Setting>,
    optional: Boolean = false,
    description: String? = null,
    getter: ServerRuntime.(Setting) -> Result,
): Locationed<PathSpec0, ServerSetting<Setting, Result>> =
    path(name).setting(
        ServerSetting(
            serializer,
            default,
            optional,
            description
        ) { _, value -> getter(this, value) }
    )

@LightningServerDsl
context(builder: ServerBuilder)
public fun <Result> PathSpec0.setting(
    name: String,
    default: Result,
    serializer: KSerializer<Result>,
    optional: Boolean = false,
    description: String? = null,
): Locationed<PathSpec0, ServerSetting<Result, Result>> =
    path(name).setting(
        ServerSetting(
            serializer,
            default,
            optional,
            description
        ) { _, value -> value }
    )

@LightningServerDsl
context(builder: ServerBuilder)
public infix fun <T : ServerBuilder> PathSpec0.bind(module: T): Locationed<PathSpec0, T> =
    builder.modules.register(this, module)

@LightningServerDsl
context(builder: ServerBuilder)
public infix fun <T : ServerDefinition> PathSpec0.bind(import: T): Locationed<PathSpec0, T> =
    builder.imports.register(this, import)


