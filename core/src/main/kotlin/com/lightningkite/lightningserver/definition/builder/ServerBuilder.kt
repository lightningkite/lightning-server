package com.lightningkite.lightningserver.definition.builder

import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.serialization.*
import com.lightningkite.lightningserver.typedoutput.TypedOutputInterceptor
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.services.Setting
import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.toSealedList
import com.lightningkite.services.data.toSealedMap
import com.lightningkite.services.database.validation.AnnotationValidators
import com.lightningkite.services.database.validation.EmptyAnnotationValidators
import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlin.uuid.Uuid

/**
 * The primary entrypoint for creating and defining servers.
 *
 * [ServerBuilder] is essentially a collection of registries for your endpoints, tasks, schedules, etc. You build a server by registering
 * resources and their locations. Once the definition is complete the [build] method is used to construct an immutable
 * [ServerDefinition] for runtime use.
 *
 * Registration is done through the provided builder dsl. Http handlers, webSockets, tasks, and schedules are registered using [bind]
 * and settings are registered using [setting].
 *
 * Example:
 * ```kotlin
 * object Server : ServerBuilder() {
 *     override val internalSerialization: SerializersModule = EmptySerializersModule()
 *     override val externalSerialization: SerializersModule = EmptySerializersModule()
 *
 *     val serverName = setting("name", "MyServer")
 *
 *     // index handler
 *     val root = path.get bind HttpHandler {
 *        HttpResponse.plainText("Hello from ${serverName()}")
 *     }
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
 * Additionally, [ServerBuilder] is designed to be modular. It is used to define your root server definition, and
 * also endpoint groups, such as endpoints for specific models. Modules are registered using [include].
 *
 * ```kotlin
 * object ModelEndpoints : ServerBuilder() {
 *     // ...
 * }
 *
 * object Server : ServerBuilder() {
 *     // ...
 *
 *     val modelEndpoints = path.path("model") include ModelEndpoints
 * }
 * ```
 * */

@LightningServerDsl
public abstract class ServerBuilder : Extendable {
    @InternalLightningServerApi
    public val moduleId: Uuid = Uuid.random()

    protected open val internalSerialization: Runtime<SerializersModule> get() = Runtime.Constant(EmptySerializersModule())
    protected open val externalSerialization: Runtime<SerializersModule> get() = Runtime.Constant(EmptySerializersModule())

    protected open val annotationValidators: Runtime<AnnotationValidators>
        get() = Runtime.Constant(
            EmptyAnnotationValidators()
        )

    public val path: PathSpec0 get() = PathSpec.root // just for convenience

    private val settings: ListRegistry<ServerSetting<*, *>> = ListRegistry()
    private val settingOverrides: MapRegistry<ServerSetting<*, *>, Runtime<*>> = MapRegistry()

    private val httpConnectionInterceptors: ListRegistry<HttpConnectionInterceptor> = ListRegistry()
    private val httpLogicalInterceptors: ListRegistry<HttpLogicalInterceptor> = ListRegistry()
    private val httpHandlers: PathSpecRegistry<MapRegistry<HttpMethod, HttpHandler<*>>> = PathSpecRegistry()

    private val webSocketConnectionInterceptors: ListRegistry<WebSocketConnectionInterceptor> = ListRegistry()
    private val webSocketLogicalInterceptors: ListRegistry<WebSocketLogicalInterceptor> = ListRegistry()
    private val webSocketHandlers: PathSpecRegistry<WebSocketHandler<*, *>> = PathSpecRegistry()
    private val webSocketTopics: PathSpecRegistry<WebSocketTopic<*, *>> = PathSpecRegistry()

    private val typedOutputInterceptors: ListRegistry<TypedOutputInterceptor> = ListRegistry()

    private var exceptionHandler: ExceptionHttpHandler = DefaultExceptionHttpHandler

    private val preDeployTasks: MapRegistry<PathSpec0, PreDeployTask> = MapRegistry()
    private val startupTasks: MapRegistry<PathSpec0, StartupTask> = MapRegistry()
    private val schedules: MapRegistry<PathSpec0, ScheduledTask> = MapRegistry()
    private val tasks: MapRegistry<PathSpec0, Task<*>> = MapRegistry()

    private val mediaTypeDecoders: MediaTypeDecoderRegistry = MediaTypeDecoderRegistry()
    private val mediaTypeEncoders: MediaTypeEncoderRegistry = MediaTypeEncoderRegistry()

