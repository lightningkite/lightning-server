package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.data.get
import com.lightningkite.lightningserver.http.HttpInterceptor
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.logger
import com.lightningkite.lightningserver.runtime.ServerRuntime
import kotlinx.coroutines.CancellationException

/**
 * HTTP interceptor that writes one access-log line per request, naming the resolved principal.
 *
 * Install it in your `ServerBuilder` (typically outermost, so every request is logged):
 * ```kotlin
 * init { install(AccessLogInterceptor()) }
 * ```
 * It logs at INFO in the form `<path> accessed by <principal> (<ip>)`, where `<principal>` is the
 * request's resolved [Authentication] — rendered including masquerade as "actor masquerading as
 * target" — or `anonymous` when the request carries no credentials. This is the v4-style access log.
 *
 * Auth resolution is cached per request, so this adds no work when a handler also resolves auth, and
 * it is skipped entirely when INFO logging is off. A resolution failure (e.g. a malformed token) is
 * swallowed here so logging never breaks a request; the handler surfaces the real error itself.
 */
public class AccessLogInterceptor : HttpInterceptor {
    override val name: String = "AccessLog"

    context(runtime: ServerRuntime)
    override suspend fun intercept(
        request: HttpRequest<*>,
        cont: suspend context(ServerRuntime) (HttpRequest<*>) -> HttpResponse,
    ): HttpResponse {
        if (runtime.logger.isInfoEnabled()) {
            val accessedBy = try {
                request[Authentication.CacheKey]
            } catch (e: CancellationException) {
                throw e // never swallow cancellation — it would break structured concurrency
            } catch (_: Exception) {
                null
            }
            runtime.logger.info { "${request.path} accessed by ${accessedBy ?: "anonymous"} (${request.sourceIp})" }
        }
        return cont(request)
    }
}
