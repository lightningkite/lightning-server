package com.lightningkite.lightningserver

import com.lightningkite.MediaType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.SerializersModule
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import com.lightningkite.serviceabstractions.data.*
import com.lightningkite.serviceabstractions.*


public abstract class ServerDefinition(allowIndexing: Boolean = false) : ServerDefinitionBuilder<PathSpec0> {

    public abstract val internalSerialization: Serialization
    public abstract val externalSerialization: Serialization

    public val handlers: PathSpecMap<ServerPathHandlers> get() = _requestables
    private val _requestables: MutablePathSpecMap<ServerPathHandlersMutable> = MutablePathSpecMap()
    public lateinit var httpNotFound: (serverRunning: ServerRunning, HttpRequest<*>) -> HttpResponse
    public lateinit var httpException: (serverRunning: ServerRunning, Exception, HttpRequest<*>) -> HttpResponse

    public val httpInterceptors: List<HttpInterceptor> get() = _interceptors
    private var _interceptors = listOf<HttpInterceptor>()
        set(value) {
            field = value
            // WARNING: This will melt your brain
            _fullAction =
                value.fold<HttpInterceptor, HttpInterceptor>(object : HttpInterceptor {
                    override suspend fun handle(
                        serverRunning: ServerRunning,
                        request: HttpRequest<*>,
                        cont: suspend (serverRunning: ServerRunning, HttpRequest<*>) -> HttpResponse
                    ): HttpResponse {
                        return cont(serverRunning, request)
                    }
                }) { total, wrapper ->
                    object : HttpInterceptor {
                        override suspend fun handle(
                            serverRunning: ServerRunning,
                            request: HttpRequest<*>,
                            cont: suspend (serverRunning: ServerRunning, HttpRequest<*>) -> HttpResponse
                        ): HttpResponse {
                            return total.handle(serverRunning, request) {
                                wrapper.handle(serverRunning, it, cont)
                            }
                        }
                    }
                }
        }
    private var _fullAction: HttpInterceptor = HttpInterceptor.None

    public val websocketInterceptors: List<WebSocketHandlerInterceptor> get() = _wsInterceptors
    private var _wsInterceptors = listOf<WebSocketHandlerInterceptor>()
        set(value) {
            field = value
            // WARNING: This will melt your brain
            _wsFullInterceptor =
                value.fold<WebSocketHandlerInterceptor, WebSocketHandlerInterceptor>(
                    WebSocketHandlerInterceptor.None
                ) { total, wrapper ->
                    return@fold object : WebSocketHandlerInterceptor {
                        override operator fun <PATH : PathSpec, T> invoke(handler: WebSocketHandler<PATH, T>): WebSocketHandler<PATH, T> =
                            wrapper(total(handler))
                    }
                }
        }
    private var _wsFullInterceptor: WebSocketHandlerInterceptor = WebSocketHandlerInterceptor.None

    public val tasks: Map<PathSpec0, TaskHandler<*>> get() = _tasks
    private val _tasks: MutableMap<PathSpec0, TaskHandler<*>> = HashMap()
    public val schedules: Map<PathSpec0, ScheduledTaskHandler> get() = _schedules
    private val _schedules: MutableMap<PathSpec0, ScheduledTaskHandler> = HashMap()
    public val webSocketTopics: PathSpecMap<WebSocketTopic<*, *>> get() = _webSocketTopics
    private val _webSocketTopics: MutablePathSpecMap<WebSocketTopic<*, *>> = MutablePathSpecMap()
    public val settings: Map<PathSpec0, ServerSetting<*, *>> get() = _settings
    private val _settings: MutableMap<PathSpec0, ServerSetting<*, *>> = HashMap()

    private val _extensions: MutableMap<ExtensionKey<*>, Any> = HashMap()
    public operator fun <T : Any> get(key: ExtensionKey<T>): T? = _extensions[key] as? T
    public operator fun <T : Any> set(key: ExtensionKey<T>, value: T) {
        _extensions[key] = value
    }

    public interface ExtensionKey<T : Any>

    /**
     * The root path of the server.
     */
    override val path: PathSpec0 = PathSpec0(listOf(), PathSpec.Afterwards.None)

    override fun <PATH : PathSpec> HttpEndpoint<PATH>.bind(other: HttpHandler<PATH>): Locationed<HttpEndpoint<PATH>, HttpHandler<PATH>> {
        return Locationed(
            this,
            other.also { _requestables.getOrPut(path) { ServerPathHandlersMutable() }.http[method] = it })
    }

    override fun <PATH : PathSpec, STORAGE> PATH.bind(other: WebSocketHandler<PATH, STORAGE>): Locationed<PATH, WebSocketHandler<PATH, STORAGE>> {
        return Locationed(this, other.also { _requestables.getOrPut(path) { ServerPathHandlersMutable() }.websocket })
    }

    override fun PathSpec0.bind(other: TaskHandler<*>): Locationed<PathSpec0, TaskHandler<*>> =
        Locationed(this, other.also { _tasks.put(this, it) })

