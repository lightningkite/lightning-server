package com.lightningkite.lightningserver.demo.endpoints

import com.lightningkite.lightningserver.ForbiddenException
import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.auth.fetch
import com.lightningkite.lightningserver.auth.id
import com.lightningkite.lightningserver.auth.or
import com.lightningkite.lightningserver.auth.require
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.demo.Server
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.auth
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * AuthExamplesEndpoints - Demonstrates reading the logged-in user inside a handler.
 *
 * Every other endpoint family in this demo passes `auth = noAuth`, which leaves no example of
 * the thing most real endpoints actually need: knowing who's calling. These three show it:
 * - [whoAmI]: required auth, reads the caller via `auth.id` / `auth.fetch()`
 * - [greet]: optional auth, different behavior for anonymous vs. logged-in callers
 * - [adminOnly]: a role check (`User.isSuperUser`) that rejects non-admins with 403
 *
 * Field-level masking (`ModelPermissions.readMask`) is already wired up on [Server.userInfo] -
 * GET /user/rest/{id} for a non-self, non-admin caller returns `hashedPassword` masked to "".
 * See AuthExamplesEndpointsTest for a test that proves it rather than duplicating it here.
 */
object AuthExamplesEndpoints : ServerBuilder() {

    @Serializable
    data class WhoAmIResponse(val id: Uuid, val email: String, val isSuperUser: Boolean)

    /**
     * GET /auth-examples/whoami
     *
     * Requires a session. `auth.id` and `auth.fetch()` are the two ways to read the caller:
     * the former is free (it's just the token's subject id), the latter fetches the full row.
     */
    val whoAmI = path.path("auth-examples").path("whoami").get bind ApiHttpHandler(
        summary = "Who Am I",
        description = "Reads the authenticated caller's identity from the session.",
        auth = Server.UserAuth.require(),
        implementation = { _: Unit ->
            val user = auth.fetch()
            WhoAmIResponse(id = auth.id, email = user.email, isSuperUser = user.isSuperUser)
        }
    )

    /**
     * GET /auth-examples/greet
     *
     * Optional auth (`require() or AuthRequirement.None`): the same endpoint serves anonymous
     * and logged-in callers differently, without needing two separate routes.
     */
    val greet = path.path("auth-examples").path("greet").get bind ApiHttpHandler(
        summary = "Greet",
        description = "Greets anonymous callers generically and logged-in callers by email.",
        auth = Server.UserAuth.require() or AuthRequirement.None,
        implementation = { _: Unit ->
            authOrNull?.fetch()?.let { "Welcome back, ${it.email}!" } ?: "Hello, anonymous visitor!"
        }
    )

    /**
     * GET /auth-examples/admin-only
     *
     * Requires a session, then checks `isSuperUser` in the handler body and throws
     * [ForbiddenException] for anyone who fails it - a hard 403, distinct from the row-level
     * filtering that ModelPermissions does for REST endpoints.
     */
    val adminOnly = path.path("auth-examples").path("admin-only").get bind ApiHttpHandler(
        summary = "Admin Only",
        description = "Requires the caller's isSuperUser flag; returns 403 for everyone else.",
        auth = Server.UserAuth.require(),
        implementation = { _: Unit ->
            val user = auth.fetch()
            if (!user.isSuperUser) throw ForbiddenException("This endpoint requires super-user access.")
            "Welcome, admin ${user.email}!"
        }
    )
}
