package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.DelicateLightningServerApi
import com.lightningkite.lightningserver.definition.MutableExtensions
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.services.database.HasId
import kotlin.time.Duration

/**
 * Defines requirements that must be met by an [Authentication] to access a protected resource.
 *
 * [AuthRequirement] provides a flexible, composable system for defining authentication and
 * authorization requirements for endpoints. Requirements can check for specific user types,
 * scopes, time constraints, and custom validation logic.
 *
 * ## Type Parameter
 * The [SUBJECT] type parameter indicates what type of authenticated entity is required:
 * - Non-nullable `SUBJECT` (e.g., `AuthRequirement<User>`) requires authentication
 * - Nullable `SUBJECT` (e.g., `AuthRequirement<User?>`) accepts authentication or null
 * - `Nothing?` allows any or no authentication
 *
 * ## Creating Requirements
 *
 * Use [PrincipalType.require] to create typed requirements:
 * ```kotlin
 * // Require specific user type with root scope
 * val userRequired = UserPrincipal.require()
 *
 * // Require specific user type with limited scope
 * val limitedUser = UserPrincipal.require(scope = RequiredScope("api:read"))
 *
 * // Require authentication with time limit
 * val recentAuth = UserPrincipal.require(maxAge = 10.minutes)
 *
 * // Require authentication with custom validation
 * val emailVerified = UserPrincipal.require { it.fetch().emailVerified }
 * ```
 *
 * ## Combining Requirements
 *
 * Use the [or] operator to allow multiple authentication options:
 * ```kotlin
 * // Allow user OR admin authentication
 * val userOrAdmin = UserPrincipal.require() or AdminPrincipal.require()
 *
 * // Allow user authentication OR no authentication
 * val optionalUser = UserPrincipal.require() or noAuth
 * ```
 *
 * ## Built-in Requirements
 * - [None] - No authentication required
 * - [Authenticated] - Any authentication required (type-agnostic)
 * - [AuthenticatedAs] - Specific principal type required
 * - [AuthSetting] - Requirement configured at runtime
 * - [Options] - Multiple requirement options (created via [or])
 *
 * @param SUBJECT The type of authenticated subject, or nullable to allow unauthenticated access
 * @see RequiredScope
 * @see Authentication
 * @see PrincipalType
 */
@SubclassOptInRequired(DelicateLightningServerApi::class)
public interface AuthRequirement<out SUBJECT : HasId<*>?> {
    public companion object;

    /**
     * Result of checking an [Authentication] against this requirement.
     *
     * @param SUBJECT The type of subject accepted by this result
     */
    public sealed interface Result<out SUBJECT : HasId<*>?> {

        /**
         * Authentication was rejected because it did not meet requirements.
         *
         * @property reason Human-readable explanation of why authentication was rejected
         */
        public data class Rejected(val reason: String) : Result<Nothing>

        /**
         * Authentication was accepted and meets all requirements.
         *
         * @property auth The accepted authentication, or null if no authentication was required
         */
        public data class Accepted<out SUBJECT : HasId<*>?>(val auth: Authentication<SUBJECT & Any>?) : Result<SUBJECT>
    }

    /**
     * Checks if the provided authentication meets this requirement.
     *
     * @param auth The authentication to check, or null if unauthenticated
     * @return [Result.Accepted] if the requirement is met, [Result.Rejected] otherwise
     */
    context(runtime: ServerRuntime)
    public suspend fun check(auth: Authentication<*>?): Result<SUBJECT>

    /**
     * The set of scopes required by this requirement.
     *
     * This is used for documentation and OpenAPI generation to indicate what
     * scopes are needed to access protected resources.
     */
    public val requiredScopes: Runtime<Set<RequiredScope>>

    /**
     * Creates a new requirement with additional subscope restrictions.
     *
     * This narrows the scope requirements by appending subscopes to all required scopes.
     *
     * @param subscopes The subscopes to append
     * @return A new requirement with narrower scope requirements
     */
    public fun subscope(subscopes: Iterable<Subscope>): AuthRequirement<SUBJECT>