    override fun PathSpec0.bind(other: ScheduledTaskHandler): Locationed<PathSpec0, ScheduledTaskHandler> =
        Locationed(this, other.also { _schedules.put(this, it) })

    override fun <PATH : PathSpec, T> PATH.topic(type: KSerializer<T>): WebSocketTopic<PATH, T> =
        WebSocketTopic(this, type).also {
            _webSocketTopics.put(
                this,
                it
            )
        }

    override fun <Serializable, Goal> PathSpec0.setting(setting: ServerSetting<Serializable, Goal>): Locationed<PathSpec0, ServerSetting<Serializable, Goal>> {
        return Locationed(this, setting.also { _settings.put(this, it) })
    }

    public fun register(interceptor: HttpInterceptor) {
        _interceptors += interceptor
    }

    public fun register(interceptor: WebSocketHandlerInterceptor) {
        _wsInterceptors += interceptor
    }

    public val generalServerSettings: Locationed<PathSpec0, ServerSetting<GeneralServerSettings, GeneralServerSettings>>
        = setting("general", GeneralServerSettings())
    init {
        // global requirements
        if (allowIndexing) {
            path.resolve("robots.txt").get bind httpHandler {
                HttpResponse(
                    TypedData.text(
                        """
                            User-agent: *
                            Disallow: /
                        """.trimIndent(),
                        MediaType.Text.Plain
                    )
                )
            }
        }
    }
}

public interface ServerPathHandlers {
    public val http: Map<HttpMethod, HttpHandler<*>>
    public val websocket: WebSocketHandler<*, *>?
}

public class ServerPathHandlersMutable : ServerPathHandlers {
    override var http: MutableMap<HttpMethod, HttpHandler<*>> = HashMap()
    override var websocket: WebSocketHandler<*, *>? = null
}

public class ServerSetting<Serializable, Goal>(
    public val serializer: KSerializer<Serializable>,
    public val default: Serializable,
    public val optional: Boolean = false,
    public val description: String? = null,
    public val getter: ServerRunning.(name: String, value: Serializable) -> Goal,
)

public interface ServerDefinitionBuilder<Path : PathSpec> {
    public val path: Path
    public infix fun <PATH : PathSpec> HttpEndpoint<PATH>.bind(other: HttpHandler<PATH>): Locationed<HttpEndpoint<PATH>, HttpHandler<PATH>>
    public infix fun <PATH : PathSpec, STORAGE> PATH.bind(other: WebSocketHandler<PATH, STORAGE>): Locationed<PATH, WebSocketHandler<PATH, STORAGE>>
    public fun <PATH : PathSpec, T> PATH.topic(type: KSerializer<T>): WebSocketTopic<PATH, T>
    public infix fun PathSpec0.bind(other: TaskHandler<*>): Locationed<PathSpec0, TaskHandler<*>>
    public infix fun PathSpec0.bind(other: ScheduledTaskHandler): Locationed<PathSpec0, ScheduledTaskHandler>
    public infix fun <PATH : PathSpec, T : ServerDefinitionPart<PATH>> PATH.bind(constructor: (PATH, passOnTo: ServerDefinitionBuilder<Path>) -> T): T =
        constructor(this, this@ServerDefinitionBuilder)

    public fun <Serializable, Goal> PathSpec0.setting(setting: ServerSetting<Serializable, Goal>): Locationed<PathSpec0, ServerSetting<Serializable, Goal>>
}

public fun <Setting, Result> ServerDefinitionBuilder<PathSpec0>.setting(
    name: String,
    default: Setting,
    serializer: KSerializer<Setting>,
    optional: Boolean = false,
    description: String? = null,
    getter: ServerRunning.(Setting) -> Result,
): Locationed<PathSpec0, ServerSetting<Setting, Result>> = path.resolve(name).setting(
    ServerSetting(
        default = default,
        serializer = serializer,
        optional = optional,
        description = description,
        getter = { _, value -> getter(this, value) },
    )
)

public fun <Setting> ServerDefinitionBuilder<PathSpec0>.setting(
    name: String,
    default: Setting,
    serializer: KSerializer<Setting>,
    optional: Boolean = false,
    description: String? = null,
): Locationed<PathSpec0, ServerSetting<Setting, Setting>> = path.resolve(name).setting(
    ServerSetting(
        default = default,
        serializer = serializer,
        optional = optional,
        description = description,
        getter = { _, value -> value },
    )
)

public inline fun <reified Setting, Result> ServerDefinitionBuilder<PathSpec0>.setting(
    name: String,
    default: Setting,
    optional: Boolean = false,
    description: String? = null,
    noinline getter: ServerRunning.(name: String, value: Setting) -> Result,
): Locationed<PathSpec0, ServerSetting<Setting, Result>> = path.resolve(name).setting(
    ServerSetting(
        default = default,
        serializer = serializerOrContextual(),
        optional = optional,
        description = description,
        getter = getter,
    )
)

