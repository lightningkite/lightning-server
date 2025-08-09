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

/**
 * [ServerBuilder] provides a fluent, type-safe API for defining your server configuration.
 *
 * [ServerBuilder] is essentially a collection of [Registry]s for your endpoints, tasks, schedules, etc. You build a server by registering
 * resources and their locations. Once the definition is complete the [build] method is used to construct an immutable
 * [ServerDefinition] for runtime use.
 *
 * You don't typically use the registries inside of [ServerBuilder] directly, instead there is a provided builder dsl to do this
 * registration for you and in a safe manner. This dsl is accessed by subclassing [ServerBuilder] into your server definition.
 *
 * Example:
 * ```kotlin
 * object Server : ServerBuilder() {
 *     override val internalSerialization: SerializersModule = EmptySerializersModule()
 *     override val externalSerialization: SerializersModule = EmptySerializersModule()
 *
 *     // GET handler (just returns OK)
 *     val root = path.get bind HttpHandler { HttpResponse(status = HttpStatus.OK) }
 *
 *     // The "bind" infix fun you see above is provided by the dsl. It "binds" the
 *     // path on the left to the handler on the right.
 *
 *     // Basic hello world endpoint, bound to the path "/hello/world" with method POST
 *     val helloWorld = path.path("hello").path("world").post bind HttpHandler {
 *         HttpResponse.plainText("Hello World!")
 *     }
 * }
 * ```
 *
 * Additionally, [ServerBuilder] is designed to be modular. This means that you can use it to define both your root server definition, and
 * also to define endpoints for specific models.
 *
 * ```kotlin
 * object ModelEndpoints : ServerBuilder() {
 *     // ...
 * }
 *
 * object Server : ServerBuilder() {
 *     // ...
 *
 *     val modelEndpoints = path.path("model") bind ModelEndpoints
 * }
 * ```
 * */
public abstract class ServerBuilder {
    public open val internalSerialization: SerializersModule get() = EmptySerializersModule()
    public open val externalSerialization: SerializersModule get() = EmptySerializersModule()

    protected val path: PathSpec0 = PathSpec.root // just for convenience

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

    internal var modulePath: PathSpec0 = PathSpec.root
}