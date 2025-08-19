package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.services.database.HasId
import kotlin.time.Duration

public sealed interface AuthenticationRequirement {
    context(server: ServerRuntime)
    public suspend fun accepts(auth: Authentication<*, *>?): Boolean

    public data object NoAuthentication : AuthenticationRequirement {
        context(server: ServerRuntime)
        override suspend fun accepts(auth: Authentication<*, *>?): Boolean = auth == null
    }

    public data object AnyAuthentication : AuthenticationRequirement {
        context(server: ServerRuntime)
        override suspend fun accepts(auth: Authentication<*, *>?): Boolean = auth != null
    }

    public data class AuthenticatedAs<SUBJECT : HasId<ID>, ID : Comparable<ID>>(
        val principalType: PrincipalType<SUBJECT, ID>,
        /**The required scopes. Empty set indicates no requirements and * indicates root access.*/
        val scopes: Set<String> = setOf("*"),
        val maxAge: Duration? = null,
        val requirement: (suspend context(ServerRuntime) (Authentication<SUBJECT, ID>) -> Boolean)? = null
    ) : AuthenticationRequirement {
        context(server: ServerRuntime)
        override suspend fun accepts(auth: Authentication<*, *>?): Boolean {
            if (auth == null) return false

            if (principalType != auth.principalType) return false

            if (scopes.isNotEmpty()) {
                auth.limitTo?.scopes?.let { limits ->
                    if (limits.isEmpty() || limits.contains("*")) return@let
                    if (scopes.contains("*")) return false // we know limits doesn't have root access
                    if (!limits.containsAll(scopes)) return false
                }
                auth.forbid?.scopes?.let { forbidden ->
                    if (forbidden.isEmpty()) return@let
                    if (forbidden.contains("*")) return false
                    if (scopes.any { it in forbidden }) return false
                }
            }

            if (maxAge != null && now() - auth.issuedAt > maxAge) return false

            @Suppress("UNCHECKED_CAST") // typecheck done when principal type was checked
            return requirement?.invoke(server, auth as Authentication<SUBJECT, ID>) ?: true
        }
    }
}