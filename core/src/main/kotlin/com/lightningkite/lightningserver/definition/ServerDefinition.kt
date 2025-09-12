package com.lightningkite.lightningserver.definition

import com.lightningkite.MediaType
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.PathSpecMap
import com.lightningkite.lightningserver.pathing.MutablePathSpecMap
import com.lightningkite.lightningserver.pathing.plus
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.DefaultExceptionHttpHandler
import com.lightningkite.lightningserver.http.ExceptionHttpHandler
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.HttpInterceptor
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.serialization.MediaTypeDecoder
import com.lightningkite.lightningserver.serialization.MediaTypeDecoderRegistry
import com.lightningkite.lightningserver.serialization.MediaTypeEncoder
import com.lightningkite.lightningserver.serialization.MediaTypeEncoderRegistry
import com.lightningkite.lightningserver.serialization.debugString
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketHandlerInterceptor
import com.lightningkite.lightningserver.websockets.WebSocketTopic
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.plus

/**
 * An immutable, runtime-ready server configuration containing all endpoints, tasks, and settings.
 *
 * [ServerDefinition] represents a finalized and immutable configuration of a Lightning Server application.
 * It contains all the resources needed to run the server at runtime, with all modular components
 * merged into a single cohesive definition. This is typically created by [ServerBuilder.build].
 *
 * ## Structure Overview
 *
 * A server definition consists of several key components:
 * - **Endpoints**: HTTP and WebSocket handlers organized by path
 * - **Background Processing**: Scheduled tasks and on-demand tasks
 * - **Serialization**: Type-safe serialization for internal and external communication
 * - **Configuration**: Application settings and extensions
 *
 * ## Lifecycle
 *
 * Server definitions are typically created during application startup:
 * ```kotlin
 * object MyServer : ServerBuilder() {
 *     // Define endpoints, tasks, etc.
 * }
 *
 * val definition: ServerDefinition = MyServer.build()
 * // Pass definition to your chosen engine (Ktor, Vert.x, etc.)
 * ```
 *
 * ## Thread Safety
 *
 * [ServerDefinition] is immutable and thread-safe once created. All collections are read-only
 * and the definition can be safely shared across multiple threads and used by concurrent
 * request handlers.
 *
 * @property internalSerializersModule Kotlinx.serialization module for internal server communication
 *           (e.g., database serialization, internal APIs). This is separate from external serialization
 *           to allow different formats or security policies for internal vs external data.
 *
 * @property externalSerializersModule Kotlinx.serialization module for external client communication
 *           (e.g., REST API responses, WebSocket messages). This defines how data is serialized
 *           when communicating with external clients.
 *
 * @property endpoints Map of URL paths to their HTTP and WebSocket handlers. Each path can have
 *           multiple HTTP methods (GET, POST, etc.) and optionally a WebSocket handler.
 *           Uses [PathSpecMap] for efficient path-based routing with support for path parameters.
 *
 * @property schedules Map of scheduled tasks that run on a recurring basis (e.g., cleanup jobs,
 *           data synchronization). Keys are path identifiers for organization, values are
 *           [ScheduledTask] instances with their timing configuration.
 *
 * @property tasks Map of on-demand tasks that can be triggered programmatically or via admin
 *           interfaces. These are typically long-running operations like data migrations,
 *           report generation, or bulk processing jobs.
 *
 * @property webSocketTopics Map of WebSocket topics for pub/sub messaging. These define
 *           channels that clients can subscribe to for real-time updates. Uses [PathSpecMap]
 *           for topic organization and routing.
 *
 * @property settings List of all configuration settings used by the server. These can be
 *           environment variables, config file values, or other configuration sources.
 *           Settings are type-safe and can be accessed throughout the application.
 *
 * @property extensions Extension registry containing additional server capabilities like
 *           database connections, caching, authentication providers, etc. This allows
 *           modular functionality to be plugged into the server definition.
 *
 * @see ServerBuilder
 * @see ModularServerDefinition for hierarchical server definitions that can be flattened
 * @see ServerPathEndpoints for the structure of endpoint definitions
 */
