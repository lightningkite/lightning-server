package com.lightningkite.lightningserver.pathing

import com.lightningkite.lightningserver.definition.Locationed
import com.lightningkite.lightningserver.http.PathSegments
import com.lightningkite.services.data.StringArrayFormat

/**
 * A specialized map for routing requests to handlers based on [PathSpec] patterns.
 *
 * PathSpecMap is the core routing data structure in Lightning Server. It efficiently matches incoming
 * URL paths against registered PathSpec patterns and returns the associated handler/value along with
 * the parsed path arguments.
 *
 * The map implements path matching with:
 * - Constant segments (exact string match)
 * - Wildcard segments (match any value, parsed according to the wildcard's serializer)
 * - Trailing segments (/{...} catches all remaining path segments)
 *
 * Matching prioritizes more specific paths:
 * 1. Constant segments are matched before wildcards
 * 2. Paths without trailing segments are matched before those with trailing segments
 *
 * Example:
 * ```kotlin
 * val routes = buildPathSpecMap<HttpHandler<*>> {
 *     put(PathSpec.root.path("users"), listUsersHandler)
 *     put(PathSpec.root.path("users").arg<String>("id"), getUserHandler)
 * }
 *
 * // Match incoming request
 * val match = routes.match(format, PathSegments.parse("/users/john"))
 * // Returns Match with pathSpec, parsed arguments ["john"], and value=getUserHandler
 * ```
 *
 * @param V The type of value associated with each PathSpec (typically handlers)
 */
public interface PathSpecMap<out V> : Map<PathSpec, V> {
    /**
     * Matches path segments against registered PathSpecs and returns the best match.
     *
     * @param format The format to use for deserializing wildcard values
     * @param pathParts The path segments to match
     * @return A [Match] containing the matched PathSpec, parsed arguments, and associated value, or null if no match
     */
    public fun match(format: StringArrayFormat, pathParts: PathSegments): Match<V>? = match(format, pathParts) { it }

    /**
     * Matches path segments and applies a getter function to filter/transform the value.
     *
     * This is useful for selecting specific handlers based on additional criteria (e.g., HTTP method).
     *
     * @param format The format to use for deserializing wildcard values
     * @param pathParts The path segments to match
     * @param getter Function to extract/filter the desired value, returning null to skip this match
     * @return A [Match] with the transformed value, or null if no match or getter returns null
     */
    public fun <T> match(format: StringArrayFormat, pathParts: PathSegments, getter: (V) -> T?): Match<T>?

    /**
     * Matches a path string against registered PathSpecs.
     *
     * Convenience method that parses the string into [PathSegments] first.
     *
     * @param format The format to use for deserializing wildcard values
     * @param string The path string to match (e.g., "/users/john")
     * @return A [Match] or null if no match
     */
    public fun match(format: StringArrayFormat, string: String): Match<V>? = match(format, PathSegments.parse(string))

    /**
     * Matches a path string and applies a getter function.
     *
     * @param format The format to use for deserializing wildcard values
     * @param string The path string to match
     * @param getter Function to extract/filter the desired value
     * @return A [Match] with the transformed value, or null if no match or getter returns null
     */
    public fun <T> match(format: StringArrayFormat, string: String, getter: (V) -> T?): Match<T>? =
        match(format, PathSegments.parse(string), getter)

    /**
     * Returns all entries as a sequence of path-value pairs.
     *
     * @return A sequence of [Locationed] entries with PathSpec keys and values
     */
    public fun asSequence(): Sequence<Locationed<PathSpec, V>>

    /**
     * The result of successfully matching a path against a PathSpec.
     *
     * Contains the matched PathSpec pattern, the parsed wildcard arguments, and the associated value (handler).
     *
     * @param V The type of value associated with the matched PathSpec
     * @property path The resolved path with parsed arguments
     * @property value The handler/value associated with this PathSpec
     */
    public class Match<out V>(
        override val path: ResolvedPath<PathSpec>,
        public val value: V,
    ) : HasResolvedPath<PathSpec> {
        public constructor(
            pathSpec: PathSpec,
            rawPathArguments: List<Any?>,
            wildcard: PathSegments?,
            value: V,
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

public inline fun <V> buildPathSpecMap(setup: MutablePathSpecMap<V>.() -> Unit): PathSpecMap<V> =
    MutablePathSpecMap<V>().apply(setup)

public inline fun <V> buildSealedPathSpecMap(setup: MutablePathSpecMap<V>.() -> Unit): PathSpecMap<V> =
    buildPathSpecMap(setup).toSealedPathSpecMap()

public fun <V> PathSpecMap<V>.toSealedPathSpecMap(): PathSpecMap<V> = when (this) {
    is ImmutablePathSpecMap<V> -> this
    is MutablePathSpecMap<V> -> ImmutablePathSpecMap(this)
    is PathSpecRegistryImpl<V> -> ImmutablePathSpecMap(this.wraps)
    else -> MutablePathSpecMap<V>().apply { putAll(PathSpec.root, this) }.let(::ImmutablePathSpecMap)
}

public fun <V, R> PathSpecMap<V>.mapValues(transform: (V) -> R): PathSpecMap<R> {
    val destination = MutablePathSpecMap<R>()
    for ((path, value) in this) destination[path] = transform(value)
    return destination
}

/*
 * TODO: API Recommendations for PathSpecMap.kt
 *
 * 1. The match() methods don't document the priority order when multiple paths could match.
 *    For example, /users/admin vs /users/{id} - which wins? Document the precedence rules clearly.
 *
 * 2. Match failures don't provide diagnostic information about what paths were tried or why they failed.
 *    Consider adding a matchWithDiagnostics() method that returns a Result with error details.
 *
 * 3. The getter function in match() is powerful but unusual. Consider renaming to 'selector' or 'filter'
 *    to make the intent clearer, or providing convenience methods like matchByHttpMethod().
 *
 * 4. The Map interface implementation (containsKey, containsValue, etc.) is inefficient - it converts
 *    the entire sequence to a collection. Consider caching these or documenting the performance implications.
 *
 * 5. No way to check for overlapping/ambiguous routes at build time. Consider adding a validate() method
 *    that detects potential ambiguities or shadowed routes.
 *
 * 6. The buildPathSpecMap and toSealedPathSpecMap functions would benefit from KDoc explaining when to use each.
 *
 * 7. Performance: The matching algorithm has to walk through the tree. For servers with hundreds of routes,
 *    consider adding metrics or optimizations (e.g., early exit for exact constant path matches).
 */