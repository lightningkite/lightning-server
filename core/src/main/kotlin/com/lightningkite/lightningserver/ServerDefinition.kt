package com.lightningkite.lightningserver

import com.lightningkite.MediaType
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.HttpInterceptor
import com.lightningkite.lightningserver.http.HttpMethod
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.httpHandler
import com.lightningkite.lightningserver.pathing.MutablePathSpecMap
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.PathSpecMap
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketHandlerInterceptor
import com.lightningkite.lightningserver.websockets.WebSocketTopic
import kotlinx.serialization.KSerializer
import com.lightningkite.services.data.*
import com.lightningkite.services.*


public abstract class ServerDefinition(allowIndexing: Boolean = false) : ServerDefinitionBuilder<PathSpec0> {

    public abstract val internalSerialization: Serialization
    public abstract val externalSerialization: Serialization

    public val handlers: PathSpecMap<ServerPathHandlers> get() = _requestables
    private val _requestables: MutablePathSpecMap<ServerPathHandlersMutable> = MutablePathSpecMap()
    public lateinit var httpNotFound: (serverRuntime: ServerRuntime, HttpRequest<*>) -> HttpResponse
    public lateinit var httpException: (serverRuntime: ServerRuntime, Exception, HttpRequest<*>) -> HttpResponse

    public val httpInterceptors: List<HttpInterceptor> get() = _interceptors
    private var _interceptors = emptyList<HttpInterceptor>()
        set(value) {
            field = value
            // WARNING: This will melt your brain
            _fullAction =
                value.fold<HttpInterceptor, HttpInterceptor>(object : HttpInterceptor {
                    override suspend fun handle(
                        serverRuntime: ServerRuntime,
                        request: HttpRequest<*>,
                        cont: suspend (serverRuntime: ServerRuntime, HttpRequest<*>) -> HttpResponse
                    ): HttpResponse {
                        return cont(serverRuntime, request)
                    }
                }) { total, wrapper ->
                    object : HttpInterceptor {
                        override suspend fun handle(
                            serverRuntime: ServerRuntime,
                            request: HttpRequest<*>,
                            cont: suspend (serverRuntime: ServerRuntime, HttpRequest<*>) -> HttpResponse
                        ): HttpResponse {
                            return total.handle(serverRuntime, request) {
                                wrapper.handle(serverRuntime, it, cont)
                            }
                        }
                    }
                }
        }
    private var _fullAction: HttpInterceptor = HttpInterceptor.None

    public val websocketInterceptors: List<WebSocketHandlerInterceptor> get() = _wsInterceptors
    private var _wsInterceptors = emptyList<WebSocketHandlerInterceptor>()
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

    public val tasks: Map<PathSpec0, Task<*>> get() = _tasks
    private val _tasks: MutableMap<PathSpec0, Task<*>> = HashMap()
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
    override val path: PathSpec0 = PathSpec.root

    override fun <PATH : PathSpec> HttpEndpoint<PATH>.bind(other: HttpHandler<PATH>): Locationed<HttpEndpoint<PATH>, HttpHandler<PATH>> {
        return Locationed(
            this,
            other.also { _requestables.getOrPut(path) { ServerPathHandlersMutable() }.http[method] = it })
    }

    override fun <PATH : PathSpec, STORAGE> PATH.bind(other: WebSocketHandler<PATH, STORAGE>): Locationed<PATH, WebSocketHandler<PATH, STORAGE>> {
        return Locationed(this, other.also { _requestables.getOrPut(path) { ServerPathHandlersMutable() }.websocket = it })
    }

    override fun PathSpec0.bind(other: Task<*>): Locationed<PathSpec0, Task<*>> =
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
            path.path("robots.txt").get bind httpHandler {
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
    public val getter: ServerRuntime.(name: String, value: Serializable) -> Goal,
)

public interface ServerDefinitionBuilder<Path : PathSpec> {
    public val path: Path
    public infix fun <PATH : PathSpec> HttpEndpoint<PATH>.bind(other: HttpHandler<PATH>): Locationed<HttpEndpoint<PATH>, HttpHandler<PATH>>
    public infix fun <PATH : PathSpec, STORAGE> PATH.bind(other: WebSocketHandler<PATH, STORAGE>): Locationed<PATH, WebSocketHandler<PATH, STORAGE>>
    public fun <PATH : PathSpec, T> PATH.topic(type: KSerializer<T>): WebSocketTopic<PATH, T>
    public infix fun PathSpec0.bind(other: Task<*>): Locationed<PathSpec0, Task<*>>
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
    getter: ServerRuntime.(Setting) -> Result,
): Locationed<PathSpec0, ServerSetting<Setting, Result>> = path.path(name).setting(
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
): Locationed<PathSpec0, ServerSetting<Setting, Setting>> = path.path(name).setting(
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
    noinline getter: ServerRuntime.(name: String, value: Setting) -> Result,
): Locationed<PathSpec0, ServerSetting<Setting, Result>> = path.path(name).setting(
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
): Locationed<PathSpec0, ServerSetting<S, Result>> = path.path(name).setting(
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
): Locationed<PathSpec0, ServerSetting<Setting, Setting>> = path.path(name).setting(
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