public data class ServerDefinition(
    public val internalSerializersModule: Runtime<SerializersModule>,
    public val externalSerializersModule: Runtime<SerializersModule>,

    public val endpoints: PathSpecMap<ServerPathEndpoints>,
    public val httpInterceptors: List<HttpInterceptor>,
    public val websocketInterceptors: List<WebSocketHandlerInterceptor>,
    public val exceptionHandler: ExceptionHttpHandler = DefaultExceptionHttpHandler,

    public val startupTasks: Map<PathSpec0, StartupTask>,
    public val tasks: Map<PathSpec0, Task<*>>,
    public val schedules: Map<PathSpec0, ScheduledTask>,

    public val mediaTypeDecoders: Map<MediaType, List<MediaTypeDecoder>>,
    public val mediaTypeEncoders: Map<MediaType, List<MediaTypeEncoder>>,

    public val webSocketTopics: PathSpecMap<WebSocketTopic<*, *>>,
    public val settings: List<ServerSetting<*, *>>,
    public override val extensions: Extensions,
) : Extended {
    private val reverseLookupHttpHandler: Map<HttpHandler<*>, HttpEndpoint<*>> =
        endpoints.entries.flatMap { (path, group) ->
            group.http.entries.map { (method, handler) ->
                handler to HttpEndpoint(path, method)
            }
        }.associate { it }

    @Suppress("UNCHECKED_CAST")
    public fun <P : PathSpec> location(handler: HttpHandler<P>): HttpEndpoint<P> =
        reverseLookupHttpHandler[handler] as HttpEndpoint<P>

    private val reverseLookupWebSocketHandler: Map<WebSocketHandler<*, *>, PathSpec> = endpoints.entries.mapNotNull {
        (it.value.websocket ?: return@mapNotNull null) to it.key
    }.associate { it }

    @Suppress("UNCHECKED_CAST")
    public fun <P : PathSpec> location(handler: WebSocketHandler<P, *>): P = reverseLookupWebSocketHandler[handler] as P

    private val reverseLookupWebSocketTopic: Map<WebSocketTopic<*, *>, PathSpec> =
        webSocketTopics.entries.associate { it.value to it.key }

    @Suppress("UNCHECKED_CAST")
    public fun <P : PathSpec> location(handler: WebSocketTopic<P, *>): P = reverseLookupWebSocketTopic[handler] as P

    private val reverseLookupTask: Map<Task<*>, PathSpec0> = tasks.entries.associate { it.value to it.key }
    public fun location(handler: Task<*>): PathSpec0 = reverseLookupTask[handler]!!

    private val reverseLookupStartupTask: Map<StartupTask, PathSpec0> =
        startupTasks.entries.associate { it.value to it.key }

    public fun location(handler: StartupTask): PathSpec0 = reverseLookupStartupTask[handler]!!

    private val reverseLookupScheduledTask: Map<ScheduledTask, PathSpec0> =
        schedules.entries.associate { it.value to it.key }

    public fun location(handler: ScheduledTask): PathSpec0 = reverseLookupScheduledTask[handler]!!

}


