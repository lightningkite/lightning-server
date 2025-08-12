package com.lightningkite.lightningserver.definition.builder

import com.lightningkite.lightningserver.ScheduledTask
import com.lightningkite.lightningserver.Task
import com.lightningkite.lightningserver.definition.Extendable
import com.lightningkite.lightningserver.definition.MutableExtensions
import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.definition.ServerPathEndpoints
import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.lightningserver.definition.toMutableExtensions
import com.lightningkite.lightningserver.http.HttpBuilder
import com.lightningkite.lightningserver.http.intercept
import com.lightningkite.lightningserver.pathing.MutablePathSpecMap
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.PathSpecMap
import com.lightningkite.lightningserver.pathing.plus
import com.lightningkite.lightningserver.websockets.WebSocketsBuilder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.plus

/**
 * [ServerBuilder] provides a fluent, type-safe API for defining your server configuration.
 *
 * [ServerBuilder] is essentially a collection of [MapRegistry]s for your endpoints, tasks, schedules, etc. You build a server by registering
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
public abstract class ServerBuilder : Extendable {
    public open val internalSerialization: SerializersModule get() = EmptySerializersModule()
    public open val externalSerialization: SerializersModule get() = EmptySerializersModule()

    protected val path: PathSpec0 get() = PathSpec.root // just for convenience

    public val settings: ListRegistry<ServerSetting<*, *>> = ListRegistry()

    public val http: HttpBuilder = HttpBuilder()
    public val websockets: WebSocketsBuilder = WebSocketsBuilder()

    public val schedules: MapRegistry<PathSpec0, ScheduledTask> = MapRegistry()
    public val tasks: MapRegistry<PathSpec0, Task<*>> = MapRegistry()

    public override val extensions: MutableExtensions = MutableExtensions()

    public val imports: MapRegistry<PathSpec0, ServerDefinition> = MapRegistry()
    public val modules: MapRegistry<PathSpec0, ServerBuilder> = MapRegistry()

    public fun build(): ServerDefinition {
        val shallow = shallowBuild()
        val modules = imports + modules.mapValues { (_, module) -> module.build() }

        if (modules.isEmpty()) return shallow

        fun <T> flatten(registry: (ServerDefinition) -> Map<PathSpec0, T>): Map<PathSpec0, T> = buildMap {
            putAll(registry(shallow))
            for ((modPath, module) in modules) {
                putAll(registry(module).mapKeys { (path, _) -> modPath + path })
            }
        }
        fun <T> flattenPathSpec(registry: (ServerDefinition) -> PathSpecMap<T>): PathSpecMap<T> = MutablePathSpecMap<T>().apply {
            putAll(PathSpec.root, registry(shallow))
            for ((modPath, module) in modules) {
                putAll(modPath, registry(module))
            }
        }

        return ServerDefinition(
            internalSerializersModule = modules.values.fold(shallow.internalSerializersModule) { acc, module -> acc + module.internalSerializersModule },
            externalSerializersModule = modules.values.fold(shallow.externalSerializersModule) { acc, module -> acc + module.externalSerializersModule },
            endpoints = flattenPathSpec { it.endpoints },
            schedules = flatten { it.schedules },
            tasks = flatten { it.tasks },
            webSocketTopics = flattenPathSpec { it.webSocketTopics },
            settings = (shallow.settings + modules.values.flatMap { it.settings }).distinctBy { it.settingName },
            extensions = shallow.extensions.toMutableExtensions().apply {
                modules.values.forEach { include(it.extensions) }
            }
        )
    }

    public fun shallowBuild(): ServerDefinition = ServerDefinition(
        internalSerializersModule = internalSerialization,
        externalSerializersModule = externalSerialization,
        endpoints = MutablePathSpecMap<ServerPathEndpoints>().apply {
            val httpInterceptor = http.interceptors.build()
            val websocketInterceptor = websockets.interceptors.build()

            for (path in http.handlers.keys + websockets.handlers.keys) {
                put(path, ServerPathEndpoints(
                    http = http
                        .handlers[path]
                        ?.mapValues { (_, handler) ->
                            httpInterceptor.intercept(handler)
                        }
                        ?: emptyMap(),

                    websocket = websockets
                        .handlers[path]
                        ?.let(websocketInterceptor::invoke)
                ))
            }
        },
        schedules = schedules,
        tasks = tasks,
        webSocketTopics = websockets.topics.registered,
        settings = settings,
        extensions = extensions
    )

    internal var modulePath: PathSpec0 = PathSpec.root
}