package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.Request
import com.lightningkite.lightningserver.definition.Locationed
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpMethod
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.pathing.PathPredicate
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.toPredicate
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.database.HasId
import kotlinx.serialization.Serializable
import kotlin.collections.intersect
import kotlin.time.Instant

public data class Authentication<SUBJECT : HasId<ID>, ID : Comparable<ID>>(
    public val principalName: String,
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


    public class Builder {
        public val limitTo: RequestPredicates.Builder = RequestPredicates.Builder()
        public val forbid: RequestPredicates.Builder = RequestPredicates.Builder()

        public fun limitToEndpoints(vararg endpoints: HttpEndpoint<PathSpec>) { for (endpoint in endpoints) limitTo.methods.getOrPut(endpoint.method, ::ArrayList).add(endpoint.path.toPredicate()) }
        public fun limitToEndpoints(vararg endpoints: Locationed<HttpEndpoint<PathSpec>, *>) { for (endpoint in endpoints) limitTo.methods.getOrPut(endpoint.location.method, ::ArrayList).add(endpoint.location.path.toPredicate()) }
        public fun limitToMethods(methods: Iterable<HttpMethod>) { for (method in methods) limitTo.methods.getOrPut(method, ::ArrayList).clear() }
        public fun limitToHeaders(headers: HttpHeaders) { limitTo.headers.set(headers) }
        public fun limitToQueryParameters(vararg queryParameters: Pair<String, String>) { limitTo.queryParameters.addAll(queryParameters) }
        public fun limitToScopes(vararg scopes: String) { limitTo.scopes.addAll(scopes) }
        public fun forbidEndpoints(endpoint: HttpEndpoint<PathSpec>) { forbid.methods.getOrPut(endpoint.method, ::ArrayList).add(endpoint.path.toPredicate()) }
        public fun forbidEndpoints(endpoint: Locationed<HttpEndpoint<PathSpec>, *>) { forbid.methods.getOrPut(endpoint.location.method, ::ArrayList).add(endpoint.location.path.toPredicate()) }
        public fun forbidMethods(methods: Iterable<HttpMethod>) { for (method in methods) forbid.methods.getOrPut(method, ::ArrayList).clear() }
        public fun forbidHeaders(headers: HttpHeaders) { forbid.headers.set(headers) }
        public fun forbidQueryParameters(vararg queryParameters: Pair<String, String>) { forbid.queryParameters.addAll(queryParameters) }
        public fun forbidScopes(vararg scopes: String) { forbid.scopes.addAll(scopes) }
    }
}

// Any provided data must be matched or satisfied for the predicate to return true.
// null means no predicate.
@Serializable
public data class RequestPredicates(
    public val methods: Map<HttpMethod?, List<PathPredicate>>? = null,
    public val headers: HttpHeaders? = null,
    public val queryParameters: Set<Pair<String, String>>? = null,
    public val scopes: Set<String>?
) {
    context(server: ServerRuntime)
    public fun matchesAll(request: Request<*>): Boolean {
        if (methods != null) methods[(request as? HttpRequest)?.method]?.let { predicates ->
            if (predicates.isNotEmpty() && predicates.none { it.matches(request.path.pathInContext) }) return false
        } ?: return false // HttpMethod not specified in predicate

        if (headers != null) {
            for ((header, values) in request.headers.normalizedEntries) {
                if (!headers.normalizedEntries.containsKey(header)) return false
                val predicate = headers.getMany(header).toSet()
                if (predicate.isNotEmpty() && !predicate.containsAll(values)) return false
            }
        }
        if (queryParameters != null && !queryParameters.containsAll(request.queryParameters)) return false
        return true
    }

    private fun <T> Set<T>.containsAny(collection: Collection<T>) = collection.any { this.contains(it) }

    context(server: ServerRuntime)
    public fun matchesAny(request: Request<*>): Boolean {
        if (methods != null) methods[(request as? HttpRequest)?.method]?.let { paths ->
            if (paths.isEmpty()) return true
            if (paths.any { it.matches(request.path.pathInContext) }) return true
        } // HttpMethod not specified in predicate
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

    public fun intersect(other: RequestPredicates): RequestPredicates = RequestPredicates(
        methods =
            if (methods == null || other.methods == null) null
            else buildMap {
                for (method in methods.keys.intersect(other.methods.keys))
                    put(method, (methods[method] ?: emptyList()).intersect(other.methods[method]?.toSet() ?: emptySet()).toList())
            },
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
        queryParameters =
            if (queryParameters == null || other.queryParameters == null) null
            else queryParameters.intersect(other.queryParameters).takeUnless { it.isEmpty() },
        scopes =
            if (scopes == null || other.scopes == null) null
            else scopes.intersect(other.scopes).takeUnless { it.isEmpty() },
    )

    private inline fun <T : Any> op(first: T?, second: T?, operation: (T, T) -> T): T? =
        when {
            first == null -> second
            second == null -> first
            else -> operation(first, second)
        }

    public operator fun plus(other: RequestPredicates): RequestPredicates = RequestPredicates(
        methods = op(methods, other.methods) { a, b ->
            buildMap {
                for (method in a.keys + b.keys) {
                    put(method, (a[method] ?: emptyList()) + (b[method] ?: emptyList()))
                }
            }
        },
        headers = op(headers, other.headers) { a, b -> a + b },
        queryParameters = op(queryParameters, other.queryParameters) { a, b -> a + b },
        scopes = op(scopes, other.scopes) { a, b -> a + b }
    )

    public fun isEmpty(): Boolean =
        methods == null && headers == null && queryParameters == null && scopes == null

    public fun isNotEmpty(): Boolean = !isEmpty()

    public class Builder {
        public val methods: MutableMap<HttpMethod?, MutableList<PathPredicate>> = HashMap()
        public val headers: HttpHeaders.Builder = HttpHeaders.Builder()
        public val queryParameters: MutableSet<Pair<String, String>> = HashSet()
        public val scopes: MutableSet<String> = HashSet()

        public fun build(): RequestPredicates = RequestPredicates(
            methods.toMap().takeUnless { it.isEmpty() },
            headers.build().takeUnless { it.isEmpty() },
            queryParameters.toSet().takeUnless { it.isEmpty() },
            scopes.toSet().takeUnless { it.isEmpty() },
        )
    }
}