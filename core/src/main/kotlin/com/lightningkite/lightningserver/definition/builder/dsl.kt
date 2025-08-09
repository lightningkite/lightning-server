package com.lightningkite.lightningserver.definition.builder

import com.lightningkite.lightningserver.ScheduledTask
import com.lightningkite.lightningserver.Task
import com.lightningkite.lightningserver.definition.Locationed
import com.lightningkite.lightningserver.definition.MutableLocation
import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.plus
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketTopic
import kotlinx.serialization.KSerializer

@DslMarker
public annotation class LightningServerDsl

private fun <Location, Item> locate(item: Item, location: () -> Location): Locationed<Location, Item> = MutableLocation(location, item)

@LightningServerDsl
context(builder: ServerBuilder)
public infix fun <PATH : PathSpec> HttpEndpoint<PATH>.bind(handler: HttpHandler<PATH>): Locationed<HttpEndpoint<PATH>, HttpHandler<PATH>> {
    builder.http.register(this, handler)
    return locate(handler) {
        HttpEndpoint(builder.modulePath + this.path, this.method)
    }
}

@LightningServerDsl
context(builder: ServerBuilder)
public infix fun <PATH : PathSpec, STORAGE> PATH.bind(handler: WebSocketHandler<PATH, STORAGE>): Locationed<PATH, WebSocketHandler<PATH, STORAGE>> {
    builder.websockets.register(this, handler)
    return locate(handler) { builder.modulePath + this }
}

@LightningServerDsl
context(builder: ServerBuilder)
public infix fun PathSpec0.bind(task: Task<*>): Locationed<PathSpec0, Task<*>> {
    builder.tasks.register(this, task)
    return locate(task) { builder.modulePath + this }
}

@LightningServerDsl
context(builder: ServerBuilder)
public infix fun PathSpec0.bind(schedule: ScheduledTask): Locationed<PathSpec0, ScheduledTask> {
    builder.schedules.register(this, schedule)
    return locate(schedule) { builder.modulePath + this }
}

@LightningServerDsl
context(builder: ServerBuilder)
public fun <PATH : PathSpec, T> PATH.topic(type: KSerializer<T>): WebSocketTopic<PATH, T> =
    WebSocketTopic(
        path = { builder.modulePath + this },
        type
    ).also { builder.websockets.topics.register(this, it) }

@LightningServerDsl
context(builder: ServerBuilder)
public fun <Setting, Result> setting(setting: ServerSetting<Setting, Result>): ServerSetting<Setting, Result> {
    builder.settings.register(setting)
    return setting
}

@LightningServerDsl
context(builder: ServerBuilder)
public fun <Setting, Result> setting(
    name: String,
    default: Setting,
    serializer: KSerializer<Setting>,
    optional: Boolean = false,
    getter: ServerRuntime.(Setting) -> Result,
): ServerSetting<Setting, Result> =
    setting(
        ServerSetting(
            name,
            serializer,
            default,
            optional,
        ) { value -> getter(this, value) }
    )

@LightningServerDsl
context(builder: ServerBuilder)
public fun <Result> setting(
    name: String,
    default: Result,
    serializer: KSerializer<Result>,
    optional: Boolean = false,
): ServerSetting.Direct<Result> {
    val setting = ServerSetting(
        name,
        serializer,
        default,
        optional,
    )
    builder.settings.register(setting)
    return setting
}

@LightningServerDsl
context(builder: ServerBuilder)
public infix fun <T : ServerBuilder> PathSpec0.bind(module: T): T {
    module.modulePath = this
    builder.modules.register(this, module.build())
    return module
}

@LightningServerDsl
context(builder: ServerBuilder)
public infix fun <T : ServerDefinition> PathSpec0.bind(import: T): Locationed<PathSpec0, T> {
    builder.modules.register(this, import)
    return Locationed(this, import)
}


