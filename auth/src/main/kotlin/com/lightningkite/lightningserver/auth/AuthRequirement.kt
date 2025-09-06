package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.definition.MutableExtensions
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.services.database.HasId
import kotlin.time.Duration

public interface AuthRequirement<out SUBJECT : HasId<*>?> {
    context(server: ServerRuntime)
    public suspend fun accepts(auth: Authentication<*>?): Boolean
    public val scopes: Set<RequiredScope>
    public fun subscope(subscopes: Iterable<Subscope>): AuthRequirement<SUBJECT>
    public fun subscope(subscope: Subscope): AuthRequirement<SUBJECT> = subscope(listOf(subscope))
    public fun subscope(subscopeA: Subscope, subscopeB: Subscope): AuthRequirement<SUBJECT> = subscope(listOf(subscopeA, subscopeB))

    public data object None : AuthRequirement<HasId<AnyId>?> {
        override fun subscope(subscopes: Iterable<Subscope>): AuthRequirement<HasId<AnyId>?> = this
        override val scopes: Set<RequiredScope> = setOf()

        context(server: ServerRuntime)
        override suspend fun accepts(auth: Authentication<*>?): Boolean = true

        @Suppress("UNCHECKED_CAST")
        public fun <SUBJECT : HasId<*>> typed(): AuthRequirement<SUBJECT?> = this as AuthRequirement<SUBJECT?>
        override fun toString(): String = "Not Authenticated"
    }

    // TODO: this doesn't subscope properly
    public abstract class AuthSetting(
        public val default: AuthRequirement<*>? = null
    ) : AuthRequirement<HasId<AnyId>>, MutableExtensions.Key<AuthRequirement<*>> {
        override val scopes: Set<RequiredScope> = setOf()
        override fun subscope(subscopes: Iterable<Subscope>): AuthRequirement<HasId<AnyId>> = this
        context(server: ServerRuntime)
        override suspend fun accepts(auth: Authentication<*>?): Boolean =
            server.server.extensions[this]?.accepts(auth) ?: default?.accepts(auth) ?: false
    }

    public data object IsSuperUser : AuthSetting()
    public data object IsAdmin : AuthSetting(default = IsSuperUser)
    public data object IsDeveloper : AuthSetting(default = IsSuperUser)

    public data class Authenticated(
        /**The required scopes. Empty set indicates no requirements and * indicates root access.*/
        override val scopes: Set<RequiredScope> = RequiredScopes.root,
        val maxAge: Duration? = null,
        val requirement: (suspend context(ServerRuntime) (Authentication<*>) -> Boolean)? = null
    ) : AuthRequirement<HasId<AnyId>> {
        override fun subscope(subscopes: Iterable<Subscope>): AuthRequirement<HasId<AnyId>> = copy(
            scopes = scopes.flatMapTo(HashSet()) {
                subscopes.map { sub ->
                    it.subscope(sub)
                }
            }
        )

        context(server: ServerRuntime)
        override suspend fun accepts(auth: Authentication<*>?): Boolean {
            if (auth == null) return false
            if (!auth.meetsRequirements(scopes)) return false
            if (maxAge != null && now() - auth.issuedAt > maxAge) return false
            return true
        }
        override fun toString(): String = "Any Authenticated with $scopes and max age of $maxAge"
    }

    public data class AuthenticatedAs<SUBJECT : HasId<ID>, ID : Comparable<ID>>(
        val principalType: PrincipalType<SUBJECT, ID>,
        /**The required scopes. Empty set indicates no requirements and * indicates root access.*/
        override val scopes: Set<RequiredScope> = RequiredScopes.root,
        val maxAge: Duration? = null,
        val requirement: (suspend context(ServerRuntime) (Authentication<SUBJECT>) -> Boolean)? = null
    ) : AuthRequirement<SUBJECT> {
        override fun subscope(subscopes: Iterable<Subscope>): AuthRequirement<SUBJECT> = copy(
            scopes = scopes.flatMapTo(HashSet()) {
                subscopes.map { sub ->
                    it.subscope(sub)
                }
            }
        )

        context(server: ServerRuntime)
        override suspend fun accepts(auth: Authentication<*>?): Boolean {
            if (auth == null) return false
            if (principalType != auth.untypedPrincipal) return false
            if (!auth.meetsRequirements(scopes)) return false
            if (maxAge != null && now() - auth.issuedAt > maxAge) return false
            @Suppress("UNCHECKED_CAST") // typecheck done when principal type was checked
            return requirement?.invoke(server, auth as Authentication<SUBJECT>) ?: true
        }
        override fun toString(): String = "${principalType.name} with $scopes and max age of $maxAge"
    }

    public data class Options<out SUBJECT : HasId<*>?>(
        public val options: Set<AuthRequirement<SUBJECT>>
    ) : AuthRequirement<SUBJECT> {
        override val scopes: Set<RequiredScope> = options.flatMapTo(HashSet()) { it.scopes }
        override fun subscope(subscopes: Iterable<Subscope>): AuthRequirement<SUBJECT> = Options(options.mapTo(HashSet()) { it.subscope(subscopes) })
        public constructor(vararg requirements: AuthRequirement<SUBJECT>) : this(requirements.toSet())

        context(server: ServerRuntime)
        override suspend fun accepts(auth: Authentication<*>?): Boolean = options.any { it.accepts(auth) }

        override fun toString(): String = "AuthOptions(${options.joinToString()})"
    }

    public companion object;
}

public fun <T : HasId<*>?> AuthRequirement<T>.options(): Set<AuthRequirement<T>> = if (this is AuthRequirement.Options) options else setOf(this)