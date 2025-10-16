package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.DelicateLightningServerApi
import com.lightningkite.lightningserver.definition.MutableExtensions
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.services.database.HasId
import kotlin.time.Duration

/**
 * A requirement for [Authentication].
 *
 * [AuthRequirement] may impose any criteria for [Authentication], but it is important to note
 * that an [AuthRequirement] with a nullable [SUBJECT] type indicates that it will accept `null`.
 * Conversely, a non-nullable [SUBJECT] indicates it will not accept `null`.
 *
 * [AuthRequirement] is deeply connected with scopes. See [RequiredScope] for more details.
 *
 * To create an [AuthRequirement] for a specific [PrincipalType], use the [PrincipalType.require] method.
 * To specify that there are multiple requirement options, using the [AuthRequirement.or] infix function.
 *
 * Example:
 * ```kotlin
 * class User(override val _id: Uuid): HasId<Uuid>
 *
 * object Principal : PrincipalType<User, Uuid> {
 *    // Principal impl...
 * }
 *
 * // Require Authentication<User>() with root access
 * val r1 = Principal.require()
 *
 * // Require Authentication<User>() with no scope requirements
 * val r2 = Principal.require(scopes = emptySet())
 *
 * // Creating an AuthRequirement<User?>, might be a user, might not.
 * val maybeUser = Principal.require() or AuthRequirement.NotAuthenticated
 * ```
 *
 * ## Rules for Inheritance
 *
 * [AuthRequirement] requires certain conventions to be met to be inherited correctly.
 *
 * The [SUBJECT] type provided for the [AuthRequirement] implementation should match the `SUBJECT`
 * type for any accepted [Authentication]. If your [AuthRequirement] will accept `null`
 * its `SUBJECT` type must be nullable. If these typing rules aren't followed casting exceptions
 * can occur when calling [AuthRequirement.assert].
 * */
@SubclassOptInRequired(DelicateLightningServerApi::class)
public interface AuthRequirement<out SUBJECT : HasId<*>?> {
    public sealed interface Result<out SUBJECT : HasId<*>?> {
        public data class Failed(val reason: String) : Result<Nothing>

        public data class Success<out SUBJECT : HasId<*>?>(val auth: Authentication<SUBJECT & Any>?) : Result<SUBJECT>
    }

    context(runtime: ServerRuntime)
    public suspend fun check(auth: Authentication<*>?): Result<SUBJECT>

    public val requiredScopes: Runtime<Set<RequiredScope>>
    public fun subscope(subscopes: Iterable<Subscope>): AuthRequirement<SUBJECT>

    /**
     * No requirements, will accept any authentication or `null`
     * */
    public data object None : AuthRequirement<Nothing?> {
        override val requiredScopes: Runtime.Constant<Set<RequiredScope>> get() = Runtime.Constant(emptySet())
        override fun subscope(subscopes: Iterable<Subscope>): None = this

        context(runtime: ServerRuntime)
        override suspend fun check(auth: Authentication<*>?): Result<Nothing?> = Result.Success(null)

        override fun toString(): String = "No Requirements"
    }

    /**
     * A requirement that is set by the server, and is only determined at runtime.
     *
     * This is useful for creating generic endpoints where you want to check for specific
     * privileges that are specific to each project.
     * */
    public abstract class AuthSetting(
        public val default: AuthRequirement<HasId<*>>? = null
    ) : AuthRequirement<HasId<*>>, MutableExtensions.Key<AuthRequirement<HasId<*>>> {
        context(server: ServerRuntime)
        public fun setting(): AuthRequirement<HasId<*>>? = server.server.extensions[this]

        override val requiredScopes: Runtime<Set<RequiredScope>> = Runtime { setting()?.requiredScopes() ?: emptySet() }
        override fun subscope(subscopes: Iterable<Subscope>): Scoped = Scoped(this, subscopes)

        context(runtime: ServerRuntime)
        override suspend fun check(auth: Authentication<*>?): Result<HasId<*>> =
            setting()?.check(auth) ?: default?.check(auth) ?: Result.Failed("AuthSetting $this has no set value or default")

        public data class Scoped(
            val wraps: AuthSetting,
            val subscopes: Iterable<Subscope>
        ) : AuthRequirement<HasId<*>> {
            override val requiredScopes: Runtime<Set<RequiredScope>> =
                Runtime { wraps.setting()?.requiredScopes()?.subscope(subscopes) ?: emptySet() }

            override fun subscope(subscopes: Iterable<Subscope>): Scoped =
                copy(subscopes = this.subscopes + subscopes)

            context(runtime: ServerRuntime)
            override suspend fun check(auth: Authentication<*>?): Result<HasId<*>> =
                wraps.setting()?.subscope(subscopes)?.check(auth) ?: wraps.check(auth)
        }
    }

