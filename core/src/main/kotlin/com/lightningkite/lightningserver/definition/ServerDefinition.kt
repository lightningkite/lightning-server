package com.lightningkite.lightningserver.definition

import com.lightningkite.MediaType
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.serialization.MediaTypeDecoder
import com.lightningkite.lightningserver.serialization.MediaTypeDecoderRegistry
import com.lightningkite.lightningserver.serialization.MediaTypeEncoder
import com.lightningkite.lightningserver.serialization.MediaTypeEncoderRegistry
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketHandlerInterceptor
import com.lightningkite.lightningserver.websockets.WebSocketTopic
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.plus

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
    public val websocketInterceptors: List<WebSocketHandlerInterceptor> get() = flattened.websocketInterceptors
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

        fun <T> flattenList(registry: (Module) -> List<T>): List<T> = buildList {
            addAll(registry(thisLayer))
            for ((modPath, module) in flattenedModules) {
                addAll(registry(module))
            }
        }

        fun <T> flattenMap(registry: (Module) -> Map<PathSpec0, T>): Map<PathSpec0, T> = buildMap {
            putAll(registry(thisLayer))
            for ((modPath, module) in flattenedModules) {
                putAll(registry(module).mapKeys { (path, _) -> modPath + path })
            }
        }

        fun <T> flattenPathSpec(registry: (Module) -> PathSpecMap<T>): PathSpecMap<T> = MutablePathSpecMap<T>().apply {
            putAll(com.lightningkite.lightningserver.pathing.PathSpec.root, registry(thisLayer))
            for ((modPath, module) in flattenedModules) {
                putAll(modPath, registry(module))
            }
        }

        return Module(
            internalSerializersModule = Runtime.Cached { flattenedModules.map { it.item }.fold(thisLayer.internalSerializersModule()) { acc, module -> acc + module.internalSerializersModule() } },
            externalSerializersModule = Runtime.Cached { flattenedModules.map { it.item }.fold(thisLayer.externalSerializersModule()) { acc, module -> acc + module.externalSerializersModule() } },
            httpInterceptors = flattenList { it.httpInterceptors },
            websocketInterceptors = flattenList { it.websocketInterceptors },
            endpoints = flattenPathSpec { it.endpoints },
            schedules = flattenMap { it.schedules },
            tasks = flattenMap { it.tasks },
            webSocketTopics = flattenPathSpec { it.webSocketTopics },
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
            startupTasks = flattenMap { it.startupTasks },
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
    public fun <P: PathSpec> location(handler: HttpHandler<P>): HttpEndpoint<P> = reverseLookupHttpHandler[handler] as HttpEndpoint<P>

    private val reverseLookupWebSocketHandler: Map<WebSocketHandler<*, *>, PathSpec> by lazy {
        endpoints.entries.mapNotNull {
            (it.value.websocket ?: return@mapNotNull null) to it.key
        }.associate { it }
    }
    @Suppress("UNCHECKED_CAST")
    public fun <P: PathSpec> location(handler: WebSocketHandler<P, *>): P = reverseLookupWebSocketHandler[handler] as P

    private val reverseLookupWebSocketTopic: Map<WebSocketTopic<*, *>, PathSpec> by lazy {
        webSocketTopics.entries.associate { it.value to it.key }
    }
    @Suppress("UNCHECKED_CAST")
    public fun <P: PathSpec> location(handler: WebSocketTopic<P, *>): P = reverseLookupWebSocketTopic[handler] as P

    private val reverseLookupTask: Map<Task<*>, PathSpec0> by lazy {
        tasks.entries.associate { it.value to it.key }
    }
    public fun location(handler: Task<*>): PathSpec0 = reverseLookupTask[handler]!!

    private val reverseLookupStartupTask: Map<StartupTask, PathSpec0> by lazy {
        startupTasks.entries.associate { it.value to it.key }
    }
    public fun location(handler: StartupTask): PathSpec0 = reverseLookupStartupTask[handler]!!

    private val reverseLookupScheduledTask: Map<ScheduledTask, PathSpec0> by lazy {
        schedules.entries.associate { it.value to it.key }
    }
    public fun location(handler: ScheduledTask): PathSpec0 = reverseLookupScheduledTask[handler]!!
}