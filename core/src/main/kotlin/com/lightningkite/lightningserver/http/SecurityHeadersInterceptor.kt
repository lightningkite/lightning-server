package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.runtime.ServerRuntime

/**
 * HTTP interceptor that adds baseline security headers to every response.
 *
 * This interceptor is installed automatically for every server, so applications get safe defaults
 * without any configuration. It adds:
 * - `X-Content-Type-Options: nosniff` on all responses, preventing browsers from MIME-sniffing a
 *   response away from its declared content type.
 * - `Strict-Transport-Security` only when the request arrived over https, instructing browsers to
 *   use https for future requests. Per the HSTS spec, this header is never sent over plain http.
 *
 * Headers a handler already set are left untouched, so an endpoint can override these defaults
 * (for example, a stricter or longer HSTS policy) without producing duplicate header values.
 *
 * @param hstsMaxAgeSeconds The `max-age` used for the Strict-Transport-Security header.
 */
public class SecurityHeadersInterceptor(
    private val hstsMaxAgeSeconds: Long = DEFAULT_HSTS_MAX_AGE_SECONDS,
) : HttpInterceptor {
    override val name: String = "SecurityHeaders"

    public companion object {
        /**
         * Default `max-age` for Strict-Transport-Security, in seconds (1 hour), matching the value
         * required by the framework's `expectations.md`. Production deployments that are confident
         * in their https setup may prefer a longer value (e.g. 31536000, one year).
         */
        public const val DEFAULT_HSTS_MAX_AGE_SECONDS: Long = 3600
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