    public data object IsSuperUser : AuthSetting()
    public data object IsAdmin : AuthSetting(default = IsSuperUser)
    public data object IsDeveloper : AuthSetting(default = IsSuperUser)

    public data class Authenticated(
        val scopes: Set<RequiredScope> = setOf(RequiredScope.root),
        val maxAge: Duration? = null,
        val requirement: (suspend context(ServerRuntime) (Authentication<*>) -> Boolean)? = null
    ) : AuthRequirement<HasId<*>> {
        override val requiredScopes: Runtime.Constant<Set<RequiredScope>>
            get() = Runtime.Constant(scopes)

        override fun subscope(subscopes: Iterable<Subscope>): Authenticated =
            copy(scopes = scopes.subscope(subscopes))

        context(server: ServerRuntime)
        override suspend fun check(auth: Authentication<*>?): Result<HasId<*>> {
            if (auth == null) return Result.Failed("Auth required")
            if (!auth.meetsRequirements(scopes)) return Result.Failed("Auth does not have required scopes $scopes")
            if (maxAge != null && now() - auth.issuedAt > maxAge) return Result.Failed("Auth is older than max age $maxAge")
            if (requirement?.invoke(server, auth) == false) return Result.Failed("Auth does not meet additional requirement")
            return Result.Success(auth)
        }

        override fun toString(): String = listOfNotNull(
            "Authenticated",
            scopes.takeIf { it.isNotEmpty() }?.let { if (it.size > 1) "scopes $it" else "scope ${it.first()}" },
            maxAge?.let { "max age of $it" },
            requirement?.let { "an additional requirement" }
        ).joinToString(" and ").replaceFirst("and", "with")
    }

    public data class AuthenticatedAs<SUBJECT : HasId<ID>, ID : Comparable<ID>>(
        val principalType: PrincipalType<SUBJECT, ID>,
        val scopes: Set<RequiredScope> = setOf(RequiredScope.root),
        val maxAge: Duration? = null,
        val requirement: (suspend context(ServerRuntime) (Authentication<SUBJECT>) -> Boolean)? = null
    ) : AuthRequirement<SUBJECT> {
        override val requiredScopes: Runtime.Constant<Set<RequiredScope>>
            get() = Runtime.Constant(scopes)

        override fun subscope(subscopes: Iterable<Subscope>): AuthenticatedAs<SUBJECT, ID> =
            copy(scopes = scopes.subscope(subscopes))

        context(server: ServerRuntime)
        override suspend fun check(auth: Authentication<*>?): Result<SUBJECT> {
            if (auth == null) return Result.Failed("Auth required")
            if (principalType != auth.untypedPrincipal) return Result.Failed("Auth is not of type ${principalType.name}")
            if (!auth.meetsRequirements(scopes)) return Result.Failed("Auth does not have required scopes $scopes")
            if (maxAge != null && now() - auth.issuedAt > maxAge) return Result.Failed("Auth is older than max age $maxAge")

            @Suppress("UNCHECKED_CAST") // typecheck done when principal type was checked
            auth as Authentication<SUBJECT>

            if (requirement?.invoke(server, auth) == false) return Result.Failed("Auth does not meet additional requirement")

            return Result.Success(auth)
        }

        override fun toString(): String = listOfNotNull(
            principalType.name,
            scopes.takeIf { it.isNotEmpty() }?.let { if (it.size > 1) "scopes $it" else "scope ${it.first()}" },
            maxAge?.let { "max age of $it" },
            requirement?.let { "an additional requirement" }
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
        override suspend fun check(auth: Authentication<*>?): Result<SUBJECT> = options
            .map {
                when (val r = it.check(auth)) {
                    is Result.Failed -> r
                    is Result.Success<*> -> return r
                }
            }
            .let { failures ->
                Result.Failed(failures.joinToString { it.reason })
            }

        override fun toString(): String = "AuthOptions(${options.joinToString()})"
    }

    public companion object;
}