    public override val extensions: MutableExtensions = MutableExtensions()

    private val imports: ListRegistry<Locationed<PathSpec0, ServerDefinition>> = ListRegistry()
    private val modules: ListRegistry<Locationed<PathSpec0, ServerBuilder>> = ListRegistry()

    @JvmName("installHttpConnectionInterceptor")
    public fun <T : HttpConnectionInterceptor> install(interceptor: T): T =
        interceptor.also { httpConnectionInterceptors.register(it) }

    @JvmName("installHttpLogicalInterceptor")
    public fun <T : HttpLogicalInterceptor> install(interceptor: T): T =
        interceptor.also { httpLogicalInterceptors.register(it) }

    @JvmName("installWebSocketConnectionInterceptor")
    public fun <T : WebSocketConnectionInterceptor> install(interceptor: T): T =
        interceptor.also { webSocketConnectionInterceptors.register(it) }

    @JvmName("installWebSocketLogicalInterceptor")
    public fun <T : WebSocketLogicalInterceptor> install(interceptor: T): T =
        interceptor.also { webSocketLogicalInterceptors.register(it) }

    @JvmName("installHttpAndWebSocketConnectionInterceptor")
    public fun <T> install(interceptor: T): T where T : HttpConnectionInterceptor, T : WebSocketConnectionInterceptor =
        interceptor.also {
            httpConnectionInterceptors.register(it)
            webSocketConnectionInterceptors.register(it)
        }

    @JvmName("installHttpAndWebSocketLogicalInterceptor")
    public fun <T> install(interceptor: T): T where T : HttpLogicalInterceptor, T : WebSocketLogicalInterceptor =
        interceptor.also {
            httpLogicalInterceptors.register(it)
            webSocketLogicalInterceptors.register(it)
        }

    @JvmName("installTypedOutputInterceptor")
    public fun <T : TypedOutputInterceptor> install(interceptor: T): T =
        interceptor.also { typedOutputInterceptors.register(it) }

    public infix fun <PATH : PathSpec, HANDLER : HttpHandler<PATH>> HttpEndpoint<PATH>.bind(handler: HANDLER): HANDLER {
        httpHandlers.getOrRegister(this.path, ::MapRegistry).register(this.method, handler)
        return handler
    }

    public infix fun <PATH : PathSpec, STORAGE, T : WebSocketHandler<PATH, STORAGE>> PATH.bind(handler: T): T {
        webSocketHandlers.register(this, handler)
        return handler
    }

    public infix fun <T> PathSpec0.bind(task: Task<T>): Task<T> {
        tasks.register(this, task)
        return task
    }

    public infix fun PathSpec0.bind(startupTask: StartupTask): StartupTask {
        startupTasks.register(this, startupTask)
        return startupTask
    }

    public infix fun PathSpec0.bind(preDeployTask: PreDeployTask): PreDeployTask {
        preDeployTasks.register(this, preDeployTask)
        return preDeployTask
    }

    public infix fun PathSpec0.bind(schedule: ScheduledTask): ScheduledTask {
        schedules.register(this, schedule)
        return schedule
    }

    public fun <PATH : PathSpec, T> PATH.topic(type: KSerializer<T>): WebSocketTopic<PATH, T> {
        val topic = WebSocketTopic<PATH, T>(type)
        webSocketTopics.register(this, topic)
        return topic
    }

    public fun <Setting, Result> setting(setting: ServerSetting<Setting, Result>): ServerSetting<Setting, Result> {
        settings.register(setting)
        return setting
    }

    public fun <Setting, Result> setting(
        name: String,
        default: Setting,
        serializer: KSerializer<Setting>,
        instructions: String = "No instructions",
        optional: Boolean = false,
        getter: SettingContext.(Setting) -> Result,
    ): ServerSetting<Setting, Result> =
        setting(
            ServerSetting(
                name,
                default,
                serializer,
                instructions,
                optional,
            ) { value -> getter(this, value) }
        )

    public fun <SETTING : Setting<RESULT>, RESULT> setting(
        name: String,
        default: SETTING,
        serializer: KSerializer<SETTING>,
        instructions: String = "No instructions",
        optional: Boolean = false,
    ): ServerSetting<SETTING, RESULT> =
        setting(
            ServerSetting(
                name,
                default,
                serializer,
                instructions,
                optional,
            )
        )

