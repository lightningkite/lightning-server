package com.lightningkite.lightningserver.definition

import com.lightningkite.lightningserver.ScheduledTask
import com.lightningkite.lightningserver.ServerPathEndpoints
import com.lightningkite.lightningserver.ServerSetting
import com.lightningkite.lightningserver.Task
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

public abstract class ServerBuilder {
    public open val internalSerialization: SerializersModule get() = EmptySerializersModule()
    public open val externalSerialization: SerializersModule get() = EmptySerializersModule()

    public val path: PathSpec0 = PathSpec.root

    public val settings: Registry<PathSpec0, ServerSetting<*, *>> = Registry()

    public val http: HttpBuilder = HttpBuilder()
    public val websockets: WebSocketsBuilder = WebSocketsBuilder()

    public val schedules: Registry<PathSpec0, ScheduledTask> = Registry()
    public val tasks: Registry<PathSpec0, Task<*>> = Registry()

    public val extensions: MutableExtensions = MutableExtensions()

    public val imports: Registry<PathSpec0, ServerDefinition> = Registry()
    public val modules: Registry<PathSpec0, ServerBuilder> = Registry()


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

        private val source = this@ServerBuilder

        override val settings: Map<PathSpec0, ServerSetting<*, *>> = source.settings
        override val schedules: Map<PathSpec0, ScheduledTask> = source.schedules
        override val tasks: Map<PathSpec0, Task<*>> = source.tasks
        override val webSocketTopics: PathSpecMap<WebSocketTopic<*, *>> = source.websockets.topics.registered
        override val extensions: Extensions = source.extensions

        override val modules: Map<PathSpec0, ServerDefinition> =
            source.imports + source.modules.mapValues { (_, builder) -> builder.build() }
    }
}