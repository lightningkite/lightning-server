package com.lightningkite.lightningserver.definition

import com.lightningkite.MediaType
import com.lightningkite.buildSealedList
import com.lightningkite.buildSealedMap
import com.lightningkite.lightningserver.definition.builder.DuplicateRegistrationError
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.builder.buildListRegistry
import com.lightningkite.lightningserver.definition.builder.buildMapRegistry
import com.lightningkite.lightningserver.definition.builder.include
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.serialization.MediaTypeDecoder
import com.lightningkite.lightningserver.serialization.MediaTypeDecoderRegistry
import com.lightningkite.lightningserver.serialization.MediaTypeEncoder
import com.lightningkite.lightningserver.serialization.MediaTypeEncoderRegistry
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketHandlerInterceptor
import com.lightningkite.lightningserver.websockets.WebSocketTopic
import com.lightningkite.lightningserver.websockets.compileAndInstrument
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.plus


/**
 * The immutable, runtime representation of a server's structure and resources.
 *
 * [ServerDefinition] is produced by building a [ServerBuilder] and contains all endpoints, tasks, schedules, settings,
 * and other server resources, organized in a tree structure. It is used by the server engine to route requests,
 * execute tasks, and provide runtime lookups for handlers and resources.
 *
 * The server is composed in a tree structure with a root [Module] (`thisLayer`) and any number of submodules (`modules`),
 * each of which may contain their own endpoints, tasks, schedules, settings, other resources, and nested submodules.
 */
