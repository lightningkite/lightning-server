package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.ExperimentalOauthProviderApi
import com.lightningkite.lightningserver.ForbiddenException
import com.lightningkite.lightningserver.OverrideOnly
import com.lightningkite.lightningserver.UnauthorizedException
import com.lightningkite.lightningserver.data.get
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.runtime.ServerRuntime

/**
 * Answers [inner]'s failed authentication the way OAuth clients expect of a protected resource (RFC 6750, RFC 9728),
 * which is how MCP clients discover where to log in:
 * - no token: 401 with `WWW-Authenticate: Bearer resource_metadata="…", scope="…"`;
 * - an invalid token, or one not issued for this resource: 401 with `error="invalid_token"`;
 * - a token for this resource without the needed scope: 403 with `error="insufficient_scope"`.
 *
 * The resource is the server's public URL followed by the request's path, and a token is for it only if its
 * [Authentication.audience] is exactly that (RFC 8707, RFC 9068), so the app's own tokens, even root ones, are
 * refused.  The metadata URL is `/.well-known/oauth-protected-resource` followed by the request's path, which is
 * where `OpenIdProviderEndpoints` serves it for a resource registered at that path.
 *
 * ```kotlin
 * val endpoint = path.post bind BearerChallenge(scope = "mcp", JsonRpcHandler(methods))
 * ```
 *
 * @param scope The space-separated scopes a token needs, sent so the client asks for them.
 */
@ExperimentalOauthProviderApi
public class BearerChallenge<PATH : PathSpec>(
    private val scope: String,
    private val inner: HttpHandler<PATH>,
) : HttpHandler<PATH> by inner {
    @OverrideOnly
    context(server: ServerRuntime)
    override suspend fun handle(request: HttpRequest<PATH>): HttpResponse = try {
        val auth = request[Authentication.CacheKey]
        val resource = generalSettings().publicUrl + "/" + request.path.pathSegments
        if (auth != null && auth.audience != resource) {
            challenge(request, HttpStatus.Unauthorized, "error=\"invalid_token\"")
        } else {
            // The same request, not a sub-request, so not handleWithMetrics.
            @OptIn(OverrideOnly::class)
            inner.handle(request)
        }
    } catch (e: UnauthorizedException) {
        challenge(request, HttpStatus.Unauthorized, "error=\"invalid_token\"")
    } catch (e: ForbiddenException) {
        // Requirements report a missing token as forbidden, but OAuth clients only start logging in on a 401.
        if (request[Authentication.CacheKey] == null) challenge(request, HttpStatus.Unauthorized, null)
        else challenge(request, HttpStatus.Forbidden, "error=\"insufficient_scope\"")
    }

    context(server: ServerRuntime)
    private fun challenge(request: HttpRequest<PATH>, status: HttpStatus, error: String?): HttpResponse {
        val metadata = generalSettings().publicUrl + "/.well-known/oauth-protected-resource/" + request.path.pathSegments
        val parameters = listOfNotNull(error, "resource_metadata=\"$metadata\"", "scope=\"$scope\"")
        return HttpResponse(
            status = status,
            headers = HttpHeaders { add(HttpHeader.WWWAuthenticate, "Bearer " + parameters.joinToString(", ")) },
        )
    }
}
