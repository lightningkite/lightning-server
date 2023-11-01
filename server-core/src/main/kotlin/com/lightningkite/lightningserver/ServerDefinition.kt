package com.lightningkite.lightningserver

import com.lightningkite.lightningdb.serializerOrContextual
import com.lightningkite.lightningserver.auth.RequestAuth
import com.lightningkite.lightningserver.cache.Cache
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.db.DatabaseSettings
import com.lightningkite.lightningserver.encryption.SecretBasis
import com.lightningkite.lightningserver.engine.Engine
import com.lightningkite.lightningserver.exceptions.ExceptionSettings
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.http.HttpMethod
import com.lightningkite.lightningserver.logging.LoggingSettings
import com.lightningkite.lightningserver.metrics.MetricSettings
import com.lightningkite.lightningserver.schedule.Schedule
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.GeneralServerSettings
import com.lightningkite.lightningserver.websocket.WebSocketIdentifier
import com.lightningkite.lightningserver.websocket.WebSockets
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A specification for a path on a server, such as `/path/to/item`.
 * These path specifications have typed arguments, so in `models/{id}`, the `id` is typed to a particular primitive, such as a `String`.
 * A `PathSpec` also defines what comes after the segments, as nothing, a trailing slash, or arbitrary trailing path segments.
 */
sealed class PathSpec(val segments: List<Segment>, val after: Afterwards) {
    override fun hashCode(): Int = segments.hashCode() * 31 + after.hashCode()
    override fun equals(other: Any?): Boolean =
        other is PathSpec && this.segments == other.segments && this.after == other.after

    override fun toString(): String = "/" + segments.joinToString("/") + when (after) {
        Afterwards.None -> ""
        Afterwards.TrailingSlash -> "/"
        Afterwards.TrailingSegments -> "/{...}"
    }

    /**
     * What comes after the URL segments.
     */
    enum class Afterwards {
        /**
         * Nothing - no trailing slash.
         */
        None,

        /**
         * A trailing slash after the segments.
         */
        TrailingSlash,

        /**
         * An arbitrary number of segments with or without a trailing segment afterwards.
         */
        TrailingSegments;

        companion object {
            fun fromString(string: String): Afterwards {
                if (string.endsWith("/{...}"))
                    return TrailingSegments
                else if (string.endsWith("/"))
                    return TrailingSlash
                else return None
            }
        }
    }

    /**
     * A single segment in a path.
     * For example, `thing` in the path `path/to/thing/here`.
     */
    sealed class Segment {

        /**
         * Any value can be present in this path segment, as long as it can be parsed using the given [serializer].
         */
        data class Wildcard<T>(val name: String, val serializer: KSerializer<T>) : Segment() {
            override fun toString(): String = "{$name}"
        }

        /**
         * A constant string in the path must be present.
         */
        data class Constant(val value: String) : Segment() {
            init {
                if (value.contains('/')) throw IllegalStateException("Path constant cannot contain a slash")
            }

            override fun toString(): String = value
        }

        companion object {
            /**
             * String format:
             * constant/{argument}/something/{...}
             * All arguments will be of type [String].
             */
            fun fromString(string: String): List<Segment> {
                return string.split('/')
                    .filter { it.isNotBlank() }
                    .filter { it != "{...}" }
                    .map {
                        if (it.startsWith("{"))
                            Wildcard(it.removePrefix("{").removeSuffix("}"), String.serializer())
                        else
                            Constant(it)
                    }
            }
        }
    }
}

/**
 * A [PathSpec] with no arguments - in other words, all segments are constant values.
 */
class PathSpec0(segments: List<Segment>, after: Afterwards) : PathSpec(segments, after) {
    val slash get() = PathSpec0(segments, Afterwards.TrailingSlash)
    val any get() = PathSpec0(segments, Afterwards.TrailingSegments)
    fun resolve(other: String) = PathSpec0(segments + Segment.Constant(other), Afterwards.None)
    inline fun <reified T> arg(name: String) =
        Segment.Wildcard<T>(name, serializerOrContextual<T>()).let { seg -> PathSpec1(segments + seg, after, seg) }

