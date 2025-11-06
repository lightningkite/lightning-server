package com.lightningkite.lightningserver

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Represents an HTTP method in a type-safe, memory-efficient manner.
 *
 * This value class provides zero-overhead abstraction over HTTP method strings while
 * preventing typos and providing compile-time safety. The [JvmInline] annotation ensures
 * no runtime overhead on the JVM - the string is used directly without wrapper allocation.
 *
 * Standard HTTP methods are available as companion object constants. Custom methods can be
 * created by constructing new instances, though this should be rare in typical usage.
 *
 * Example usage:
 * ```kotlin
 * val method: HttpMethod = HttpMethod.GET
 * when (method) {
 *     HttpMethod.GET -> // handle GET
 *     HttpMethod.POST -> // handle POST
 *     else -> // handle other methods
 * }
 * ```
 *
 * Gotcha: [WEBSOCKET] is not a standard HTTP method but is included for WebSocket upgrade handling.
 * It represents the conceptual "method" for WebSocket connections rather than a literal HTTP verb.
 */
@JvmInline
@Serializable
public value class HttpMethod(private val asString: String) {
    public companion object {
        /** HTTP GET method - retrieves a resource without side effects */
        public val GET: HttpMethod = HttpMethod("GET")

        /** HTTP POST method - creates a new resource or triggers an action */
        public val POST: HttpMethod = HttpMethod("POST")

        /** HTTP PUT method - replaces an entire resource */
        public val PUT: HttpMethod = HttpMethod("PUT")

        /** HTTP PATCH method - partially updates a resource */
        public val PATCH: HttpMethod = HttpMethod("PATCH")

        /** HTTP DELETE method - removes a resource */
        public val DELETE: HttpMethod = HttpMethod("DELETE")

        /** HTTP OPTIONS method - describes communication options for the target resource */
        public val OPTIONS: HttpMethod = HttpMethod("OPTIONS")

        /** HTTP HEAD method - identical to GET but returns only headers, no body */
        public val HEAD: HttpMethod = HttpMethod("HEAD")

        /** Pseudo-method representing WebSocket connections (not a standard HTTP method) */
        public val WEBSOCKET: HttpMethod = HttpMethod("WEBSOCKET")
    }

    override fun toString(): String = asString
}

/*
 * TODO: API Recommendations
 *
 * 1. Consider adding an equality check method that's case-insensitive for robustness:
 *    fun equalsIgnoreCase(other: HttpMethod): Boolean
 *    HTTP methods should be case-sensitive per RFC 7231, but defensive parsing could be valuable.
 *
 * 2. Add a validation method to check if a method is standard/safe:
 *    val isStandard: Boolean (checks if it's one of the companion object constants)
 *    val isSafe: Boolean (true for GET, HEAD, OPTIONS - methods that don't modify state)
 *    val isIdempotent: Boolean (true for GET, PUT, DELETE, HEAD, OPTIONS)
 *
 * 3. Consider adding a factory method that validates and normalizes strings:
 *    fun fromString(method: String): HttpMethod that uppercases the input
 *    This would prevent accidental lowercase method names.
 *
 * 4. The private constructor means users can't create custom methods. If this is intentional,
 *    document it clearly. If custom methods should be supported, make the constructor public
 *    and possibly add validation.
 */