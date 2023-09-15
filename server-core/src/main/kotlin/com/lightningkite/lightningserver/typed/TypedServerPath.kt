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
    val serializers: Array<KSerializer<*>>
}
data class TypedServerPath0(override val path: ServerPath): TypedServerPath {
    @LightningServerDsl fun path(string: String) = copy(path = path.path(string))
    override val serializers: Array<KSerializer<*>> = arrayOf()
}
data class TypedServerPath1<A>(override val path: ServerPath, val a: KSerializer<A>): TypedServerPath {
    @LightningServerDsl fun path(string: String) = copy(path = path.path(string))
    override val serializers: Array<KSerializer<*>> = arrayOf(a)
}
data class TypedServerPath2<A, B>(override val path: ServerPath, val a: KSerializer<A>, val b: KSerializer<B>): TypedServerPath {
    @LightningServerDsl fun path(string: String) = copy(path = path.path(string))
    override val serializers: Array<KSerializer<*>> = arrayOf(a, b)
}
data class TypedServerPath3<A, B, C>(override val path: ServerPath, val a: KSerializer<A>, val b: KSerializer<B>, val c: KSerializer<C>): TypedServerPath {
    @LightningServerDsl fun path(string: String) = copy(path = path.path(string))
    override val serializers: Array<KSerializer<*>> = arrayOf(a, b, c)
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
inline fun <reified A> TypedServerPath0.arg(key: String): TypedServerPath1<A> = TypedServerPath1(
    path = path.copy(segments = path.segments + ServerPath.Segment.Wildcard(key)),
    a = Serialization.module.serializer(),
)
@LightningServerDsl
inline fun <reified A> ServerPath.arg(key: String): TypedServerPath1<A> = TypedServerPath1(
    path = copy(segments = segments + ServerPath.Segment.Wildcard(key)),
    a = Serialization.module.serializer(),
)
@LightningServerDsl
inline fun <A, reified B> TypedServerPath1<A>.arg(key: String): TypedServerPath2<A, B> = TypedServerPath2(
    path = path.copy(segments = path.segments + ServerPath.Segment.Wildcard(key)),
    a = a,
    b = Serialization.module.serializer(),
)
@LightningServerDsl
inline fun <A, B, reified C> TypedServerPath2<A, B>.arg(key: String): TypedServerPath3<A, B, C> = TypedServerPath3(
    path = path.copy(segments = path.segments + ServerPath.Segment.Wildcard(key)),
    a = a,
    b = b,
    c = Serialization.module.serializer(),
)
@LightningServerDsl
fun <A> ServerPath.arg(key: String, serializer: KSerializer<A>): TypedServerPath1<A> = TypedServerPath1(
    path = copy(segments = segments + ServerPath.Segment.Wildcard(key)),
    a = serializer,
)
@LightningServerDsl
fun <A, B> TypedServerPath1<A>.arg(key: String, serializer: KSerializer<B>): TypedServerPath2<A, B> = TypedServerPath2(
    path = path.copy(segments = path.segments + ServerPath.Segment.Wildcard(key)),
    a = a,
    b = serializer,
)
@LightningServerDsl
fun <A, B, C> TypedServerPath2<A, B>.arg(key: String, serializer: KSerializer<C>): TypedServerPath3<A, B, C> = TypedServerPath3(
    path = path.copy(segments = path.segments + ServerPath.Segment.Wildcard(key)),
    a = a,
    b = b,
    c = serializer,
)