public data class ServerDefinition(
    val thisLayer: Module,
    val modules: List<Locationed<PathSpec0, ServerDefinition>> = emptyList(),
) : Extended {
    public data class Module(
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
        override val extensions: Extensions,
    ) : Extended

    private val flattened by lazy { this.flatten() }

    public val internalSerializersModule: Runtime<SerializersModule> get() = flattened.internalSerializersModule
    public val externalSerializersModule: Runtime<SerializersModule> get() = flattened.externalSerializersModule

    public val endpoints: PathSpecMap<ServerPathEndpoints> get() = flattened.endpoints
    public val httpInterceptors: List<HttpInterceptor> get() = flattened.httpInterceptors
    public val compiledHttpInterceptors: HttpInterceptor by lazy { httpInterceptors.compileAndInstrument() }
    public val websocketInterceptors: List<WebSocketHandlerInterceptor> get() = flattened.websocketInterceptors
    public val compiledWebsocketInterceptors: WebSocketHandlerInterceptor by lazy { websocketInterceptors.compileAndInstrument() }
    public val exceptionHandler: ExceptionHttpHandler get() = flattened.exceptionHandler

    public val startupTasks: Map<PathSpec0, StartupTask> get() = flattened.startupTasks
    public val tasks: Map<PathSpec0, Task<*>> get() = flattened.tasks
    public val schedules: Map<PathSpec0, ScheduledTask> get() = flattened.schedules

    public val mediaTypeDecoders: Map<MediaType, List<MediaTypeDecoder>> get() = flattened.mediaTypeDecoders
    public val mediaTypeEncoders: Map<MediaType, List<MediaTypeEncoder>> get() = flattened.mediaTypeEncoders

    public val webSocketTopics: PathSpecMap<WebSocketTopic<*, *>> get() = flattened.webSocketTopics
    public val settings: List<ServerSetting<*, *>> get() = flattened.settings
    override val extensions: Extensions get() = flattened.extensions


    private fun flatten(): Module {
        if (modules.isEmpty()) return thisLayer

        val flattenedModules = modules.mapItems { it.flatten() }

        fun <T> flattenList(registry: (Module) -> List<T>): List<T> = buildSealedList {
            addAll(registry(thisLayer))
            for ((modPath, module) in flattenedModules) {
                addAll(registry(module))
            }
        }

        fun <T> flattenMap(registry: (Module) -> Map<PathSpec0, T>): Map<PathSpec0, T> = buildMapRegistry {
            include(registry(thisLayer))
            for ((modPath, module) in flattenedModules) {
                include(registry(module).mapKeys { (path, _) -> modPath + path })
            }
        }

        return Module(
            internalSerializersModule = Runtime.Cached { flattenedModules.map { it.item }.fold(thisLayer.internalSerializersModule()) { acc, module -> acc + module.internalSerializersModule() } },
            externalSerializersModule = Runtime.Cached { flattenedModules.map { it.item }.fold(thisLayer.externalSerializersModule()) { acc, module -> acc + module.externalSerializersModule() } },
            httpInterceptors = flattenList { it.httpInterceptors },
            websocketInterceptors = flattenList { it.websocketInterceptors },
            endpoints = buildPathSpecMap { // We want to be able to override existing entries here, but we'll have to check for duplicate registration manually.
                putAll(thisLayer.endpoints)
                for ((modPath, map) in flattenedModules.mapItems { it.endpoints })
                    for ((relPath, endpoints) in map) {
                        val path = modPath + relPath
                        get(path)
                            ?.let { previous ->
                                val intersection = endpoints.http.keys.intersect(previous.http.keys)
                                if (intersection.isNotEmpty()) throw DuplicateRegistrationError("Endpoints ${intersection.map { HttpEndpoint(path, it) }} already have registered handlers", previous.http, endpoints.http)
                                if (previous.websocket != null && endpoints.websocket != null) throw DuplicateRegistrationError("Path $path already has a registered websocket", previous.websocket, endpoints.websocket)
                                put(path, ServerPathEndpoints(
                                    previous.http + endpoints.http,
                                    previous.websocket ?: endpoints.websocket
                                ))
                            }
                            ?: put(path, endpoints)
                    }
            },
            schedules = flattenMap { it.schedules },
            tasks = flattenMap { it.tasks },
            webSocketTopics = buildPathSpecRegistry {
                include(thisLayer.webSocketTopics)
                for ((modPath, module) in flattenedModules)
                    for ((relPath, topic) in module.webSocketTopics)
                        register(modPath + relPath, topic)
            },
            settings = (thisLayer.settings + flattenedModules.flatMap { it.item.settings }).distinctBy { it.name },
            extensions = thisLayer.extensions.toMutableExtensions().apply {
                flattenedModules.forEach { include(it.item.extensions, it.location) }
            },
            mediaTypeDecoders = MediaTypeDecoderRegistry().apply {
                include(thisLayer.mediaTypeDecoders)
                for ((_, mod) in flattenedModules) include(mod.mediaTypeDecoders)
            },
            mediaTypeEncoders = MediaTypeEncoderRegistry().apply {
                include(thisLayer.mediaTypeEncoders)
                for ((_, mod) in flattenedModules) include(mod.mediaTypeEncoders)
            },
            exceptionHandler = thisLayer.exceptionHandler,
            startupTasks = flattenMap { it.startupTasks }.also { tasks ->
                // Validate startup task dependencies for circular references
                validateStartupTaskDependencies(tasks.values)
            },
        )
    }

    private val reverseLookupHttpHandler: Map<HttpHandler<*>, HttpEndpoint<*>> by lazy {
        endpoints.entries.flatMap { (path, group) ->
            group.http.entries.map { (method, handler) ->
                handler to HttpEndpoint(path, method)
            }
        }.associate { it }
    }
    @Suppress("UNCHECKED_CAST")
    public fun <P: PathSpec> location(handler: HttpHandler<P>): HttpEndpoint<P>? = reverseLookupHttpHandler[handler]?.let { it as HttpEndpoint<P> }

    private val reverseLookupWebSocketHandler: Map<WebSocketHandler<*, *>, PathSpec> by lazy {
        endpoints.entries.mapNotNull {
            (it.value.websocket ?: return@mapNotNull null) to it.key
        }.associate { it }
    }
    @Suppress("UNCHECKED_CAST")
    public fun <P: PathSpec> location(handler: WebSocketHandler<P, *>): P? = reverseLookupWebSocketHandler[handler]?.let { it as P }

    private val reverseLookupWebSocketTopic: Map<WebSocketTopic<*, *>, PathSpec> by lazy {
        webSocketTopics.entries.associate { it.value to it.key }
    }
    @Suppress("UNCHECKED_CAST")
    public fun <P: PathSpec> location(handler: WebSocketTopic<P, *>): P? = reverseLookupWebSocketTopic[handler]?.let { it as P }

    private val reverseLookupTask: Map<Task<*>, PathSpec0> by lazy {
        tasks.entries.associate { it.value to it.key }
    }
    public fun location(handler: Task<*>): PathSpec0? = reverseLookupTask[handler]

    private val reverseLookupStartupTask: Map<StartupTask, PathSpec0> by lazy {
        startupTasks.entries.associate { it.value to it.key }
    }
    public fun location(handler: StartupTask): PathSpec0? = reverseLookupStartupTask[handler]

    private val reverseLookupScheduledTask: Map<ScheduledTask, PathSpec0> by lazy {
        schedules.entries.associate { it.value to it.key }
    }
    public fun location(handler: ScheduledTask): PathSpec0? = reverseLookupScheduledTask[handler]
}