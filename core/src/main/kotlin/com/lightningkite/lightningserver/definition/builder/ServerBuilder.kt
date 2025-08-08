package com.lightningkite.lightningserver.definition.builder

import com.lightningkite.lightningserver.ScheduledTask
import com.lightningkite.lightningserver.Task
import com.lightningkite.lightningserver.definition.Extensions
import com.lightningkite.lightningserver.definition.MutableExtensions
import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.definition.ServerPathEndpoints
import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.lightningserver.http.HttpBuilder
import com.lightningkite.lightningserver.http.intercept
import com.lightningkite.lightningserver.pathing.MutablePathSpecMap
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.PathSpecMap
import com.lightningkite.lightningserver.websockets.WebSocketTopic
import com.lightningkite.lightningserver.websockets.WebSocketsBuilder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.plus

public abstract class ServerBuilder {
    public open val internalSerialization: SerializersModule get() = EmptySerializersModule()
    public open val externalSerialization: SerializersModule get() = EmptySerializersModule()

    public val path: PathSpec0 = PathSpec.Companion.root

    public val settings: ListRegistry<ServerSetting<*, *>> = ListRegistry()

    public val http: HttpBuilder = HttpBuilder()
    public val websockets: WebSocketsBuilder = WebSocketsBuilder()

    public val schedules: Registry<PathSpec0, ScheduledTask> = Registry()
    public val tasks: Registry<PathSpec0, Task<*>> = Registry()

    public val extensions: MutableExtensions = MutableExtensions()

    public val modules: Registry<PathSpec0, ServerDefinition> = Registry()


    public fun build(): ServerDefinition = object : ServerDefinition {
        override val endpoints: PathSpecMap<ServerPathEndpoints> =
            MutablePathSpecMap<ServerPathEndpoints>().apply {
                val httpInterceptor = http.interceptors.build()
                val websocketInterceptor = websockets.interceptors.build()

                val paths = http.handlers.keys + websockets.handlers.keys
                for (path in paths) {
                    put(
                        path,
                        ServerPathEndpoints(
                            http = http
                                .handlers[path]
                                ?.mapValues { (_, handler) ->
                                    httpInterceptor.intercept(handler)
                                }
                                ?: emptyMap(),

                            websocket = websockets
                                .handlers[path]
                                ?.let(websocketInterceptor::invoke)
                        )
                    )
                }
            }

        private val source get() = this@ServerBuilder

        override val settings: List<ServerSetting<*, *>> = source.settings
        override val schedules: Map<PathSpec0, ScheduledTask> = source.schedules
        override val tasks: Map<PathSpec0, Task<*>> = source.tasks
        override val webSocketTopics: PathSpecMap<WebSocketTopic<*, *>> = source.websockets.topics.registered
        override val extensions: Extensions = source.extensions
        override val modules: Map<PathSpec0, ServerDefinition> = source.modules

        override val internalSerializersModule: SerializersModule =
            modules.values.fold(source.internalSerialization) { acc, module -> acc + module.internalSerializersModule }

        override val externalSerializersModule: SerializersModule =
            modules.values.fold(source.externalSerialization) { acc, module -> acc + module.externalSerializersModule }
    }
}