public inline fun <reified S : Setting<Result>, Result> ServerDefinitionBuilder<PathSpec0>.setting(
    name: String,
    default: S,
    optional: Boolean = false,
    description: String? = null,
): Locationed<PathSpec0, ServerSetting<S, Result>> = path.resolve(name).setting(
    ServerSetting(
        default = default,
        serializer = serializerOrContextual(),
        optional = optional,
        description = description,
        getter = { name, it -> it(settingContext(name)) },
    )
)

public inline fun <reified Setting> ServerDefinitionBuilder<PathSpec0>.setting(
    name: String,
    default: Setting,
    optional: Boolean = false,
    description: String? = null,
): Locationed<PathSpec0, ServerSetting<Setting, Setting>> = path.resolve(name).setting(
    ServerSetting(
        default = default,
        serializer = serializerOrContextual(),
        optional = optional,
        description = description,
        getter = { _, it -> it },
    )
)

public data class Locationed<out Location, out Item>(public val location: Location, public val item: Item)

public open class ServerDefinitionPart<Path : PathSpec>(
    override val path: Path,
    private val passOnTo: ServerDefinitionBuilder<Path>
) : ServerDefinitionBuilder<Path> by passOnTo

public interface HttpHandler<PATH : PathSpec> {
    public val timeout: Duration get() = 30.seconds
    public suspend fun handle(serverRunning: ServerRunning, request: HttpRequest<PATH>): HttpResponse
}

public interface TaskHandler<Input> {
    public val serializer: KSerializer<Input>
    public val timeout: Duration get() = 30.seconds
    public suspend fun execute(serverRunning: ServerRunning, input: Input)
}

public interface ScheduledTaskHandler {
    public val schedule: Schedule
    public val timeout: Duration get() = 30.seconds
    public suspend fun execute(serverRunning: ServerRunning)
}

public interface ServerRunning {
    public val server: ServerDefinition
    public operator fun <SERIALIZABLE, GOAL> Locationed<PathSpec0, ServerSetting<SERIALIZABLE, GOAL>>.invoke(): GOAL
    public suspend fun <PATH : PathSpec, T> sendWebSocketSubscriptionMessage(event: WebSocketSubscriptionMessage<PATH, T>)
}

context(serverRunning: ServerRunning) public operator fun <SERIALIZABLE, GOAL> Locationed<PathSpec0, ServerSetting<SERIALIZABLE, GOAL>>.invoke(): GOAL
    = with(serverRunning) { invoke() }

context(serverRunning: ServerRunning) public suspend fun <T> WebSocketTopic<PathSpec0, T>.send(value: T): Unit =
    serverRunning.sendWebSocketSubscriptionMessage(
        WebSocketSubscriptionMessage(this, listOf(), value)
    )

context(serverRunning: ServerRunning) public suspend fun <A, T> WebSocketTopic<PathSpec1<A>, T>.send(
    path1: A,
    value: T
): Unit = serverRunning.sendWebSocketSubscriptionMessage(
    WebSocketSubscriptionMessage(this, listOf(path1), value)
)

context(serverRunning: ServerRunning) public suspend fun <A, B, T> WebSocketTopic<PathSpec2<A, B>, T>.send(
    path1: A,
    path2: B,
    value: T
): Unit = serverRunning.sendWebSocketSubscriptionMessage(
    WebSocketSubscriptionMessage(this, listOf(path1, path2), value)
)

context(serverRunning: ServerRunning) public suspend fun <A, B, C, T> WebSocketTopic<PathSpec3<A, B, C>, T>.send(
    path1: A,
    path2: B,
    path3: C,
    value: T
): Unit = serverRunning.sendWebSocketSubscriptionMessage(
    WebSocketSubscriptionMessage(this, listOf(path1, path2, path3), value)
)

public fun <PATH : PathSpec> ServerDefinitionBuilder<*>.httpHandler(
    timeout: Duration = 30.seconds,
    handler: ServerRunning.(HttpRequest<PATH>) -> HttpResponse
): HttpHandler<PATH> = object : HttpHandler<PATH> {
    override val timeout: Duration = timeout
    override suspend fun handle(serverRunning: ServerRunning, request: HttpRequest<PATH>): HttpResponse {
        return handler(serverRunning, request)
    }
}

public fun <INPUT> ServerDefinitionBuilder<*>.taskHandler(
    input: KSerializer<INPUT>,
    timeout: Duration = 5.minutes,
    handler: ServerRunning.(INPUT) -> Unit
): TaskHandler<INPUT> =
    object : TaskHandler<INPUT> {
        override val timeout: Duration = timeout
        override val serializer: KSerializer<INPUT> = input
        override suspend fun execute(serverRunning: ServerRunning, input: INPUT) {
            return handler(serverRunning, input)
        }
    }

public fun ServerDefinitionBuilder<*>.scheduleHandler(
    schedule: Schedule,
    timeout: Duration = 5.minutes,
    handler: ServerRunning.() -> Unit
): ScheduledTaskHandler =
    object : ScheduledTaskHandler {
        override val schedule: Schedule = schedule
        override val timeout: Duration = timeout
        override suspend fun execute(serverRunning: ServerRunning) {
            handler(serverRunning)
        }
    }
