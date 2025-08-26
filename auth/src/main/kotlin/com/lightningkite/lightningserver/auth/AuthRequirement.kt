package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.ForbiddenException
import com.lightningkite.lightningserver.definition.MutableExtensions
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.services.database.HasId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

public sealed interface AuthRequirement<out SUBJECT : HasId<*>?> {
    context(server: ServerRuntime)
    public suspend fun accepts(auth: Authentication<*>?): Boolean

    public data object NoAuth : AuthRequirement<HasId<AnyId>?> {
        context(server: ServerRuntime)
        override suspend fun accepts(auth: Authentication<*>?): Boolean = true

        @Suppress("UNCHECKED_CAST")
        public fun <SUBJECT : HasId<*>> typed(): AuthRequirement<SUBJECT?> = this as AuthRequirement<SUBJECT?>
    }

    public data object AnyAuth : AuthRequirement<HasId<AnyId>> {
        context(server: ServerRuntime)
        override suspend fun accepts(auth: Authentication<*>?): Boolean = auth != null
    }

    public data object RecentRootAuth : AuthRequirement<HasId<AnyId>> {
        context(server: ServerRuntime)
        override suspend fun accepts(auth: Authentication<*>?): Boolean =
            auth != null && auth.limitTo == null && auth.forbid == null && auth.issuedAt > now() - 10.minutes
    }

    public abstract class AuthSetting : AuthRequirement<HasId<AnyId>>, MutableExtensions.Key<AuthRequirement<HasId<*>>> {
        context(server: ServerRuntime)
        override suspend fun accepts(auth: Authentication<*>?): Boolean =
            server.server.extensions[this]?.accepts(auth) ?: false
    }

    public data object IsAdmin : AuthSetting()
    public data object IsSuperUser : AuthSetting()
    public data object IsDeveloper : AuthSetting()

    public data class AuthenticatedAs<SUBJECT : HasId<ID>, ID : Comparable<ID>>(
        val principalType: PrincipalType<SUBJECT, ID>,
        /**The required scopes. Empty set indicates no requirements and * indicates root access.*/
        val scopes: Set<String> = setOf("*"),
        val maxAge: Duration? = null,
        val requirement: (suspend context(ServerRuntime) (Authentication<SUBJECT>) -> Boolean)? = null
    ) : AuthRequirement<SUBJECT> {
        context(server: ServerRuntime)
        override suspend fun accepts(auth: Authentication<*>?): Boolean {
            if (auth == null) return false

            if (principalType != auth.untypedPrincipal) return false

            if (scopes.isNotEmpty()) {
                auth.limitTo?.scopes?.also { limits ->
                    if (limits.isEmpty() || limits.contains("*")) return@also
                    if (scopes.contains("*")) return false // we know limits doesn't have root access
                    if (!limits.containsAll(scopes)) return false
                }
                auth.forbid?.scopes?.also { forbidden ->
                    if (forbidden.isEmpty()) return@also
                    if (forbidden.contains("*")) return false
                    if (scopes.any { it in forbidden }) return false
                }
            }

            if (maxAge != null && now() - auth.issuedAt > maxAge) return false

            @Suppress("UNCHECKED_CAST") // typecheck done when principal type was checked
            return requirement?.invoke(server, auth as Authentication<SUBJECT>) ?: true
        }
    }

    @JvmInline
    public value class Options<out SUBJECT : HasId<*>?>(
        public val options: Set<AuthRequirement<SUBJECT>>
    ) : AuthRequirement<SUBJECT> {
        public constructor(vararg requirements: AuthRequirement<SUBJECT>) : this(requirements.toSet())

        context(server: ServerRuntime)
        override suspend fun accepts(auth: Authentication<*>?): Boolean = options.any { it.accepts(auth) }

        override fun toString(): String = "AuthOptions(${options.joinToString()})"

        public companion object;
    }
}