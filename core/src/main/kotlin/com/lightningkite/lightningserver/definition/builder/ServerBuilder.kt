package com.lightningkite.lightningserver.definition.builder

import com.lightningkite.lightningserver.definition.ScheduledTask
import com.lightningkite.lightningserver.definition.Task
import com.lightningkite.lightningserver.definition.Extendable
import com.lightningkite.lightningserver.definition.ModularServerDefinition
import com.lightningkite.lightningserver.definition.MutableExtensions
import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.definition.ServerPathEndpoints
import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.lightningserver.definition.StartupTask
import com.lightningkite.lightningserver.http.DefaultExceptionHttpHandler
import com.lightningkite.lightningserver.http.ExceptionHttpHandler
import com.lightningkite.lightningserver.http.HttpBuilder
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.intercept
import com.lightningkite.lightningserver.pathing.MutablePathSpecMap
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.serialization.MediaTypeDecoderRegistry
import com.lightningkite.lightningserver.serialization.MediaTypeEncoderRegistry
import com.lightningkite.lightningserver.websockets.WebSocketsBuilder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

/**
 * [ServerBuilder] provides a fluent, type-safe API for defining your server configuration.
 *
 * [ServerBuilder] is essentially a collection of registries for your endpoints, tasks, schedules, etc. You build a server by registering
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
 *     // index handler (just returns OK)
 *     val root = path.get bind HttpHandler { HttpResponse(status = HttpStatus.OK) }
 *
 *     // The "bind" infix fun you see above is provided by the dsl. It "binds" the
 *     // path on the left to the handler on the right.
 *
 *     // Basic hello world endpoint, bound to the endpoint POST /hello/world
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

    protected open val path: PathSpec0 get() = PathSpec.root // just for convenience

    public val settings: ListRegistry<ServerSetting<*, *>> = ListRegistry()

    public val http: HttpBuilder = HttpBuilder()
    public val websockets: WebSocketsBuilder = WebSocketsBuilder()
    public var exceptionHandler: ExceptionHttpHandler = DefaultExceptionHttpHandler

    public val startupTasks: MapRegistry<PathSpec0, StartupTask> = MapRegistry()
    public val schedules: MapRegistry<PathSpec0, ScheduledTask> = MapRegistry()
    public val tasks: MapRegistry<PathSpec0, Task<*>> = MapRegistry()

    public val mediaTypeDecoders: MediaTypeDecoderRegistry = MediaTypeDecoderRegistry()
    public val mediaTypeEncoders: MediaTypeEncoderRegistry = MediaTypeEncoderRegistry()

    public override val extensions: MutableExtensions = MutableExtensions()

    public val imports: MapRegistry<PathSpec0, ModularServerDefinition> = MapRegistry()
    public val modules: MapRegistry<PathSpec0, ServerBuilder> = MapRegistry()

    /**
     * Builds a complete, flattened [ServerDefinition] ready for runtime use.
     *
     * This is the primary method for converting a [ServerBuilder] into a deployable server configuration.
     * It performs a full build process that involves:
     * 1. Creating a [shallowBuild] of this builder's direct configuration
     * 2. Recursively builds all imported modules
     * 3. Flattens the modular structure into a single [ServerDefinition]
     *
     * @return A complete [ServerDefinition] with all modules flattened and ready for deployment
     * @see modularBuild
     * @see shallowBuild
     */
    public fun build(): ServerDefinition = modularBuild().flatten()

    /**
     * Builds a [ModularServerDefinition] that preserves the modular structure.
     *
     * This method creates a hierarchical server definition that maintains the separation
     * between the current builder's configuration and its nested modules. The resulting
     * structure can be inspected for debugging or flattened later using [ModularServerDefinition.flatten].
     *
     * The build process:
     * 1. Creates a [shallowBuild] of this builder's direct configuration
     * 2. Recursively builds all imported modules
     * 3. Combines them into a hierarchical structure with proper path mounting
     *
     * @return A [ModularServerDefinition] containing this builder's configuration and all nested modules
     * @see build
     * @see shallowBuild
     */
    public fun modularBuild(): ModularServerDefinition =
        ModularServerDefinition(
            definition = shallowBuild(),
            modules = imports + modules.mapValues { it.value.modularBuild() }
        )

    /**
     * Builds a [ServerDefinition] containing only this builder's direct configuration.
     *
     * This method creates a server definition that includes only the endpoints, settings,
     * and other configurations defined directly in this builder. It does not include
     * any nested modules or imported definitions.
     *
     * Use this when you need to:
     * - Inspect only the current builder's configuration
     * - Build a partial definition for testing
     * - Create a base definition that will be extended elsewhere
     *
     * This method called in both the [build] and [modularBuild] methods.
     *
     * The build process includes:
     * - Applying HTTP and WebSocket interceptors to all handlers
     * - Combining HTTP and WebSocket endpoints into [ServerPathEndpoints]
     * - Converting all registries into their corresponding read-only versions
     *
     * @return A [ServerDefinition] containing only this builder's direct configuration
     * @see build
     * @see modularBuild
     */
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
        extensions = extensions,
        exceptionHandler = exceptionHandler,
        startupTasks = startupTasks,
        mediaTypeDecoders = mediaTypeDecoders,
        mediaTypeEncoders = mediaTypeEncoders,
    )

    internal var modulePath: PathSpec0 = PathSpec.root
}