    /**
     * No authentication requirements - accepts any authentication or unauthenticated requests.
     *
     * Use this for public endpoints that don't require authentication but may still
     * receive it (e.g., for personalization).
     *
     * Example:
     * ```kotlin
     * val publicEndpoint = path.get bind ApiHttpHandler(
     *     auth = noAuth,
     *     implementation = { /* ... */ }
     * )
     * ```
     */
    public data object None : AuthRequirement<Nothing?> {
        override val requiredScopes: Runtime.Constant<Set<RequiredScope>> get() = Runtime.Constant(emptySet())
        override fun subscope(subscopes: Iterable<Subscope>): None = this

        context(runtime: ServerRuntime)
        override suspend fun check(auth: Authentication<*>?): Result<Nothing?> = Result.Accepted(null)

        override fun toString(): String = "No Requirements"
    }

    /**
     * An authentication requirement that is configured at runtime rather than compile-time.
     *
     * [AuthSetting] allows you to define flexible, project-specific authentication requirements
     * that can be customized when building the server. This is particularly useful for:
     * - Creating reusable library endpoints with pluggable authentication
     * - Defining role-based access that varies by deployment
     * - Building generic admin interfaces with project-specific permissions
     *
     * ## Usage
     *
     * 1. Define a custom [AuthSetting]:
     * ```kotlin
     * object IsContentModerator : AuthRequirement.AuthSetting()
     * ```
     *
     * 2. Configure it in your server builder:
     * ```kotlin
     * object Server : ServerBuilder() {
     *     init {
     *         IsContentModerator.value = UserPrincipal.require(scope = RequiredScope("moderate"))
     *     }
     * }
     * ```
     *
     * 3. Use it in endpoint definitions:
     * ```kotlin
     * val deletePost = path.delete bind ApiHttpHandler(
     *     auth = IsContentModerator,
     *     implementation = { /* ... */ }
     * )
     * ```
     *
     * ## Built-in Settings
     * - [IsSuperUser] - Highest privilege level (no default)
     * - [IsAdmin] - Administrative access (defaults to [IsSuperUser])
     * - [IsDeveloper] - Developer access (defaults to [IsSuperUser])
     *
     * @property default The fallback requirement if not explicitly configured
     */
    public abstract class AuthSetting(
        public val default: AuthRequirement<HasId<*>>? = null,
    ) : AuthRequirement<HasId<*>>, MutableExtensions.Key<AuthRequirement<HasId<*>>> {
        context(server: ServerRuntime)
        public fun setting(): AuthRequirement<HasId<*>>? = server.server.extensions[this]

        override val requiredScopes: Runtime<Set<RequiredScope>> = Runtime { setting()?.requiredScopes() ?: emptySet() }
        override fun subscope(subscopes: Iterable<Subscope>): Scoped = Scoped(this, subscopes)

        context(runtime: ServerRuntime)
        override suspend fun check(auth: Authentication<*>?): Result<HasId<*>> =
            setting()?.check(auth) ?: default?.check(auth)
            ?: Result.Rejected("AuthSetting $this has no set value or default")

        public data class Scoped(
            val wraps: AuthSetting,
            val subscopes: Iterable<Subscope>,
        ) : AuthRequirement<HasId<*>> {
            override val requiredScopes: Runtime<Set<RequiredScope>> =
                Runtime { wraps.setting()?.requiredScopes()?.subscope(subscopes) ?: emptySet() }

            override fun subscope(subscopes: Iterable<Subscope>): Scoped =
                copy(subscopes = this.subscopes + subscopes)

            context(runtime: ServerRuntime)
            override suspend fun check(auth: Authentication<*>?): Result<HasId<*>> =
                wraps.setting()?.subscope(subscopes)?.check(auth) ?: wraps.default?.subscope(subscopes)?.check(auth)
                ?: Result.Rejected("AuthSetting $wraps has no set value or default")
        }
    }

    /**
     * Predefined [AuthSetting] for super user / root access.
     *
     * This represents the highest level of access and has no default fallback.
     */
    public data object IsSuperUser : AuthSetting()

    /**
     * Predefined [AuthSetting] for administrative access.
     *
     * Defaults to [IsSuperUser] if not explicitly configured.
     */
    public data object IsAdmin : AuthSetting(default = IsSuperUser)

    /**
     * Predefined [AuthSetting] for developer/debug access.
     *
     * Defaults to [IsSuperUser] if not explicitly configured.
     */
    public data object IsDeveloper : AuthSetting(default = IsSuperUser)

    /**
     * Requires any valid authentication, regardless of principal type.
     *
     * This requirement is type-agnostic and will accept any authenticated user.
     * Use this when you need to ensure someone is authenticated but don't care
     * about their specific type.
     *
     * @property scopes The scopes required (defaults to root scope)
     * @property maxAge Maximum age of the authentication token (null = no limit)
     * @property requirement Custom validation function for additional checks
     *
     * Example:
     * ```kotlin
     * // Any authenticated user with admin scope
     * val adminAuth = AuthRequirement.Authenticated(
     *     scopes = setOf(RequiredScope("admin"))
     * )
     *
     * // Any recent authentication
     * val recentAuth = AuthRequirement.Authenticated(
     *     maxAge = 10.minutes
     * )
     *
     * // Any authentication with custom check
     * val verifiedAuth = AuthRequirement.Authenticated(
     *     requirement = { auth -> auth.fetch().emailVerified }
     * )
     * ```
     */
    public data class Authenticated(
        val scopes: Set<RequiredScope> = setOf(RequiredScope.root),
        val maxAge: Duration? = null,
        val requirement: (suspend context(ServerRuntime) (Authentication<*>) -> Boolean)? = null,
    ) : AuthRequirement<HasId<*>> {
        override val requiredScopes: Runtime.Constant<Set<RequiredScope>>
            get() = Runtime.Constant(scopes)

        override fun subscope(subscopes: Iterable<Subscope>): Authenticated =
            copy(scopes = scopes.subscope(subscopes))

        context(runtime: ServerRuntime)
        override suspend fun check(auth: Authentication<*>?): Result<HasId<*>> {
            if (auth == null) return Result.Rejected("Auth required")
            if (maxAge != null && now() - auth.issuedAt > maxAge) return Result.Rejected("Auth is older than max age $maxAge")
            if (!auth.meetsRequirements(scopes)) return Result.Rejected("Auth does not have required scopes $scopes")
            if (requirement?.invoke(
                    runtime,
                    auth
                ) == false
            ) return Result.Rejected("Auth does not meet additional requirement")
            return Result.Accepted(auth)
        }

        override fun toString(): String = listOfNotNull(
            "Authenticated",
            scopes.takeIf { it.isNotEmpty() }?.let {
                if (it.size > 1) "scopes $it"
                else if (it.first() == RequiredScope.root) "root access"
                else "scope ${it.first()}"
            },
            maxAge?.let { "max age of $it" },
            requirement?.let { "an additional requirement" }
        ).joinToString(" and ").replaceFirst("and", "with")
    }

    /**
     * Requires authentication as a specific principal type.
     *
     * This is the type-safe way to require authentication. It ensures the authenticated
     * user is of a specific type and provides type-safe access to the subject.
     *
     * **Best Practice**: Use [PrincipalType.require] extension instead of constructing directly.
     *
     * @param SUBJECT The specific user/entity type required
     * @param ID The ID type for the subject
     * @property principalType The principal type that must be authenticated
     * @property scopes The scopes required (defaults to root scope)
     * @property maxAge Maximum age of the authentication token (null = no limit)
     * @property requirement Custom type-safe validation function
     *
     * Example:
     * ```kotlin
     * // Use the extension function (recommended)
     * val userRequired = UserPrincipal.require(
     *     scope = RequiredScope("api:read"),
     *     maxAge = 30.minutes,
     *     requirement = { it.fetch().emailVerified }
     * )
     * ```
     */
    public data class AuthenticatedAs<SUBJECT : HasId<ID>, ID : Comparable<ID>>(
        val principalType: PrincipalType<SUBJECT, ID>,
        val scopes: Set<RequiredScope> = setOf(RequiredScope.root),
        val maxAge: Duration? = null,
        val requirement: (suspend context(ServerRuntime) (Authentication<SUBJECT>) -> Boolean)? = null,
    ) : AuthRequirement<SUBJECT> {
        override val requiredScopes: Runtime.Constant<Set<RequiredScope>>
            get() = Runtime.Constant(scopes)

        override fun subscope(subscopes: Iterable<Subscope>): AuthenticatedAs<SUBJECT, ID> =
            copy(scopes = scopes.subscope(subscopes))

        context(runtime: ServerRuntime)
        override suspend fun check(auth: Authentication<*>?): Result<SUBJECT> {
            if (auth == null) return Result.Rejected("Auth required")
            if (principalType !== auth.untypedPrincipal) return Result.Rejected("Auth is not of type ${principalType.name}")
            if (maxAge != null && now() - auth.issuedAt > maxAge) return Result.Rejected("Auth is older than max age $maxAge")
            if (!auth.meetsRequirements(scopes)) return Result.Rejected("Auth does not have required scopes $scopes")

            @Suppress("UNCHECKED_CAST") // typecheck done when principal type was checked
            auth as Authentication<SUBJECT>

            if (requirement?.invoke(
                    runtime,
                    auth
                ) == false
            ) return Result.Rejected("Auth does not meet additional requirement")

            return Result.Accepted(auth)
        }

        override fun toString(): String = listOfNotNull(
            principalType.name,
            scopes.takeIf { it.isNotEmpty() }?.let {
                if (it.size > 1) "scopes $it"
                else if (it.first() == RequiredScope.root) "root access"
                else "scope ${it.first()}"
            },
            maxAge?.let { "max age of $it" },
            requirement?.let { "an additional requirement" }
        ).joinToString(" and ").replaceFirst("and", "with")
    }

    /**
     * Represents multiple alternative authentication requirements (logical OR).
     *
     * This allows endpoints to accept authentication through multiple different paths.
     * The first requirement that accepts the authentication will be used.
     *
     * **Best Practice**: Use the [or] infix function instead of constructing directly.
     *
     * @property options The set of alternative requirements
     *
     * Example:
     * ```kotlin
     * // Use the infix function (recommended)
     * val requirement = UserPrincipal.require() or AdminPrincipal.require() or noAuth
     *
     * // Direct construction (not recommended)
     * val requirement = AuthRequirement.Options(
     *     UserPrincipal.require(),
     *     AdminPrincipal.require(),
     *     noAuth
     * )
     * ```
     *
     * **Note**: [None] is always checked last to ensure other requirements are tried first.
     */
    public data class Options<out SUBJECT : HasId<*>?>(
        public val options: Set<AuthRequirement<SUBJECT>>,
    ) : AuthRequirement<SUBJECT> {
        public constructor(vararg requirements: AuthRequirement<SUBJECT>) : this(requirements.toSet())

        override val requiredScopes: Runtime<Set<RequiredScope>> =
            Runtime.Cached { options.flatMap { it.requiredScopes() }.toSet() }

        override fun subscope(subscopes: Iterable<Subscope>): Options<SUBJECT> =
            Options(options.map { it.subscope(subscopes) }.toSet())

        context(runtime: ServerRuntime)
        override suspend fun check(auth: Authentication<*>?): Result<SUBJECT> = options
            .sortedBy { it === None } // None should always be last resort
            .map {
                when (val r = it.check(auth)) {
                    is Result.Rejected -> r
                    is Result.Accepted -> return r
                }
            }
            .let { failures ->
                Result.Rejected(failures.joinToString { it.reason })
            }

        override fun toString(): String = options.joinToString(" or ")
    }
}

