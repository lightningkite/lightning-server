package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpMethod
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.pathing.PathPredicate
import com.lightningkite.lightningserver.runtime.ServerRuntime
import kotlinx.serialization.Serializable
import kotlin.collections.iterator

/**
 * A set of conditions that can be checked against a [Request]. This is typically
 * used to apply limitations to [com.lightningkite.lightningserver.auth.Authentication], like what methods it is limited to or forbidden
 * from requesting.
 *
 * Any provided data must be matched or satisfied for the predicate to return true.
 * Empty lists/maps means no predicate. For example, if you say `forbid scopes = emptySet()` or
 * `limitTo scopes = emptySet()`, both of these are no-ops. You cannot limit to an empty set,
 * because that would make the request useless.
 *
 * For `methods`, a [com.lightningkite.lightningserver.pathing.PathPredicate] with `null` method applies to all methods and/or no method.
 * This way you can specify "request may use `GET /a/b/c` or `POST d/e/f` or do anything with `/g/h/i`".
 * */
@Serializable
public data class RequestPredicates(
    public val methods: Map<HttpMethod?, List<PathPredicate>> = emptyMap(),
    public val headers: HttpHeaders = HttpHeaders(),
    public val queryParameters: Set<Pair<String, String>> = emptySet(),
    public val scopes: Set<String> = emptySet()
) {
    context(server: ServerRuntime)
    public fun matchesAll(request: Request<*>): Boolean {
        if (methods.isNotEmpty()) {
            val predicates = methods.getOrElse(null, ::emptyList) +
                    if (request is HttpRequest) methods.getOrElse(request.method, ::emptyList)
                    else emptyList()

            if (predicates.isNotEmpty() && predicates.none { it.matches(request.pathInContext) }) return false
        }
        if (headers.isNotEmpty()) {
            for ((header, values) in request.headers.normalizedEntries) {
                if (!headers.normalizedEntries.containsKey(header)) return false
                val predicate = headers.getMany(header).toSet()
                if (predicate.isNotEmpty() && !predicate.containsAll(values)) return false
            }
        }
        if (queryParameters.isNotEmpty() && !queryParameters.containsAll(request.queryParameters)) return false
        return true
    }

    private fun <T> Set<T>.containsAny(collection: Collection<T>) = collection.any { this.contains(it) }

    context(server: ServerRuntime)
    public fun matchesAny(request: Request<*>): Boolean {
        if (methods.isNotEmpty()) {
            val predicates = methods.getOrElse(null, ::emptyList) +
                    if (request is HttpRequest) methods.getOrElse(request.method, ::emptyList)
                    else emptyList()

            if (predicates.isNotEmpty() && predicates.any { it.matches(request.pathInContext) }) return true
        }
        if (headers.isNotEmpty()) {
            for ((header, values) in request.headers.normalizedEntries) {
                if (!headers.normalizedEntries.containsKey(header)) continue
                val predicateValues = headers.getMany(header).toSet()
                if (predicateValues.isEmpty() || predicateValues.containsAny(values)) return true
            }
        }
        if (queryParameters.isNotEmpty() && queryParameters.containsAny(request.queryParameters)) return true
        return false
    }

    public fun intersect(other: RequestPredicates): RequestPredicates = RequestPredicates(
        methods = buildMap {
            for (method in methods.keys.intersect(other.methods.keys))
                put(method, (methods[method] ?: emptyList()).intersect(other.methods[method]?.toSet() ?: emptySet()).toList())
        },
        headers = HttpHeaders {
            for (header in headers.normalizedEntries.keys.intersect(other.headers.normalizedEntries.keys))
                set(header, headers.getMany(header).intersect(other.headers.getMany(header).toSet()).toList())
        },
        queryParameters = queryParameters.intersect(other.queryParameters),
        scopes = scopes.intersect(other.scopes),
    )

    public operator fun plus(other: RequestPredicates): RequestPredicates = RequestPredicates(
        methods = buildMap {
            for (method in methods.keys + other.methods.keys) {
                put(method, (methods[method] ?: emptyList()) + (other.methods[method] ?: emptyList()))
            }
        },
        headers = headers + other.headers,
        queryParameters = queryParameters + other.queryParameters,
        scopes = scopes + other.scopes
    )

    public fun isEmpty(): Boolean =
        methods.isEmpty() && headers.isEmpty() && queryParameters.isEmpty() && scopes.isEmpty()

    public fun isNotEmpty(): Boolean =
        methods.isNotEmpty() || headers.isNotEmpty() || queryParameters.isNotEmpty() || scopes.isNotEmpty()

    public fun copy(builder: Builder.() -> Unit): RequestPredicates = Builder(this).apply(builder).build()

    public class Builder(start: RequestPredicates? = null) {
        public val methods: MutableMap<HttpMethod?, MutableList<PathPredicate>> = start?.methods?.mapValues { it.value.toMutableList() }?.toMutableMap() ?: HashMap()
        public val headers: HttpHeaders.Builder = HttpHeaders.Builder(start?.headers)
        public val queryParameters: MutableSet<Pair<String, String>> = start?.queryParameters?.toMutableSet() ?: HashSet()
        public val scopes: MutableSet<String> = start?.scopes?.toMutableSet() ?: HashSet()

        public fun build(): RequestPredicates = RequestPredicates(methods, headers.build(), queryParameters, scopes)
    }
}