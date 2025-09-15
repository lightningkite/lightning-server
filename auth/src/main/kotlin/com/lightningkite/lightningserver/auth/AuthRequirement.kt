package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.definition.MutableExtensions
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.services.database.HasId
import kotlin.time.Duration

public interface AuthRequirement<out SUBJECT : HasId<*>?> {
    context(server: ServerRuntime)
    public suspend fun accepts(auth: Authentication<*>?): Boolean

    public val requiredScopes: Runtime<Set<RequiredScope>>
    public fun subscope(subscopes: Iterable<Subscope>): AuthRequirement<SUBJECT>

    public data object None : AuthRequirement<HasId<AnyId>?> {
        override val requiredScopes: Runtime.Constant<Set<RequiredScope>> = Runtime.Constant(emptySet())
        override fun subscope(subscopes: Iterable<Subscope>): None = this

        context(server: ServerRuntime)
        override suspend fun accepts(auth: Authentication<*>?): Boolean = true

        @Suppress("UNCHECKED_CAST")
        public fun <SUBJECT : HasId<*>> typed(): AuthRequirement<SUBJECT?> = this as AuthRequirement<SUBJECT?>
        override fun toString(): String = "Not Authenticated"
    }

    public abstract class AuthSetting(
        public val default: AuthRequirement<*>? = null
    ) : AuthRequirement<HasId<AnyId>>, MutableExtensions.Key<AuthRequirement<*>> {
        context(server: ServerRuntime)
        public fun setting(): AuthRequirement<*>? = server.server.extensions[this]

        override val requiredScopes: Runtime<Set<RequiredScope>> = Runtime { setting()?.requiredScopes() ?: emptySet() }
        override fun subscope(subscopes: Iterable<Subscope>): Scoped = Scoped(this, subscopes)

        context(server: ServerRuntime)
        override suspend fun accepts(auth: Authentication<*>?): Boolean =
            setting()?.accepts(auth) ?: default?.accepts(auth) ?: false

        public data class Scoped(
            val wraps: AuthSetting,
            val subscopes: Iterable<Subscope>
        ) : AuthRequirement<HasId<AnyId>> {
            override val requiredScopes: Runtime<Set<RequiredScope>> =
                Runtime { wraps.setting()?.requiredScopes()?.subscope(subscopes) ?: emptySet() }

            override fun subscope(subscopes: Iterable<Subscope>): Scoped =
                copy(subscopes = this.subscopes + subscopes)

            context(server: ServerRuntime)
            override suspend fun accepts(auth: Authentication<*>?): Boolean =
                wraps.setting()?.subscope(subscopes)?.accepts(auth) ?: wraps.accepts(auth)
        }
    }

    public data object IsSuperUser : AuthSetting()
    public data object IsAdmin : AuthSetting(default = IsSuperUser)
    public data object IsDeveloper : AuthSetting(default = IsSuperUser)

    public data class Authenticated(
        /**The required scopes. Empty set indicates no requirements and * indicates root access.*/
        val scopes: Set<RequiredScope> = RequiredScopes.root,
        val maxAge: Duration? = null,
        val requirement: (suspend context(ServerRuntime) (Authentication<*>) -> Boolean)? = null
    ) : AuthRequirement<HasId<AnyId>> {
        override val requiredScopes: Runtime.Constant<Set<RequiredScope>>
            get() = Runtime.Constant(scopes)

        override fun subscope(subscopes: Iterable<Subscope>): Authenticated =
            copy(scopes = scopes.subscope(subscopes))

        context(server: ServerRuntime)
        override suspend fun accepts(auth: Authentication<*>?): Boolean {
            if (auth == null) return false
            if (!auth.meetsRequirements(scopes)) return false
            if (maxAge != null && now() - auth.issuedAt > maxAge) return false
            return true
        }

        override fun toString(): String = listOfNotNull(
            "Authenticated",
            scopes.takeIf { it.isNotEmpty() }?.let { if (it.size > 1) "scopes $it" else "scope ${it.first()}" },
            maxAge?.let { "max age of $it" }
        ).joinToString(" and ").replaceFirst("and", "with")
    }

    public data class AuthenticatedAs<SUBJECT : HasId<ID>, ID : Comparable<ID>>(
        val principalType: PrincipalType<SUBJECT, ID>,
        /**The required scopes. Empty set indicates no requirements and * indicates root access.*/
        val scopes: Set<RequiredScope> = RequiredScopes.root,
        val maxAge: Duration? = null,
        val requirement: (suspend context(ServerRuntime) (Authentication<SUBJECT>) -> Boolean)? = null
    ) : AuthRequirement<SUBJECT> {
        override val requiredScopes: Runtime.Constant<Set<RequiredScope>>
            get() = Runtime.Constant(scopes)

        override fun subscope(subscopes: Iterable<Subscope>): AuthenticatedAs<SUBJECT, ID> =
            copy(scopes = scopes.subscope(subscopes))

        context(server: ServerRuntime)
        override suspend fun accepts(auth: Authentication<*>?): Boolean {
            if (auth == null) return false
            if (principalType != auth.untypedPrincipal) return false
            if (!auth.meetsRequirements(scopes)) return false
            if (maxAge != null && now() - auth.issuedAt > maxAge) return false
            @Suppress("UNCHECKED_CAST") // typecheck done when principal type was checked
            return requirement?.invoke(server, auth as Authentication<SUBJECT>) ?: true
        }

        override fun toString(): String = listOfNotNull(
            principalType.name,
            scopes.takeIf { it.isNotEmpty() }?.let { if (it.size > 1) "scopes $it" else "scope ${it.first()}" },
            maxAge?.let { "max age of $it" }
        ).joinToString(" and ").replaceFirst("and", "with")
    }

    public data class Options<out SUBJECT : HasId<*>?>(
        public val options: Set<AuthRequirement<SUBJECT>>
    ) : AuthRequirement<SUBJECT> {
        public constructor(vararg requirements: AuthRequirement<SUBJECT>) : this(requirements.toSet())

        override val requiredScopes: Runtime<Set<RequiredScope>> =
            Runtime.Cached { options.flatMap { it.requiredScopes() }.toSet() }

        override fun subscope(subscopes: Iterable<Subscope>): Options<SUBJECT> =
            Options(options.map { it.subscope(subscopes) }.toSet())

        context(server: ServerRuntime)
        override suspend fun accepts(auth: Authentication<*>?): Boolean = options.any { it.accepts(auth) }

        override fun toString(): String = "AuthOptions(${options.joinToString()})"
    }

    public companion object;
}

public fun <T : HasId<*>?> AuthRequirement<T>.options(): Set<AuthRequirement<T>> =
    if (this is AuthRequirement.Options) options else setOf(this)