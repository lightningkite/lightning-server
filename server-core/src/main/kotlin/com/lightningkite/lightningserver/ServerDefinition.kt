package com.lightningkite.lightningserver

import com.lightningkite.lightningdb.serializerOrContextual
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
import com.lightningkite.lightningserver.websocket.WebSockets
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

sealed class PathSpec(val segments: List<Segment>, val after: Afterwards) {
    override fun hashCode(): Int = segments.hashCode() * 31 + after.hashCode()
    override fun equals(other: Any?): Boolean =
        other is PathSpec && this.segments == other.segments && this.after == other.after

    override fun toString(): String = "/" + segments.joinToString("/") + when (after) {
        Afterwards.None -> ""
        Afterwards.TrailingSlash -> "/"
        Afterwards.ChainedWildcard -> "/{...}"
    }

    enum class Afterwards {
        None,
        TrailingSlash,
        ChainedWildcard;

        companion object {
            fun fromString(string: String): Afterwards {
                if (string.endsWith("/{...}"))
                    return ChainedWildcard
                else if (string.endsWith("/"))
                    return TrailingSlash
                else return None
            }
        }
    }

    sealed class Segment {
        data class Wildcard<T>(val name: String, val serializer: KSerializer<T>) : Segment() {
            override fun toString(): String = "{$name}"
        }

        data class Constant(val value: String) : Segment() {
            init {
                if (value.contains('/')) throw IllegalStateException("Path constant cannot contain a slash")
            }

            override fun toString(): String = value
        }