    fun <T> arg(other: Segment.Wildcard<T>) = other.let { seg -> PathSpec1(segments + seg, Afterwards.None, seg) }
}

/**
 * A [PathSpec] with a single typed argument.
 */
class PathSpec1<A>(segments: List<Segment>, after: Afterwards, val first: Segment.Wildcard<A>) :
    PathSpec(segments, after) {
    val slash get() = PathSpec1(segments, Afterwards.TrailingSlash, first)
    val any get() = PathSpec1(segments, Afterwards.TrailingSegments, first)
    fun resolve(other: String) = PathSpec1(segments + Segment.Constant(other), Afterwards.None, first)
    inline fun <reified T> arg(name: String) = Segment.Wildcard<T>(name, serializerOrContextual<T>())
        .let { seg -> PathSpec2(segments + seg, after, first, seg) }

    fun <T> arg(other: Segment.Wildcard<T>) =
        other.let { seg -> PathSpec2(segments + seg, Afterwards.None, first, seg) }
}

/**
 * A [PathSpec] with two typed arguments.
 */
class PathSpec2<A, B>(
    segments: List<Segment>,
    after: Afterwards,
    val first: Segment.Wildcard<A>,
    val second: Segment.Wildcard<B>
) : PathSpec(segments, after) {
    val slash get() = PathSpec2(segments, Afterwards.TrailingSlash, first, second)
    val any get() = PathSpec2(segments, Afterwards.TrailingSegments, first, second)
    fun resolve(other: String) = PathSpec2(segments + Segment.Constant(other), Afterwards.None, first, second)
    inline fun <reified T> arg(name: String) = Segment.Wildcard<T>(name, serializerOrContextual<T>())
        .let { seg -> PathSpec3(segments + seg, after, first, second, seg) }

    fun <T> arg(other: Segment.Wildcard<T>) =
        other.let { seg -> PathSpec3(segments + seg, Afterwards.None, first, second, seg) }
}

/**
 * A [PathSpec] with three typed arguments.
 */
class PathSpec3<A, B, C>(
    segments: List<Segment>,
    after: Afterwards,
    val first: Segment.Wildcard<A>,
    val second: Segment.Wildcard<B>,
    val third: Segment.Wildcard<C>
) : PathSpec(segments, after) {
    val slash get() = PathSpec3(segments, Afterwards.TrailingSlash, first, second, third)
    val any get() = PathSpec3(segments, Afterwards.TrailingSegments, first, second, third)
    fun resolve(other: String) = PathSpec3(segments + Segment.Constant(other), Afterwards.None, first, second, third)
}

class NewHttpEndpoint<Path : PathSpec>(val path: Path, val method: HttpMethod)

val <T : PathSpec> T.get get() = NewHttpEndpoint(this, HttpMethod.GET)
val <T : PathSpec> T.post get() = NewHttpEndpoint(this, HttpMethod.POST)
val <T : PathSpec> T.put get() = NewHttpEndpoint(this, HttpMethod.PUT)
val <T : PathSpec> T.patch get() = NewHttpEndpoint(this, HttpMethod.PATCH)
val <T : PathSpec> T.delete get() = NewHttpEndpoint(this, HttpMethod.DELETE)
val <T : PathSpec> T.options get() = NewHttpEndpoint(this, HttpMethod.OPTIONS)
val <T : PathSpec> T.head get() = NewHttpEndpoint(this, HttpMethod.HEAD)


class ServerDefinition<in SETTINGS> : ServerDefinitionBuilder<PathSpec0, SETTINGS> {

    /**
     * The serialization system to use for external communication to and from server.
     */
    var serialization: Serialization = Serialization

    /**
     * The serialization system to use for internal communication, such as storing data in the database.
     */
    var internalSerialization: Serialization = Serialization.Internal

