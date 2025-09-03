package com.lightningkite.lightningserver.definition.builder

import com.lightningkite.lightningserver.LightningServerDsl
import com.lightningkite.lightningserver.definition.ScheduledTask
import com.lightningkite.lightningserver.definition.Task
import com.lightningkite.lightningserver.definition.Locationed
import com.lightningkite.lightningserver.definition.DynamicLocation
import com.lightningkite.lightningserver.definition.ModularServerDefinition
import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.lightningserver.definition.StartupTask
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.plus
import com.lightningkite.lightningserver.serialization.MediaTypeCoder
import com.lightningkite.lightningserver.serialization.serializerOrContextual
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketTopic
import com.lightningkite.services.Setting
import com.lightningkite.services.SettingContext
import kotlinx.serialization.KSerializer

private fun <Location, Item> locate(item: Item, location: () -> Location): Locationed<Location, Item> = DynamicLocation(location, item)

@LightningServerDsl
context(builder: ServerBuilder)
public infix fun <PATH : PathSpec, HANDLER : HttpHandler<PATH>> HttpEndpoint<PATH>.bind(handler: HANDLER): Locationed<HttpEndpoint<PATH>, HANDLER> {
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
public infix fun <T> PathSpec0.bind(task: Task<T>): Locationed<PathSpec0, Task<T>> {
    builder.tasks.register(this, task)
    return locate(task) { builder.modulePath + this }
}

@LightningServerDsl
context(builder: ServerBuilder)
public infix fun PathSpec0.bind(startupTask: StartupTask): Locationed<PathSpec0, StartupTask> {
    builder.startupTasks.register(this, startupTask)
    return locate(startupTask) { builder.modulePath + this }
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
    getter: SettingContext.(Setting) -> Result,
): ServerSetting<Setting, Result> =
    setting(
        ServerSetting(
            name,
            default,
            serializer,
            optional,
        ) { value -> getter(this, value) }
    )

@LightningServerDsl
context(builder: ServerBuilder)
public fun <SETTING : Setting<RESULT>, RESULT> setting(
    name: String,
    default: SETTING,
    serializer: KSerializer<SETTING>,
    optional: Boolean = false,
): ServerSetting<SETTING, RESULT> =
    setting(
        ServerSetting(
            name,
            default,
            serializer,
            optional,
        )
    )

@LightningServerDsl
context(builder: ServerBuilder)
public inline fun <reified SETTING : Setting<RESULT>, RESULT> setting(
    name: String,
    default: SETTING,
    optional: Boolean = false,
): ServerSetting<SETTING, RESULT> =
    setting(
        ServerSetting(
            name,
            default,
            serializerOrContextual<SETTING>(),
            optional,
        )
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
        default,
        serializer,
        optional,
    )
    builder.settings.register(setting)
    return setting
}

@LightningServerDsl
context(builder: ServerBuilder)
public inline fun <reified Setting, Result> setting(
    name: String,
    default: Setting,
    optional: Boolean = false,
    crossinline getter: SettingContext.(Setting) -> Result,
): ServerSetting<Setting, Result> =
    setting(
        ServerSetting(
            name,
            default,
            serializerOrContextual<Setting>(),
            optional,
        ) { value -> getter(this, value) }
    )

@LightningServerDsl
context(builder: ServerBuilder)
public inline fun <reified Result> setting(
    name: String,
    default: Result,
    optional: Boolean = false,
): ServerSetting.Direct<Result> {
    val setting = ServerSetting(
        name,
        default,
        serializerOrContextual<Result>(),
        optional,
    )
    builder.settings.register(setting)
    return setting
}

@LightningServerDsl
context(builder: ServerBuilder)
public infix fun <T : ServerBuilder> PathSpec0.bind(module: T): T {
    module.modulePath = this
    builder.modules.register(this, module)
    return module
}


@LightningServerDsl
context(builder: ServerBuilder)
public infix fun PathSpec0.bind(import: ModularServerDefinition): Locationed<PathSpec0, ModularServerDefinition> {
    builder.imports.register(this, import)
    return Locationed(this, import)
}

@LightningServerDsl
context(builder: ServerBuilder)
public infix fun PathSpec0.bind(import: ServerDefinition): Locationed<PathSpec0, ServerDefinition> {
    builder.imports.register(this, ModularServerDefinition(import))
    return Locationed(this, import)
}

//@LightningServerDsl
//context(builder: ServerBuilder)
//public infix fun <T : ServerBuilder> PathSpec0.include(module: T): T {
//    builder.internalSerialization.include(module.internalSerialization)
//    builder.externalSerialization.include(module.externalSerialization)
//    builder.settings.include(module.settings)
//    builder.http.include(module.http)
//    builder.websockets.include(module.websockets)
//    builder.exceptionHandler.include(module.exceptionHandler)
//    builder.startupTasks.include(module.startupTasks)
//    builder.schedules.include(module.schedules)
//    builder.tasks.include(module.tasks)
//    builder.mediaTypeDecoders.include(module.mediaTypeDecoders)
//    builder.mediaTypeEncoders.include(module.mediaTypeEncoders)
//    builder.extensions.include(module.extensions)
//    builder.imports.include(module.imports)
//    builder.modules.include(module.modules)
//}

public fun ServerBuilder.register(coder: MediaTypeCoder) {
    mediaTypeDecoders.register(coder)
    mediaTypeEncoders.register(coder)
}