/**
 * A hierarchical server definition that preserves modular structure before flattening.
 *
 * [ModularServerDefinition] represents a server configuration that maintains the separation
 * between a base definition and its nested modules. This allows for inspection of the modular
 * structure, debugging of module composition, and controlled flattening into a runtime-ready
 * [ServerDefinition].
 *
 * ## Purpose & Design
 *
 * While [ServerDefinition] is the final flattened configuration used at runtime,
 * [ModularServerDefinition] serves as an intermediate representation that:
 * - Preserves the original modular architecture for debugging and introspection
 * - Enables incremental composition of server definitions from multiple sources
 * - Supports complex module hierarchies with proper path mounting
 * - Allows delayed flattening until the complete structure is assembled
 *
 * ## Module Hierarchy
 *
 * Modules are organized as a tree structure where each module can contain its own nested modules:
 * ```kotlin
 * object UserEndpoints : ServerBuilder() {
 *     val getUser = path.arg<UserId>("userId").get bind HttpHandler { userId ->
 *         // Get user logic
 *     }
 * }
 *
 * object AdminEndpoints : ServerBuilder() {
 *     val deleteUser = path.path("users").arg<UserId>("userId").delete bind HttpHandler { userId ->
 *         // Delete user logic
 *     }
 * }
 *
 * object MetaEndpoints : ServerBuilder() {
 *     val getPublicInfo = path.path("info").get bind HttpHandler {
 *         HttpResponse.plainText("Public information")
 *     }
 * }
 *
 * object ApiV1 : ServerBuilder() {
 *     val userEndpoints = path.path("users") bind UserEndpoints
 *     val adminModule = path.path("admin") bind AdminEndpoints
 *     val publicModule = path.path("meta") bind MetaEndpoints
 * }
 *
 * object RootServer : ServerBuilder() {
 *     val apiV1 = path.path("api").path("v1") bind ApiV1
 *     val index = path.get bind HttpHandler {
 *         HttpResponse()
 *     }
 * }
 * ```
 *
 * ## Path Resolution
 *
 * When flattened, module paths are combined with their mount points:
 * - A module at `/api/v1` with endpoint `/users` becomes `/api/v1/users`
 * - Nested modules combine all parent paths: `/api/v1/admin/users`
 * - Root-level paths in modules are preserved relative to their mount point
 *
 * ## Common Usage Patterns
 *
 * [ModularServerDefinition]s are typically created by a [ServerBuilder] tree.
 *
 * ```kotlin
 * object UserEndpoints : ServerBuilder() {
 *     val getUser = path.path("users").path(userId).get bind HttpHandler { userId ->
 *         // Get user logic
 *     }
 * }
 *
 * object AdminEndpoints : ServerBuilder() {
 *     val deleteUser = path.path("users").arg<UserId>("userId").delete bind HttpHandler { userId ->
 *         // Delete user logic
 *     }
 * }
 *
 * object MainServer : ServerBuilder() {
 *     // Mount user endpoints at /api/users
 *     val userApi = path.path("api") bind UserEndpoints
 *
 *     // Mount admin endpoints at /admin
 *     val adminApi = path.path("admin") bind AdminEndpoints
 *
 *     // Root level endpoint
 *     val health = path.path("health").get bind HttpHandler {
 *         HttpResponse.plainText("OK")
 *     }
 * }
 *
 * // Build modular structure
 * val modularDef: ModularServerDefinition = MainServer.modularBuild()
 *
 * // Flatten for runtime use
 * val serverDef: ServerDefinition = MainServer.build()
 * ```
 *
 * **Importing External Modules:**
 * ```kotlin
 * object CoreServer : ServerBuilder() {
 *     // Import a pre-built modular definition
 *     val externalModule = path.path("external") bind someExternalModularDefinition
 *
 *     // Mix with regular ServerBuilder modules
 *     val userApi = path.path("users") bind UserEndpoints
 *
 *     val health = path.path("health").get bind HttpHandler {
 *         HttpResponse.plainText("OK")
 *     }
 * }
 * ```
 *
 * ## Performance Considerations
 *
 * [ModularServerDefinition] is designed for composition and inspection, not runtime use.
 * Always call [flatten] to flatten the structure before passing to your
 * server engine. The flattening operation should be done once during
 * application startup.
 *
 * @property definition The base [ServerDefinition] for this module level, containing
 *           endpoints, tasks, and settings defined directly at this level (not inherited
 *           from nested modules). This forms the "root" of this modular definition.
 *
 * @property modules Map of path mount points to nested [ModularServerDefinition] instances.
 *           Each entry represents a sub-module mounted at the specified path. When flattened,
 *           all paths from nested modules will be prefixed with their mount path.
 *           Empty by default for leaf modules with no nested structure.
 *
 * @see ServerDefinition
 * @see ServerBuilder.modularBuild
 * @see flatten
 */