    val http: MutableMap<NewHttpEndpoint<*>, HttpHandler<@UnsafeVariance SETTINGS, *>> = HashMap()
    lateinit var httpNotFound: ServerRunning<@UnsafeVariance SETTINGS>.(HttpRequest) -> HttpResponse
    lateinit var httpException: ServerRunning<@UnsafeVariance SETTINGS>.(Exception, HttpRequest) -> HttpResponse
    val interceptors: MutableCollection<Interceptor<@UnsafeVariance SETTINGS>> = ArrayList()
    val ws: MutableMap<PathSpec, WsHandler<@UnsafeVariance SETTINGS, *>> = HashMap()
    val tasks: MutableMap<PathSpec0, TaskHandler<@UnsafeVariance SETTINGS, *>> = HashMap()
    val schedules: MutableMap<PathSpec0, ScheduledTaskHandler<@UnsafeVariance SETTINGS>> = HashMap()

    /**
     * The root path of the server.
     */
    override val path: PathSpec0 = PathSpec0(listOf(), PathSpec.Afterwards.None)

    override fun <PATH : PathSpec> NewHttpEndpoint<PATH>.bind(other: HttpHandler<@UnsafeVariance SETTINGS, PATH>): Locationed<NewHttpEndpoint<*>, HttpHandler<@UnsafeVariance SETTINGS, PATH>> {
        return Locationed(this, other.also { http.put(this, it) })
    }

    override fun <PATH : PathSpec> PATH.bind(other: WsHandler<@UnsafeVariance SETTINGS, PATH>): Locationed<PathSpec, WsHandler<@UnsafeVariance SETTINGS, PATH>> {
        return Locationed(this, other.also { ws.put(this, it) })
    }

    override fun PathSpec0.bind(other: TaskHandler<@UnsafeVariance SETTINGS, *>) =
        Locationed(this, tasks.put(this, other)!!)

    override fun PathSpec0.bind(other: ScheduledTaskHandler<@UnsafeVariance SETTINGS>) =
        Locationed(this, schedules.put(this, other)!!)
}

interface ServerDefinitionBuilder<Path : PathSpec, in SETTINGS> {
    val path: Path
    infix fun <PATH : PathSpec> NewHttpEndpoint<PATH>.bind(other: HttpHandler<@UnsafeVariance SETTINGS, PATH>): Locationed<NewHttpEndpoint<*>, HttpHandler<in @UnsafeVariance SETTINGS, PATH>>
    infix fun <PATH : PathSpec> PATH.bind(other: WsHandler<@UnsafeVariance SETTINGS, PATH>): Locationed<PathSpec, WsHandler<in @UnsafeVariance SETTINGS, PATH>>
    infix fun PathSpec0.bind(other: TaskHandler<@UnsafeVariance SETTINGS, *>): Locationed<PathSpec0, TaskHandler<in @UnsafeVariance SETTINGS, *>>
    infix fun PathSpec0.bind(other: ScheduledTaskHandler<@UnsafeVariance SETTINGS>): Locationed<PathSpec0, ScheduledTaskHandler<in @UnsafeVariance SETTINGS>>
    infix fun <PATH : PathSpec, T : ServerDefinitionPart<PATH, @UnsafeVariance SETTINGS>> PATH.bind(constructor: (PATH, passOnTo: ServerDefinitionBuilder<Path, @UnsafeVariance SETTINGS>) -> T) =
        constructor(this, this@ServerDefinitionBuilder)
}

data class Locationed<Location, Item>(val location: Location, val item: Item)

open class ServerDefinitionPart<Path : PathSpec, in SETTINGS>(
    override val path: Path,
    val passOnTo: ServerDefinitionBuilder<Path, SETTINGS>
) : ServerDefinitionBuilder<Path, SETTINGS> by passOnTo

interface HttpHandler<in SETTINGS, PATH : PathSpec> {
    val timeout: Duration get() = 30.seconds
    suspend fun ServerRunning<SETTINGS>.handle(request: HttpRequestWithPath<PATH>): HttpResponse
}

interface GenericHttpHandler<in SETTINGS> {
    val timeout: Duration get() = 30.seconds
    suspend fun ServerRunning<SETTINGS>.handle(request: HttpRequest): HttpResponse
}

interface Interceptor<in SETTINGS> {
    val timeout: Duration get() = 5.seconds
    suspend fun ServerRunning<SETTINGS>.intercept(
        request: HttpRequest,
        cont: suspend (HttpRequest) -> HttpResponse
    ): HttpResponse

