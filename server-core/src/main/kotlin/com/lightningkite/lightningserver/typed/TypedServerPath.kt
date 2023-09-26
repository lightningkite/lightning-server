package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.core.LightningServerDsl
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.http.HttpMethod
import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

sealed interface TypedServerPath {
    val path: ServerPath
    val serializers: Array<KSerializer<*>> get() = parameters.map { it.serializer }.toTypedArray()
    val parameters: Array<TypedServerPathParameter<*>>
}

data class TypedServerPathParameter<T>(val name: String, val description: String?, val serializer: KSerializer<T>)

data class TypedServerPath0(override val path: ServerPath): TypedServerPath {
    @LightningServerDsl fun path(string: String) = copy(path = path.path(string))
    override val parameters: Array<TypedServerPathParameter<*>> = arrayOf()
}
data class TypedServerPath1<A>(override val path: ServerPath, val a: TypedServerPathParameter<A>): TypedServerPath {
    @LightningServerDsl fun path(string: String) = copy(path = path.path(string))
    override val parameters: Array<TypedServerPathParameter<*>> = arrayOf(a)
}
data class TypedServerPath2<A, B>(override val path: ServerPath, val a: TypedServerPathParameter<A>, val b: TypedServerPathParameter<B>): TypedServerPath {
    @LightningServerDsl fun path(string: String) = copy(path = path.path(string))
    override val parameters: Array<TypedServerPathParameter<*>> = arrayOf(a, b)
}
data class TypedServerPath3<A, B, C>(override val path: ServerPath, val a: TypedServerPathParameter<A>, val b: TypedServerPathParameter<B>, val c: TypedServerPathParameter<C>): TypedServerPath {
    @LightningServerDsl fun path(string: String) = copy(path = path.path(string))
    override val parameters: Array<TypedServerPathParameter<*>> = arrayOf(a, b, c)
}

data class TypedHttpEndpoint<PathType: TypedServerPath>(
    val path: PathType,
    val method: HttpMethod
) {
    val endpoint get() = HttpEndpoint(path.path, method)
}

@LightningServerDsl val <PATH: TypedServerPath> PATH.get get() = TypedHttpEndpoint(this, HttpMethod.GET)
@LightningServerDsl val <PATH: TypedServerPath> PATH.post get() = TypedHttpEndpoint(this, HttpMethod.POST)
@LightningServerDsl val <PATH: TypedServerPath> PATH.put get() = TypedHttpEndpoint(this, HttpMethod.PUT)
@LightningServerDsl val <PATH: TypedServerPath> PATH.patch get() = TypedHttpEndpoint(this, HttpMethod.PATCH)
@LightningServerDsl val <PATH: TypedServerPath> PATH.delete get() = TypedHttpEndpoint(this, HttpMethod.DELETE)
@LightningServerDsl val <PATH: TypedServerPath> PATH.options get() = TypedHttpEndpoint(this, HttpMethod.OPTIONS)
@LightningServerDsl val <PATH: TypedServerPath> PATH.head get() = TypedHttpEndpoint(this, HttpMethod.HEAD)

@LightningServerDsl val ServerPath.typed get() = TypedServerPath0(this)

@LightningServerDsl
inline fun <reified A> TypedServerPath0.arg(name: String, description: String? = null): TypedServerPath1<A> = TypedServerPath1(
    path = path.copy(segments = path.segments + ServerPath.Segment.Wildcard(name)),
    a = TypedServerPathParameter(name, description, Serialization.module.serializer()),
)
@LightningServerDsl
inline fun <reified A> ServerPath.arg(name: String, description: String? = null): TypedServerPath1<A> = TypedServerPath1(
    path = copy(segments = segments + ServerPath.Segment.Wildcard(name)),
    a = TypedServerPathParameter(name, description, Serialization.module.serializer()),
)
@LightningServerDsl
fun <A> ServerPath.arg(name: String, serializer: KSerializer<A>, description: String? = null): TypedServerPath1<A> = TypedServerPath1(
    path = copy(segments = segments + ServerPath.Segment.Wildcard(name)),
    a = TypedServerPathParameter(name, description, serializer),
)
@LightningServerDsl
fun <A, B> TypedServerPath1<A>.arg(name: String, serializer: KSerializer<B>, description: String? = null): TypedServerPath2<A, B> = TypedServerPath2(
    path = path.copy(segments = path.segments + ServerPath.Segment.Wildcard(name)),
    a = a,
    b = TypedServerPathParameter(name, description, serializer),
)
@LightningServerDsl
fun <A, B, C> TypedServerPath2<A, B>.arg(name: String, serializer: KSerializer<C>, description: String? = null): TypedServerPath3<A, B, C> = TypedServerPath3(
    path = path.copy(segments = path.segments + ServerPath.Segment.Wildcard(name)),
    a = a,
    b = b,
    c = TypedServerPathParameter(name, description, serializer),
)