    public inline fun <reified SETTING : Setting<RESULT>, RESULT> setting(
        name: String,
        default: SETTING,
        instructions: String = "No instructions",
        optional: Boolean = false,
    ): ServerSetting<SETTING, RESULT> =
        setting(
            ServerSetting(
                name,
                default,
                serializerOrContextual<SETTING>(),
                instructions,
                optional,
            )
        )

    public fun <Result> setting(
        name: String,
        default: Result,
        serializer: KSerializer<Result>,
        instructions: String = "No instructions",
        optional: Boolean = false,
    ): ServerSetting.Direct<Result> {
        val setting = ServerSetting(
            name,
            default,
            serializer,
            instructions,
            optional,
        )
        settings.register(setting)
        return setting
    }

    public inline fun <reified Setting, Result> setting(
        name: String,
        default: Setting,
        instructions: String = "No instructions",
        optional: Boolean = false,
        crossinline getter: SettingContext.(Setting) -> Result,
    ): ServerSetting<Setting, Result> =
        setting(
            ServerSetting(
                name,
                default,
                serializerOrContextual<Setting>(),
                instructions,
                optional,
            ) { value -> getter(this, value) }
        )

    public inline fun <reified Result> setting(
        name: String,
        default: Result,
        instructions: String = "No instructions",
        optional: Boolean = false,
    ): ServerSetting.Direct<Result> =
        setting(
            name,
            default,
            serializerOrContextual<Result>(),
            instructions,
            optional,
        )

    public infix fun <S, R> ServerSetting<S, R>.bind(deferTo: Runtime<R>): Runtime<R> {
        settingOverrides.register(this, deferTo)
        if (deferTo is ServerSetting<*, *>) settings.register(deferTo)
        return deferTo
    }

    public infix fun <T : ServerBuilder> PathSpec0.include(module: T): T {
        modules.register(Locationed(this, module))
        return module
    }

    public infix fun PathSpec0.include(import: ServerDefinition): ServerDefinition {
        imports.register(Locationed(this, import))
        return import
    }

    public fun register(decoder: MediaTypeDecoder) {
        mediaTypeDecoders.register(decoder)
    }

    public fun register(decoder: MediaTypeEncoder) {
        mediaTypeEncoders.register(decoder)
    }

    public fun register(coder: MediaTypeCoder) {
        mediaTypeDecoders.register(coder)
        mediaTypeEncoders.register(coder)
    }


    /**
     * Builds a complete, flattened [ServerDefinition] ready for runtime use.
     *
     * This is the primary method for converting a [ServerBuilder] into a deployable server configuration.
     * It performs a full build process that involves:
     * 1. Creating a [ServerDefinition.Module] of this builder's direct configuration
     * 2. Recursively building all imported modules
     *
     * @return A complete [ServerDefinition] with all modules flattened and ready for deployment
     */
    @OptIn(InternalLightningServerApi::class)
    public fun build(): ServerDefinition = ServerDefinition(
        thisLayer = ServerDefinition.Module(
            moduleId = moduleId,
            internalSerializersModule = internalSerialization,
            externalSerializersModule = externalSerialization,
            annotationValidators = annotationValidators,
            httpConnectionInterceptors = httpConnectionInterceptors.toSealedList(),
            httpLogicalInterceptors = httpLogicalInterceptors.toSealedList(),
            webSocketConnectionInterceptors = webSocketConnectionInterceptors.toSealedList(),
            webSocketLogicalInterceptors = webSocketLogicalInterceptors.toSealedList(),
            typedOutputInterceptors = typedOutputInterceptors.toSealedList(),
            endpoints = buildSealedPathSpecMap {
                for (path in httpHandlers.keys + webSocketHandlers.keys) {
                    put(
                        path, ServerPathEndpoints(
                            http = httpHandlers[path] ?: emptyMap(),
                            webSocket = webSocketHandlers[path]
                        )
                    )
                }
            },
            schedules = schedules.toSealedMap(),
            tasks = tasks.toSealedMap(),
            webSocketTopics = webSocketTopics.toSealedPathSpecMap(),
            settings = settings.toSealedList(),
            extensions = extensions.sealed(),
            exceptionHandler = exceptionHandler,
            startupTasks = startupTasks.toSealedMap(),
            preDeployTasks = preDeployTasks.toSealedMap(),
            mediaTypeDecoders = mediaTypeDecoders.toSealedMap(),
            mediaTypeEncoders = mediaTypeEncoders.toSealedMap(),
            settingOverrides = settingOverrides.toSealedMap()
        ),
        modules = (imports + modules.mapItems { it.build() }).toSealedList()
    )
}