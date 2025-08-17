package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.KeyedSerializableCache
import com.lightningkite.lightningserver.Request
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpMethod
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.pathing.PathPredicate
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.database.HasId
import kotlin.time.Instant

public data class Authentication<SUBJECT : HasId<ID>, ID : Comparable<ID>>(
    public val type: PrincipalType<SUBJECT, ID>,
    public val fromMasquerade: Authentication<*, *>?,
    public val limitTo: RequestPredicates? = null,
    public val forbid: RequestPredicates? = null,
    public val issuedAt: Instant,
) {
    init {
        if (limitTo != null && forbid != null) limitTo.intersect(forbid).let { intersection ->
            if (intersection.isNotEmpty()) throw IllegalArgumentException("limitTo and forbid cannot have any common predicates, as this leads to a contradiction. Intersection: $intersection")
        }
    }

    object CacheKey : KeyedSerializableCache.Key<Authentication<*, *>?> {
        override val id: String = "authentication"
    }
}


// Any provided data must be matched or satisfied for the predicate to return true.
// null means no predicate.
public data class RequestPredicates(
    public val methods: Set<HttpMethod>? = null,
    public val paths: Set<PathPredicate>? = null,
    public val headers: HttpHeaders? = null,
    public val queryParameters: Set<Pair<String, String>>? = null,
) {
    context(server: ServerRuntime)
    public fun matchesAll(request: Request<*>): Boolean {
        if (methods != null && request is HttpRequest && request.method !in methods) return false
        if (paths != null && paths.none { it.satisfiedBy(request.pathInContext) }) return false
        if (headers != null) {
            for ((header, values) in request.headers.normalizedEntries) {
                if (!headers.normalizedEntries.containsKey(header)) return false
                if (!headers.getMany(header).toSet().containsAll(values)) return false
            }
        }
        if (queryParameters != null && !queryParameters.containsAll(request.queryParameters)) return false
        return true
    }

    private fun <T> Set<T>.containsAny(collection: Collection<T>) = collection.any { this.contains(it) }

    context(server: ServerRuntime)
    public fun matchesAny(request: Request<*>): Boolean {
        if (methods != null && request is HttpRequest && request.method in methods) return true
        if (paths != null && paths.any { it.satisfiedBy(request.pathInContext) }) return true
        if (headers != null) {
            for ((header, values) in request.headers.normalizedEntries) {
                if (!headers.normalizedEntries.containsKey(header)) continue
                val predicateValues = headers.getMany(header).toSet()
                if (predicateValues.isEmpty() || predicateValues.containsAny(values)) return true
            }
        }
        if (queryParameters != null && queryParameters.containsAny(request.queryParameters)) return true
        return false
    }

    private fun <T> Set<T>?.intersect2(other: Set<T>?) =
        if (this == null || other == null) null
        else this.intersect(other)

    public fun intersect(other: RequestPredicates): RequestPredicates = RequestPredicates(
        methods = methods.intersect2(other.methods)?.takeUnless { it.isEmpty() },
        paths = paths.intersect2(other.paths)?.takeUnless { it.isEmpty() },
        headers =
            if (headers == null || other.headers == null) null
            else HttpHeaders {
                for ((header, values) in headers.normalizedEntries) {
                    if (!other.headers.normalizedEntries.containsKey(header)) continue
                    val otherValues = other.headers.getMany(header)
                    set(
                        header,
                        values
                            .toMutableSet()
                            .apply { retainAll(otherValues.toSet()) }
                            .toList()
                    )
                }
            }.takeUnless { it.isEmpty() },
        queryParameters = queryParameters.intersect2(other.queryParameters)
    )

    private inline fun <T : Any> op(first: T?, second: T?, operation: (T, T) -> T): T? =
        when {
            first == null -> second
            second == null -> first
            else -> operation(first, second)
        }

    public operator fun plus(other: RequestPredicates): RequestPredicates = RequestPredicates(
        methods = op(methods, other.methods) { a, b -> a + b },
        paths = op(paths, other.paths) { a, b -> a + b },
        headers = op(headers, other.headers) { a, b -> a + b },
        queryParameters = op(queryParameters, other.queryParameters) { a, b -> a + b }
    )

    public fun isEmpty(): Boolean =
        methods == null && paths == null && headers == null && queryParameters == null

    public fun isNotEmpty(): Boolean = !isEmpty()
}