    suspend fun ServerRunning<SETTINGS>.intercept(
        request: WebsocketConnectEvent,
        cont: suspend (WebsocketConnectEvent) -> Unit
    ): HttpResponse
}

interface WsHandler<in SETTINGS, PATH : PathSpec> {
    val timeout: Duration get() = 30.seconds
    suspend fun ServerRunning<SETTINGS>.connect(event: WebsocketConnectEventWithPath<PATH>)
    suspend fun ServerRunning<SETTINGS>.message(event: WebsocketMessageEvent)
    suspend fun ServerRunning<SETTINGS>.disconnect(event: WebsocketDisconnectEvent)
}

interface TaskHandler<in SETTINGS, Input> {
    val serializer: KSerializer<Input>
    val timeout: Duration get() = 30.seconds
    suspend fun ServerRunning<SETTINGS>.execute(input: Input)
}

interface ScheduledTaskHandler<in SETTINGS> {
    val schedule: Schedule
    val timeout: Duration get() = 30.seconds
    suspend fun ServerRunning<SETTINGS>.execute()
}

interface ServerRunning<out SETTINGS> {
    val serialization: Serialization
    val server: ServerDefinition<*>
    val settings: SETTINGS
    val engine: Engine
}

fun <SETTINGS, PATH : PathSpec> ServerDefinitionPart<*, SETTINGS>.httpHandler(
    timeout: Duration = 30.seconds,
    handler: ServerRunning<SETTINGS>.(HttpRequestWithPath<PATH>) -> HttpResponse
) = object : HttpHandler<SETTINGS, PATH> {
    override val timeout: Duration = timeout
    override suspend fun ServerRunning<SETTINGS>.handle(request: HttpRequestWithPath<PATH>): HttpResponse {
        return handler(this, request)
    }
}

fun <SETTINGS, PATH : PathSpec> ServerDefinitionPart<*, SETTINGS>.wsHandler(
    timeout: Duration = 30.seconds,
    connect: ServerRunning<SETTINGS>.(WebsocketConnectEventWithPath<PATH>) -> Unit,
    message: ServerRunning<SETTINGS>.(WebsocketMessageEvent) -> Unit,
    disconnect: ServerRunning<SETTINGS>.(WebsocketDisconnectEvent) -> Unit,
) = object : WsHandler<SETTINGS, PATH> {
    override val timeout: Duration = timeout
    override suspend fun ServerRunning<SETTINGS>.connect(event: WebsocketConnectEventWithPath<PATH>) {
        return connect(event)
    }

    override suspend fun ServerRunning<SETTINGS>.message(event: WebsocketMessageEvent) {
        return message(event)
    }

    override suspend fun ServerRunning<SETTINGS>.disconnect(event: WebsocketDisconnectEvent) {
        return disconnect(event)
    }
}

fun <SETTINGS, INPUT> ServerDefinitionPart<*, SETTINGS>.taskHandler(
    input: KSerializer<INPUT>,
    timeout: Duration = 5.minutes,
    handler: ServerRunning<SETTINGS>.(INPUT) -> Unit
) =
    object : TaskHandler<SETTINGS, INPUT> {
        override val timeout: Duration = timeout
        override val serializer: KSerializer<INPUT> = input
        override suspend fun ServerRunning<SETTINGS>.execute(input: INPUT) {
            return handler(this, input)
        }
    }

fun <SETTINGS> ServerDefinitionPart<*, SETTINGS>.scheduleHandler(
    schedule: Schedule,
    timeout: Duration = 5.minutes,
    handler: ServerRunning<SETTINGS>.() -> Unit
) =
    object : ScheduledTaskHandler<SETTINGS> {
        override val schedule: Schedule = schedule
        override val timeout: Duration = timeout
        override suspend fun ServerRunning<SETTINGS>.execute() {
            handler()
        }
    }


//------ REQUEST DATA


interface Request {
    val segments: List<String>
    val queryParameters: List<Pair<String, String>>
    val headers: HttpHeaders
    val domain: String
    val protocol: String
    val sourceIp: String

