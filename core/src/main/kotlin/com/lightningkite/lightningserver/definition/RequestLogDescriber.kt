package com.lightningkite.lightningserver.definition

import com.lightningkite.lightningserver.definition.builder.ListRegistry
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.runtime.ServerRuntime

/**
 * Produces the actor portion of a request's access-log line — normally the resolved authentication
 * principal. Returns `null` when this describer can't identify the request (e.g. no credentials),
 * letting the next registered describer try; when all return `null` the log falls back to the IP.
 *
 * The `auth` layer lives above `core` and so registers a describer that resolves the request's
 * `Authentication` (whose `toString` renders masquerade as "X masquerading as Y"). This is how a
 * server records the full principal on every request — the same information v4 logged — without
 * `core` depending on the auth module.
 *
 * Register describers on a [ServerBuilder] via [requestLogDescribers].
 */
public typealias RequestLogDescriber = suspend context(ServerRuntime) (HttpRequest<*>) -> String?

private object RequestLogDescribers : ListRegistryExtension<RequestLogDescriber>

/** Register a [RequestLogDescriber] that names who made each request in its access-log line. */
public val ServerBuilder.requestLogDescribers: ListRegistry<RequestLogDescriber> by RequestLogDescribers

/** The [RequestLogDescriber]s the access log consults, in registration order. */
public val ServerDefinition.requestLogDescribers: List<RequestLogDescriber> by RequestLogDescribers
