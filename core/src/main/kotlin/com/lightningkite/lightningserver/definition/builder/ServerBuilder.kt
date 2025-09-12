package com.lightningkite.lightningserver.definition.builder

import com.lightningkite.lightningserver.LightningServerDsl
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.MutablePathSpecMap
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.serialization.MediaTypeCoder
import com.lightningkite.lightningserver.serialization.MediaTypeDecoder
import com.lightningkite.lightningserver.serialization.MediaTypeDecoderRegistry
import com.lightningkite.lightningserver.serialization.MediaTypeEncoder
import com.lightningkite.lightningserver.serialization.MediaTypeEncoderRegistry
import com.lightningkite.lightningserver.serialization.serializerOrContextual
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketTopic
import com.lightningkite.lightningserver.websockets.WebSocketsBuilder
import com.lightningkite.services.Setting
import com.lightningkite.services.SettingContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

/**
 * [ServerBuilder] provides a fluent, type-safe API for defining your server configuration.
 *
 * [ServerBuilder] is essentially a collection of registries for your endpoints, tasks, schedules, etc. You build a server by registering
 * resources and their locations. Once the definition is complete the [build] method is used to construct an immutable
 * [ServerDefinition] for runtime use.
 *
 * Registration is done through the provided builder dsl. Endpoints and tasks are registered using [include] and settings are registered
 * using [setting].
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
    public open val internalSerialization: Runtime<SerializersModule> get() = Runtime.Constant(EmptySerializersModule())
    public open val externalSerialization: Runtime<SerializersModule> get() = Runtime.Constant(EmptySerializersModule())

    protected val path: PathSpec0 get() = PathSpec.root // just for convenience

    private val settings: ListRegistry<ServerSetting<*, *>> = ListRegistry()

    private val http: HttpBuilder = HttpBuilder()
    private val websockets: WebSocketsBuilder = WebSocketsBuilder()
    private var exceptionHandler: ExceptionHttpHandler = DefaultExceptionHttpHandler

    private val startupTasks: MapRegistry<PathSpec0, StartupTask> = MapRegistry()
    private val schedules: MapRegistry<PathSpec0, ScheduledTask> = MapRegistry()
    private val tasks: MapRegistry<PathSpec0, Task<*>> = MapRegistry()

    private val mediaTypeDecoders: MediaTypeDecoderRegistry = MediaTypeDecoderRegistry()
    private val mediaTypeEncoders: MediaTypeEncoderRegistry = MediaTypeEncoderRegistry()

    public override val extensions: MutableExtensions = MutableExtensions()

    private val imports: ListRegistry<Locationed<PathSpec0, ServerDefinition>> = ListRegistry()
    private val modules: ListRegistry<Locationed<PathSpec0, ServerBuilder>> = ListRegistry()

    @LightningServerDsl
    public infix fun <PATH : PathSpec, HANDLER : HttpHandler<PATH>> HttpEndpoint<PATH>.bind(handler: HANDLER): HANDLER {
        http.register(this, handler)
        return handler
    }

    @LightningServerDsl
    public infix fun <PATH : PathSpec, STORAGE, T : WebSocketHandler<PATH, STORAGE>> PATH.bind(handler: T): T {
        websockets.register(this, handler)
        return handler
    }

    @LightningServerDsl
    public infix fun <T> PathSpec0.bind(task: Task<T>): Task<T> {
        tasks.register(this, task)
        return task
    }

    @LightningServerDsl
    public infix fun PathSpec0.bind(startupTask: StartupTask): StartupTask {
        startupTasks.register(this, startupTask)
        return startupTask
    }

    @LightningServerDsl
    public infix fun PathSpec0.bind(schedule: ScheduledTask): ScheduledTask {
        schedules.register(this, schedule)
        return schedule
    }

    @LightningServerDsl
    public fun <PATH : PathSpec, T> PATH.topic(type: KSerializer<T>): WebSocketTopic<PATH, T> {
        val topic = WebSocketTopic<PATH, T>(type)
        websockets.topics.register(this, topic)
        return topic
    }

    @LightningServerDsl
    public fun <Setting, Result> setting(setting: ServerSetting<Setting, Result>): ServerSetting<Setting, Result> {
        settings.register(setting)
        return setting
    }

    @LightningServerDsl
    public fun <Setting, Result> setting(
        name: String,
        default: Setting,
        serializer: KSerializer<Setting>,
        optional: Boolean = false,
        getter: SettingContext.(Setting) -> Result,
    ): ServerSetting<Setting, Result> =
        setting(
            ServerSetting(
                name,
                default,
                serializer,
                optional,
            ) { value -> getter(this, value) }
        )

    @LightningServerDsl
    public fun <SETTING : Setting<RESULT>, RESULT> setting(
        name: String,
        default: SETTING,
        serializer: KSerializer<SETTING>,
        optional: Boolean = false,
    ): ServerSetting<SETTING, RESULT> =
        setting(
            ServerSetting(
                name,
                default,
                serializer,
                optional,
            )
        )

    @LightningServerDsl
    public inline fun <reified SETTING : Setting<RESULT>, RESULT> setting(
        name: String,
        default: SETTING,
        optional: Boolean = false,
    ): ServerSetting<SETTING, RESULT> =
        setting(
            ServerSetting(
                name,
                default,
                serializerOrContextual<SETTING>(),
                optional,
            )
        )

    @LightningServerDsl
    public fun <Result> setting(
        name: String,
        default: Result,
        serializer: KSerializer<Result>,
        optional: Boolean = false,
    ): ServerSetting.Direct<Result> {
        val setting = ServerSetting(
            name,
            default,
            serializer,
            optional,
        )
        settings.register(setting)
        return setting
    }

    @LightningServerDsl
    public inline fun <reified Setting, Result> setting(
        name: String,
        default: Setting,
        optional: Boolean = false,
        crossinline getter: SettingContext.(Setting) -> Result,
    ): ServerSetting<Setting, Result> =
        setting(
            ServerSetting(
                name,
                default,
                serializerOrContextual<Setting>(),
                optional,
            ) { value -> getter(this, value) }
        )

    @LightningServerDsl
    public inline fun <reified Result> setting(
        name: String,
        default: Result,
        optional: Boolean = false,
    ): ServerSetting.Direct<Result> =
        setting(
            name,
            default,
            serializerOrContextual<Result>(),
            optional,
        )

    @LightningServerDsl
    public infix fun <T : ServerBuilder> PathSpec0.include(module: T): T {
        modules.register(Locationed(this, module))
        return module
    }

    @LightningServerDsl
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
     * 1. Creating a [shallowBuild] of this builder's direct configuration
     * 2. Recursively builds all imported modules
     * 3. Flattens the modular structure into a single [ServerDefinition]
     *
     * @return A complete [ServerDefinition] with all modules flattened and ready for deployment
     */
    public fun build(): ServerDefinition = ServerDefinition(
        thisLayer = shallowBuild(),
        modules = imports + modules.mapItems { it.build() }
    )

    private fun shallowBuild(): ServerDefinition.Module = ServerDefinition.Module(
        internalSerializersModule = internalSerialization,
        externalSerializersModule = externalSerialization,
        httpInterceptors = http.interceptors.interceptors,
        websocketInterceptors = websockets.interceptors.interceptors,
        endpoints = MutablePathSpecMap<ServerPathEndpoints>().apply {
            for (path in http.handlers.keys + websockets.handlers.keys) {
                put(path, ServerPathEndpoints(
                    http = http.handlers[path] ?: emptyMap(),
                    websocket = websockets.handlers[path]
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
}