        companion object {
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

class PathSpec0(segments: List<Segment>, after: Afterwards) : PathSpec(segments, after) {
    val slash get() = PathSpec0(segments, Afterwards.TrailingSlash)
    val any get() = PathSpec0(segments, Afterwards.ChainedWildcard)
    operator fun div(other: String) = if(other.isBlank()) slash else path(other)
    operator fun <T> div(other: PathSpec.Segment.Wildcard<T>) = arg(other)
    fun path(other: String) = PathSpec0(segments + Segment.Constant(other), Afterwards.None)
    inline fun <reified T> arg(name: String) = Segment.Wildcard<T>(name, serializerOrContextual<T>()).let { seg -> PathSpec1(segments + seg, after, seg) }
    fun <T> arg(other: Segment.Wildcard<T>) = other.let { seg -> PathSpec1(segments + seg, Afterwards.None, seg) }
    operator fun minus(method: HttpMethod) = NewHttpEndpoint(this, method)
}

class PathSpec1<A>(segments: List<Segment>, after: Afterwards, val first: Segment.Wildcard<A>) :
    PathSpec(segments, after) {
    val slash get() = PathSpec1(segments, Afterwards.TrailingSlash, first)
    val any get() = PathSpec1(segments, Afterwards.ChainedWildcard, first)
    operator fun div(other: String) = if(other.isBlank()) slash else path(other)
    operator fun <T> div(other: PathSpec.Segment.Wildcard<T>) = arg(other)
    fun path(other: String) = PathSpec1(segments + Segment.Constant(other), Afterwards.None, first)
    inline fun <reified T> arg(name: String) = Segment.Wildcard<T>(name, serializerOrContextual<T>()).let { seg -> PathSpec2(segments + seg, after, first, seg) }
    fun <T> arg(other: Segment.Wildcard<T>) =
        other.let { seg -> PathSpec2(segments + seg, Afterwards.None, first, seg) }
    operator fun minus(method: HttpMethod) = NewHttpEndpoint(this, method)
}

class PathSpec2<A, B>(
    segments: List<Segment>,
    after: Afterwards,
    val first: Segment.Wildcard<A>,
    val second: Segment.Wildcard<B>
) : PathSpec(segments, after) {
    val slash get() = PathSpec2(segments, Afterwards.TrailingSlash, first, second)
    val any get() = PathSpec2(segments, Afterwards.ChainedWildcard, first, second)
    operator fun div(other: String) = if(other.isBlank()) slash else path(other)
    operator fun <T> div(other: PathSpec.Segment.Wildcard<T>) = arg(other)
    fun path(other: String) = PathSpec2(segments + Segment.Constant(other), Afterwards.None, first, second)
    inline fun <reified T> arg(name: String) = Segment.Wildcard<T>(name, serializerOrContextual<T>()).let { seg -> PathSpec3(segments + seg, after, first, second, seg) }
    fun <T> arg(other: Segment.Wildcard<T>) =
        other.let { seg -> PathSpec3(segments + seg, Afterwards.None, first, second, seg) }
    operator fun minus(method: HttpMethod) = NewHttpEndpoint(this, method)
}

class PathSpec3<A, B, C>(
    segments: List<Segment>,
    after: Afterwards,
    val first: Segment.Wildcard<A>,
    val second: Segment.Wildcard<B>,
    val third: Segment.Wildcard<C>
) : PathSpec(segments, after) {
    val slash get() = PathSpec3(segments, Afterwards.TrailingSlash, first, second, third)
    val any get() = PathSpec3(segments, Afterwards.ChainedWildcard, first, second, third)
    fun path(other: String) = PathSpec3(segments + Segment.Constant(other), Afterwards.None, first, second, third)
    operator fun minus(method: HttpMethod) = NewHttpEndpoint(this, method)
}

class NewHttpEndpoint<Path : PathSpec>(val path: Path, val method: HttpMethod)

val <T : PathSpec> T.get get() = NewHttpEndpoint(this, HttpMethod.GET)
val <T : PathSpec> T.post get() = NewHttpEndpoint(this, HttpMethod.POST)
val <T : PathSpec> T.put get() = NewHttpEndpoint(this, HttpMethod.PUT)
val <T : PathSpec> T.patch get() = NewHttpEndpoint(this, HttpMethod.PATCH)
val <T : PathSpec> T.delete get() = NewHttpEndpoint(this, HttpMethod.DELETE)
val <T : PathSpec> T.options get() = NewHttpEndpoint(this, HttpMethod.OPTIONS)
val <T : PathSpec> T.head get() = NewHttpEndpoint(this, HttpMethod.HEAD)

interface ServerSettings {
    val general: GeneralServerSettings
    val logging: LoggingSettings
    val secretBasis: SecretBasis
    val metrics: MetricSettings
    val exceptions: ExceptionSettings

    val serialization: Serialization
    val internalSerialization: Serialization
}

class ServerDefinition<in SETTINGS: ServerSettings>: ServerDefinitionBuilder<PathSpec0, SETTINGS> {
    var serialization: Serialization = Serialization
    val http: MutableMap<NewHttpEndpoint<*>, HttpHandler<@UnsafeVariance SETTINGS>> = HashMap()
    lateinit var httpNotFound: HttpHandler<@UnsafeVariance SETTINGS>
    lateinit var httpException: (Exception) -> HttpHandler<@UnsafeVariance SETTINGS>
    val httpInterceptors: MutableCollection<HttpInterceptor<@UnsafeVariance SETTINGS>> = ArrayList()
    val ws: MutableMap<PathSpec, WsHandler<@UnsafeVariance SETTINGS>> = HashMap()
    val tasks: MutableMap<PathSpec0, TaskHandler<@UnsafeVariance SETTINGS, *>> = HashMap()
    val schedules: MutableMap<PathSpec0, ScheduledTaskHandler<@UnsafeVariance SETTINGS>> = HashMap()
//    val extensions: Extensions = Extensions()

    override val path: PathSpec0 = PathSpec0(listOf(), PathSpec.Afterwards.None)

    override fun NewHttpEndpoint<*>.minus(other: HttpHandler<in @UnsafeVariance SETTINGS>) = Locationed(this, http.put(this, other)!!)
    override fun PathSpec.minus(other: WsHandler<in @UnsafeVariance SETTINGS>) = Locationed(this, ws.put(this, other)!!)
    override fun PathSpec0.minus(other: TaskHandler<in @UnsafeVariance SETTINGS, *>) = Locationed(this, tasks.put(this, other)!!)
    override fun PathSpec0.minus(other: ScheduledTaskHandler<in @UnsafeVariance SETTINGS>) = Locationed(this, schedules.put(this, other)!!)
}

interface ServerDefinitionBuilder<Path: PathSpec, in SETTINGS: ServerSettings> {
    val path: Path
    operator fun NewHttpEndpoint<*>.minus(other: HttpHandler<in @UnsafeVariance SETTINGS>): Locationed<NewHttpEndpoint<*>, HttpHandler<in @UnsafeVariance SETTINGS>>
    operator fun PathSpec.minus(other: WsHandler<in @UnsafeVariance SETTINGS>): Locationed<PathSpec, WsHandler<in @UnsafeVariance SETTINGS>>
    operator fun PathSpec0.minus(other: TaskHandler<in @UnsafeVariance SETTINGS, *>): Locationed<PathSpec0, TaskHandler<in @UnsafeVariance SETTINGS, *>>
    operator fun PathSpec0.minus(other: ScheduledTaskHandler<in @UnsafeVariance SETTINGS>): Locationed<PathSpec0, ScheduledTaskHandler<in @UnsafeVariance SETTINGS>>
    operator fun <PATH: PathSpec, T: ServerDefinitionPart<PATH, in @UnsafeVariance SETTINGS>> PATH.minus(constructor: (PATH, passOnTo: ServerDefinitionBuilder<Path, in @UnsafeVariance SETTINGS>)->T) = constructor(this, this@ServerDefinitionBuilder)
}

data class Locationed<Location, Item>(val location: Location, val item: Item)

open class ServerDefinitionPart<Path: PathSpec, in SETTINGS: ServerSettings>(
    override val path: Path,
    val passOnTo: ServerDefinitionBuilder<Path, SETTINGS>
): ServerDefinitionBuilder<Path, SETTINGS> by passOnTo

class Extensions {
    private val map = HashMap<ExtensionKey<*>, Any?>()
    operator fun <T> set(key: ExtensionKey<T>, value: T) {
        map[key] = value
    }

    operator fun <T> get(key: ExtensionKey<T>): T? {
        return map[key] as? T
    }

    interface ExtensionKey<T> {}
}

interface HttpHandler<in SETTINGS: ServerSettings> {
    val timeout: Duration get() = 30.seconds
    suspend fun ServerRunning<SETTINGS>.handle(request: HttpRequest): HttpResponse
}

interface HttpInterceptor<in SETTINGS: ServerSettings> {
    val timeout: Duration get() = 5.seconds
    suspend fun ServerRunning<SETTINGS>.intercept(
        request: HttpRequest,
        cont: suspend (HttpRequest) -> HttpResponse
    ): HttpResponse
}

interface WsHandler<in SETTINGS: ServerSettings> {
    val timeout: Duration get() = 30.seconds
    suspend fun ServerRunning<SETTINGS>.connect(event: WebSockets.ConnectEvent)
    suspend fun ServerRunning<SETTINGS>.message(event: WebSockets.MessageEvent)
    suspend fun ServerRunning<SETTINGS>.disconnect(event: WebSockets.DisconnectEvent)
}

interface TaskHandler<in SETTINGS: ServerSettings, Input> {
    val serializer: KSerializer<Input>
    val timeout: Duration get() = 30.seconds
    suspend fun ServerRunning<SETTINGS>.execute(input: Input)
}

interface ScheduledTaskHandler<in SETTINGS: ServerSettings> {
    val schedule: Schedule
    val timeout: Duration get() = 30.seconds
    suspend fun ServerRunning<SETTINGS>.execute()
}

interface ServerRunning<out SETTINGS: ServerSettings> {
    val serialization: Serialization
    val server: ServerDefinition<*>
    val settings: SETTINGS
    val engine: Engine
}


//------


object SampleTask: TaskHandler<ServerSettings, Int> {
    override val serializer: KSerializer<Int> get() = Int.serializer()
    override suspend fun ServerRunning<ServerSettings>.execute(input: Int) {

    }

}

class Grouping(path: PathSpec0, passOnTo: ServerDefinitionBuilder<PathSpec0, ServerSettings>): ServerDefinitionPart<PathSpec0, ServerSettings>(path, passOnTo) {
    val y = path / "test" - HttpMethod.GET - object: HttpHandler<ServerSettings> {
        override suspend fun ServerRunning<ServerSettings>.handle(request: HttpRequest): HttpResponse {
            return HttpResponse.plainText(settings.general.projectName)
        }
    }
}

fun ServerDefinitionBuilder<PathSpec0, ServerSettings>.sample() {
    path / "test" / "path" - SampleTask
    val x = path / "project" / "name" - HttpMethod.GET - object: HttpHandler<ServerSettings> {
        override suspend fun ServerRunning<ServerSettings>.handle(request: HttpRequest): HttpResponse {
            return HttpResponse.plainText(settings.general.projectName)
        }
    }
    val g = path / "subset" - ::Grouping
    path.get - object: HttpHandler<ServerSettings> {
        override suspend fun ServerRunning<ServerSettings>.handle(request: HttpRequest): HttpResponse {
            return HttpResponse.plainText(settings.general.projectName)
        }
    }
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