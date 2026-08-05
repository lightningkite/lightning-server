package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.runtime.ServerRuntime

/**
 * HTTP interceptor that adds baseline security headers to every response.
 *
 * This is **opt-in** — install it in your `ServerBuilder` to get safe defaults:
 * ```kotlin
 * init { install(SecurityHeadersInterceptor()) }
 * ```
 * Install it early (before CORS and other interceptors) so it runs outermost and post-processes the
 * final response, including error responses mapped inside the chain. It adds:
 * - `X-Content-Type-Options: nosniff` on all responses, preventing browsers from MIME-sniffing a
 *   response away from its declared content type.
 * - `Strict-Transport-Security` only when the request arrived over https, instructing browsers to
 *   use https for future requests. Per the HSTS spec, this header is never sent over plain http.
 *   Behind a TLS-terminating proxy this depends on the engine reporting the original scheme (e.g. from
 *   `X-Forwarded-Proto`) as `request.protocol`; if the proxy forwards as plain http, HSTS is omitted.
 *
 * Headers a handler already set are left untouched, so an endpoint can override these defaults
 * (for example, a stricter or shorter HSTS policy) without producing duplicate header values.
 *
 * @param hstsMaxAgeSeconds The `max-age` used for the Strict-Transport-Security header.
 */
public class SecurityHeadersInterceptor(
    private val hstsMaxAgeSeconds: Long = DEFAULT_HSTS_MAX_AGE_SECONDS,
) : HttpInterceptor {
    override val name: String = "SecurityHeaders"

    public companion object {
        /**
         * Default `max-age` for Strict-Transport-Security: one year, the value browsers and the HSTS
         * preload list expect. HSTS is only meaningful when it is long-lived — a short max-age gives
         * an attacker a wide window to downgrade the connection — so this deliberately defaults high.
         * Only lower it if you are not yet confident your https setup is permanent, since browsers
         * will refuse plain http to your domain for this long once they see the header.
         */
        public const val DEFAULT_HSTS_MAX_AGE_SECONDS: Long = 31_536_000
    }

    context(runtime: ServerRuntime)
    override suspend fun intercept(
        request: HttpRequest<*>,
        cont: suspend context(ServerRuntime) (HttpRequest<*>) -> HttpResponse,
    ): HttpResponse {
        val response = cont(request)
        val secure = request.protocol.equals("https", ignoreCase = true)

        // Nothing to add if the header is already present and HSTS doesn't apply.
        val needsNosniff = response.headers[HttpHeader.XContentTypeOptions] == null
        val needsHsts = secure && response.headers[HttpHeader.StrictTransportSecurity] == null
        if (!needsNosniff && !needsHsts) return response

        return response.copy(
            headers = response.headers.copy {
                if (needsNosniff) add(HttpHeader.XContentTypeOptions, "nosniff")
                if (needsHsts) add(HttpHeader.StrictTransportSecurity, "max-age=$hstsMaxAgeSeconds")
            }
        )
    }
}