public data class ModularServerDefinition(
    val definition: ServerDefinition,
    val modules: Map<PathSpec0, ModularServerDefinition> = emptyMap(),
    val parent: ModularServerDefinition? = null
) : Extended by definition {
    /**
     * Flattens this modular server definition into a single [ServerDefinition] ready for runtime use.
     *
     * This method recursively processes all nested modules and combines them into a unified server definition.
     * It's the final step in [ServerBuilder.build] - where the builder creates a modular structure and then
     * flattens it, this method handles the flattening step.
     *
     * The flattening process:
     * 1. **Recursively flattens nested modules**: Each module in the hierarchy is flattened first to ensure
     *    deep nesting is properly handled
     * 2. **Path prefixing**: All paths from nested modules are prefixed with their mount path to maintain
     *    proper routing in the flattened structure
     * 3. **Resource merging**: All server resources are combined using appropriate merge strategies:
     *    - **Serialization modules**: Combined using kotlinx.serialization's `+` operator
     *    - **Endpoints & WebSocket topics**: Merged using [PathSpecMap] with proper path mounting
     *    - **Schedules & tasks**: Combined with path prefixing to avoid conflicts
     *    - **Settings**: Merged, and kept distinct by `settingName`. Settings declared higher up
     *      in the higherarchy take precedence.
     *    - **Extensions**: Combined using `MutableExtensions.include`. Any extensions in modules with
     *      existing keys will be discarded, unless it is a `DegradingKey`, in which case
     *      `DegradingKey.include` is used to merge the extensions.
     *
     *
     * **Performance Note**: This is an expensive operation that should typically be done once during
     * server startup, not per-request. The resulting [ServerDefinition] should be stored and reused.
     *
     * @return A flattened [ServerDefinition] containing all configurations from this definition and
     *         all nested modules, with proper path resolution and resource merging
     * @see ServerBuilder.build for the typical build-and-flatten workflow
     * @see ServerBuilder.modularBuild for creating modular definitions from builders
     */
    public fun flatten(): ServerDefinition {
        if (modules.isEmpty()) return definition

        val flattenedModules = modules.mapValues { (_, module) -> module.flatten() }

        fun <T> flattenList(registry: (ServerDefinition) -> List<T>): List<T> = buildList {
            addAll(registry(definition))
            for ((modPath, module) in flattenedModules) {
                addAll(registry(module))
            }
        }

        fun <T> flatten(registry: (ServerDefinition) -> Map<PathSpec0, T>): Map<PathSpec0, T> = buildMap {
            putAll(registry(definition))
            for ((modPath, module) in flattenedModules) {
                putAll(registry(module).mapKeys { (path, _) -> modPath + path })
            }
        }

        fun <T> flattenPathSpec(registry: (ServerDefinition) -> PathSpecMap<T>): PathSpecMap<T> =
            MutablePathSpecMap<T>().apply {
                putAll(com.lightningkite.lightningserver.pathing.PathSpec.root, registry(definition))
                for ((modPath, module) in flattenedModules) {
                    putAll(modPath, registry(module))
                }
            }

        return ServerDefinition(
            internalSerializersModule = Runtime.Cached { flattenedModules.values.fold(definition.internalSerializersModule()) { acc, module -> acc + module.internalSerializersModule() } },
            externalSerializersModule = Runtime.Cached { flattenedModules.values.fold(definition.externalSerializersModule()) { acc, module -> acc + module.externalSerializersModule() } },
            httpInterceptors = flattenList { it.httpInterceptors },
            websocketInterceptors = flattenList { it.websocketInterceptors },
            endpoints = flattenPathSpec { it.endpoints },
            schedules = flatten { it.schedules },
            tasks = flatten { it.tasks },
            webSocketTopics = flattenPathSpec { it.webSocketTopics },
            settings = (definition.settings + flattenedModules.values.flatMap { it.settings }).distinctBy { it.name },
            extensions = definition.extensions.toMutableExtensions().apply {
                flattenedModules.entries.forEach { include(it.value.extensions, it.key) }
            },
            mediaTypeDecoders = MediaTypeDecoderRegistry().apply {
                include(definition.mediaTypeDecoders)
                for ((_, mod) in flattenedModules) include(mod.mediaTypeDecoders)
            },
            mediaTypeEncoders = MediaTypeEncoderRegistry().apply {
                include(definition.mediaTypeEncoders)
                for ((_, mod) in flattenedModules) include(mod.mediaTypeEncoders)
            },
            exceptionHandler = definition.exceptionHandler,
            startupTasks = flatten { it.startupTasks },
        )
    }
}