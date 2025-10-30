package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.pathing.PathSpec

/**
 * Represents an HTTP endpoint combining a path specification and HTTP method.
 *
 * This is typically created using extension properties (.get, .post, etc.) on PathSpec,
 * and then bound to a handler using the `bind` infix function.
 *
 * Example:
 * ```kotlin
 * val endpoint: HttpEndpoint<PathSpec2<String, Int>> =
 *     path.path("users").arg<String>("id").path("posts").arg<Int>("postId").get
 * ```
 *
 * @param Path The PathSpec type describing the structure of this endpoint's path
 * @property path The path specification
 * @property method The HTTP method (GET, POST, PUT, etc.)
 */
public data class HttpEndpoint<Path : PathSpec>(public val path: Path, public val method: HttpMethod) {
    override fun toString(): String = "$method $path"
}

/** Creates a GET endpoint from this path specification. */
public val <T : PathSpec> T.get: HttpEndpoint<T> get() = HttpEndpoint(this, HttpMethod.GET)

/** Creates a POST endpoint from this path specification. */
public val <T : PathSpec> T.post: HttpEndpoint<T> get() = HttpEndpoint(this, HttpMethod.POST)

/** Creates a PUT endpoint from this path specification. */
public val <T : PathSpec> T.put: HttpEndpoint<T> get() = HttpEndpoint(this, HttpMethod.PUT)

/** Creates a PATCH endpoint from this path specification. */
public val <T : PathSpec> T.patch: HttpEndpoint<T> get() = HttpEndpoint(this, HttpMethod.PATCH)

/** Creates a DELETE endpoint from this path specification. */
public val <T : PathSpec> T.delete: HttpEndpoint<T> get() = HttpEndpoint(this, HttpMethod.DELETE)

/** Creates an OPTIONS endpoint from this path specification. */
public val <T : PathSpec> T.options: HttpEndpoint<T> get() = HttpEndpoint(this, HttpMethod.OPTIONS)

/** Creates a HEAD endpoint from this path specification. */
public val <T : PathSpec> T.head: HttpEndpoint<T> get() = HttpEndpoint(this, HttpMethod.HEAD)
