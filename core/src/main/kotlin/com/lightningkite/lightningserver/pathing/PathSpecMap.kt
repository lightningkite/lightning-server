package com.lightningkite.lightningserver.pathing

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.definition.Locationed
import com.lightningkite.lightningserver.http.PathSegments
import com.lightningkite.services.data.StringArrayFormat
import kotlinx.serialization.SerializationException

public interface PathSpecMap<out V> : Map<PathSpec, V> {
    public fun match(format: StringArrayFormat, pathParts: PathSegments): Match<V>? = match(format, pathParts) { it }
    public fun <T> match(format: StringArrayFormat, pathParts: PathSegments, getter: (V) -> T?): Match<T>?
    public fun match(format: StringArrayFormat, string: String): Match<V>? = match(format, PathSegments.parse(string))
    public fun <T> match(format: StringArrayFormat, string: String, getter: (V) -> T?): Match<T>? =
        match(format, PathSegments.parse(string), getter)

    public fun asSequence(): Sequence<Locationed<PathSpec, V>>

    public class Match<out V>(
        override val path: ResolvedPath<PathSpec>,
        public val value: V
    ) : HasResolvedPath<PathSpec> {
        public constructor(
            pathSpec: PathSpec,
            rawPathArguments: List<Any?>,
            wildcard: PathSegments?,
            value: V
        ) : this(
            ResolvedPath(pathSpec, rawPathArguments, trailingSegments = wildcard),
            value
        )

        public val pathSpec: PathSpec get() = path.pathSpec

        override fun toString(): String = "Match(path = $path, value = $value)"
    }

    override fun containsKey(key: PathSpec): Boolean = get(key) != null
    override fun containsValue(value: @UnsafeVariance V): Boolean = asSequence().any { it.value == value }

    override val entries: Set<Locationed<PathSpec, V>> get() = asSequence().toSet()
    override val keys: Set<PathSpec> get() = asSequence().map { it.key }.toSet()
    override val values: Collection<V> get() = asSequence().map { it.value }.toList()
    override val size: Int get() = asSequence().count()
    override fun isEmpty(): Boolean = asSequence().none()
}

public fun <V> buildPathSpecMap(setup: MutablePathSpecMap<V>.() -> Unit): PathSpecMap<V> =
    ImmutablePathSpecMap(MutablePathSpecMap<V>().apply(setup))

public fun <V> PathSpecMap<V>.toSealedPathSpecMap(): PathSpecMap<V> = when (this) {
    is ImmutablePathSpecMap<V> -> this
    is MutablePathSpecMap<V> -> ImmutablePathSpecMap(this)
    is PathSpecRegistryImpl<V> -> ImmutablePathSpecMap(this.wraps)
    else -> buildPathSpecMap { putAll(PathSpec.root, this) }
}

public fun <V, R> PathSpecMap<V>.mapValues(transform: (V) -> R): PathSpecMap<R> {
    val destination = MutablePathSpecMap<R>()
    for ((path, value) in this) destination[path] = transform(value)
    return destination
}