    fun queryParameter(key: String): String? = queryParameters.find { it.first == key }?.second

    interface CacheKey<T> {
        suspend fun calculate(request: Request): T
    }

    suspend fun <T> cache(key: CacheKey<T>): T
}

interface RequestWithPath<PATH : PathSpec> : Request {
    val pathSpec: PATH
    val rawPathArguments: Array<Any?>
}

interface HttpRequest : Request {
    /** Access to the content of the request **/
    val body: HttpContent?

    /** The domain used in making the request **/
    override val domain: String

    /** The protocol used in making the request - HTTP or HTTPS **/
    override val protocol: String

    /** The originating public IP of the request, as can best be determined **/
    override val sourceIp: String
}

interface HttpRequestWithPath<PATH : PathSpec> : HttpRequest, RequestWithPath<PATH>

interface WebsocketConnectEvent : Request {
    val id: WebSocketIdentifier
    val cache: Cache
}

interface WebsocketConnectEventWithPath<PATH : PathSpec> : WebsocketConnectEvent, RequestWithPath<PATH>
interface WebsocketMessageEvent {
    val id: WebSocketIdentifier
    val cache: Cache
    val content: String
}

interface WebsocketDisconnectEvent {
    val id: WebSocketIdentifier
    val cache: Cache
}

@Suppress("UNCHECKED_CAST")
val <A> RequestWithPath<PathSpec1<A>>.first: A get() = rawPathArguments[0] as A

@Suppress("UNCHECKED_CAST")
val <A, B> RequestWithPath<PathSpec2<A, B>>.first: A get() = rawPathArguments[0] as A

@Suppress("UNCHECKED_CAST")
val <A, B> RequestWithPath<PathSpec2<A, B>>.second: B get() = rawPathArguments[1] as B

@Suppress("UNCHECKED_CAST")
val <A, B, C> RequestWithPath<PathSpec3<A, B, C>>.first: A get() = rawPathArguments[0] as A

@Suppress("UNCHECKED_CAST")
val <A, B, C> RequestWithPath<PathSpec3<A, B, C>>.second: B get() = rawPathArguments[1] as B

@Suppress("UNCHECKED_CAST")
val <A, B, C> RequestWithPath<PathSpec3<A, B, C>>.third: C get() = rawPathArguments[1] as C

//------ SAMPLES


class Grouping(path: PathSpec0, passOnTo: ServerDefinitionBuilder<PathSpec0, MySettings>) :
    ServerDefinitionPart<PathSpec0, MySettings>(path, passOnTo) {

    val x = path.resolve("asdf").get bind httpHandler {
        settings.general.projectName
        HttpResponse.plainText("Hello world!")
    }
    val y = path.arg<Int>("asdf").get bind httpHandler {
        val x: Int = it.first
        HttpResponse.plainText("Hello world!")
    }
    val ws = path.arg<Int>("asdf") bind wsHandler(
        connect = { it.auth() },
        message = {},
        disconnect = {}
    )
    val task1 = path.resolve("some-task") bind taskHandler(Unit.serializer()) {

    }
}

val authenticationThing = MyAuthStuff()
fun Request.auth(): RequestAuth<*> = authenticationThing.auth(this)

@Serializable
data class MySettings(
    val general: GeneralServerSettings,
    val logging: LoggingSettings,
    val secretBasis: SecretBasis,
    val metrics: MetricSettings,
    val exceptions: ExceptionSettings,
    val database: DatabaseSettings,
) {
}

fun ServerDefinitionBuilder<PathSpec0, MySettings>.sample() {
    val grouping = path.resolve("subset") bind ::Grouping
}

// -------

/*

GOALS

Leverage Kotlin as much as humanly possible
Allow all definitions to be accessed later for testing
No static!
Build systems on another; don't require janking
Type everything humanly possible
Prevent pre-setting loading


Settings = serialized data class
Http/WS handlers are raw, but how raw?
    Typed pathing arguments are certainly nice; should we just enforce typing everywhere?
Typed:
    In Body
    Auth
    Out Body
        May be a model, redirect, or page?
    Parameters
Outputs may be specifically customized

 */