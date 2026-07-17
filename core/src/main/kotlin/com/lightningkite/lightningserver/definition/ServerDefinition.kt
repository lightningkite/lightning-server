package com.lightningkite.lightningserver.definition

import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.serialization.*
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.services.data.*
import com.lightningkite.services.database.validation.AnnotationValidators
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.plus
import kotlin.uuid.Uuid


/**
 * The immutable, runtime representation of a server's structure and resources.
 *
 * [ServerDefinition] is produced by building a [ServerBuilder] and contains all endpoints, tasks, schedules, settings,
 * and other server resources, organized in a tree structure. It is used by the server engine to route requests,
 * execute tasks, and provide runtime lookups for handlers and resources.
 *
 * The server is composed in a tree structure with a root [Module] (`thisLayer`) and any number of submodules (`modules`),
 * each of which may contain their own endpoints, tasks, schedules, settings, other resources, and nested submodules. This tree
 * structure is flattened before endpoints and other resources are routed during runtime.
 */
public data class ServerDefinition(
    val thisLayer: Module,
    val modules: List<Locationed<PathSpec0, ServerDefinition>> = emptyList(),
) : Extended {
    public data class Module(
        @InternalLightningServerApi public val moduleId: Uuid,

        public val internalSerializersModule: Runtime<SerializersModule>,
        public val externalSerializersModule: Runtime<SerializersModule>,
        public val annotationValidators: Runtime<AnnotationValidators>,

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
        public val settingOverrides: Map<ServerSetting<*, *>, Runtime<*>>,

        override val extensions: Extensions,
    ) : Extended

    internal val flattened by lazy { this.flatten().finalize() }

    public val internalSerializersModule: Runtime<SerializersModule> get() = flattened.internalSerializersModule
    public val externalSerializersModule: Runtime<SerializersModule> get() = flattened.externalSerializersModule

    public val annotationValidators: Runtime<AnnotationValidators> get() = flattened.annotationValidators

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
    public val settingOverrides: Map<ServerSetting<*, *>, Runtime<*>> get() = flattened.settingOverrides

    override val extensions: Extensions get() = flattened.extensions

    /**
     * Seals all registries, caches calculations, and performs final validation checks.
     * */
    @OptIn(InternalLightningServerApi::class)
    private fun Module.finalize(): Module = Module(
        moduleId = moduleId,
        internalSerializersModule = Runtime.Cached(internalSerializersModule),
        externalSerializersModule = Runtime.Cached(externalSerializersModule),
        annotationValidators = Runtime.Cached(annotationValidators),
        httpInterceptors = httpInterceptors.toSealedList(),
        websocketInterceptors = websocketInterceptors.toSealedList(),
        endpoints = endpoints.toSealedPathSpecMap(),
        schedules = schedules.toSealedMap(),
        tasks = tasks.toSealedMap(),
        webSocketTopics = webSocketTopics.toSealedPathSpecMap(),
        settings = settings.toSealedList(),
        settingOverrides = settingOverrides.toSealedMap(),
        extensions = extensions.sealed(),
        mediaTypeDecoders = mediaTypeDecoders.toSealedMap(),
        mediaTypeEncoders = mediaTypeEncoders.toSealedMap(),
        exceptionHandler = exceptionHandler,
        startupTasks = startupTasks.toSealedMap().also {
            // Validate startup task dependencies for circular references
            validateStartupTaskDependencies(it.values)
        },
    )

    /**
     * Flattens this definition into a single module recursively.
     * Flattening is **not** sealed or cached to prevent unnecessary intermediate allocations.
     * */
    private fun flatten(): Module {
        if (modules.isEmpty()) return thisLayer

        val flattenedModules = modules.mapItems { it.flatten() }
        // Cache this to avoid repeated list creation in serializers module lambdas
        val flattenedModuleItems = flattenedModules.map { it.item }

        fun <T> flattenList(registry: (Module) -> List<T>): List<T> {
            // list reallocation is pretty inexpensive, getting the size of this top layer is 0-cost.
            // Finding the cost of all layers is O(m) where m is # of modules, cost of iteration is unneeded, just estimate.
            val thisLayer = registry(thisLayer)
            return buildList(thisLayer.size + flattenedModules.size) {
                addAll(thisLayer)
                for ((_, module) in flattenedModules) {
                    addAll(registry(module))
                }
            }
        }

        fun <T> flattenMap(registry: (Module) -> Map<PathSpec0, T>): Map<PathSpec0, T> {
            // Map reallocation is more expensive, recalculating all hash buckets. Worth the cost of iteration.
            val thisLayerMap = registry(thisLayer)
            return buildMap(thisLayerMap.size + flattenedModules.sumOf { registry(it.value).size }) {
                putAll(thisLayerMap)
                for ((modPath, module) in flattenedModules) {
                    for ((path, value) in registry(module)) put(modPath + path, value)
                }
            }
        }

        return Module(
            moduleId = Uuid.NIL,    // When flattening module identification loses meaning
            internalSerializersModule = { flattenedModuleItems.fold(thisLayer.internalSerializersModule()) { acc, module -> acc + module.internalSerializersModule() } },
            externalSerializersModule = { flattenedModuleItems.fold(thisLayer.externalSerializersModule()) { acc, module -> acc + module.externalSerializersModule() } },
            annotationValidators = { flattenedModuleItems.fold(thisLayer.annotationValidators()) { acc, module -> acc + module.annotationValidators() } },
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
                                if (intersection.isNotEmpty()) throw DuplicateRegistrationError(
                                    "Endpoints ${
                                        intersection.map {
                                            HttpEndpoint(
                                                path,
                                                it
                                            )
                                        }
                                    } already have registered handlers", previous.http, endpoints.http
                                )
                                if (previous.websocket != null && endpoints.websocket != null) throw DuplicateRegistrationError(
                                    "Path $path already has a registered websocket",
                                    previous.websocket,
                                    endpoints.websocket
                                )
                                put(
                                    path, ServerPathEndpoints(
                                        previous.http + endpoints.http,
                                        previous.websocket ?: endpoints.websocket
                                    )
                                )
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
            // Preallocate settings list to avoid intermediate allocations
            settings = buildList(thisLayer.settings.size + flattenedModules.sumOf { it.item.settings.size }) {
                addAll(thisLayer.settings)
                for ((_, mod) in flattenedModules) addAll(mod.settings)
            },
            settingOverrides = buildMapRegistry {
                include(thisLayer.settingOverrides)
                for ((_, mod) in flattenedModules) include(mod.settingOverrides)
            },
            extensions = thisLayer.extensions.toMutableExtensions().apply {
                flattenedModules.forEach { include(it.item.extensions) }
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
    public fun <P : PathSpec> location(handler: HttpHandler<P>): HttpEndpoint<P>? =
        reverseLookupHttpHandler[handler]?.let { it as HttpEndpoint<P> }

    private val reverseLookupWebSocketHandler: Map<WebSocketHandler<*, *>, PathSpec> by lazy {
        endpoints.entries.mapNotNull {
            (it.value.websocket ?: return@mapNotNull null) to it.key
        }.associate { it }
    }

    @Suppress("UNCHECKED_CAST")
    public fun <P : PathSpec> location(handler: WebSocketHandler<P, *>): P? =
        reverseLookupWebSocketHandler[handler]?.let { it as P }

    private val reverseLookupWebSocketTopic: Map<WebSocketTopic<*, *>, PathSpec> by lazy {
        webSocketTopics.entries.associate { it.value to it.key }
    }

    @Suppress("UNCHECKED_CAST")
    public fun <P : PathSpec> location(handler: WebSocketTopic<P, *>): P? =
        reverseLookupWebSocketTopic[handler]?.let { it as P }

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

    @OptIn(InternalLightningServerApi::class)
    private val reverseLookupServerModule: Map<Uuid, PathSpec0> by lazy {
        buildMap {
            fun put(path: PathSpec0, def: ServerDefinition) {
                put(def.thisLayer.moduleId, path)
                for ((modPath, mod) in def.modules) put(path + modPath, mod)
            }
            put(PathSpec.root, this@ServerDefinition)
        }
    }

    @OptIn(InternalLightningServerApi::class)
    public fun location(module: ServerDefinition): PathSpec0? = reverseLookupServerModule[module.thisLayer.moduleId]
    @OptIn(InternalLightningServerApi::class)
    public fun location(module: ServerBuilder): PathSpec0? = reverseLookupServerModule[module.moduleId]
}