package com.lightningkite.lightningserver.auth

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline


/**
 * Represents a scope required to access a protected resource or endpoint.
 *
 * Scopes use hierarchical naming with colon (`:`) as the delimiter for subscopes.
 * The scope hierarchy follows a parent-child containment model where broader scopes
 * grant access to more specific subscopes.
 *
 * ## Access Rules
 * - [Authentication] with scope `foo` grants access to `foo`, `foo:bar`, `foo:bar:baz`, etc.
 * - [Authentication] with subscope `foo:bar` does **not** grant access to parent scope `foo`
 * - The special scope `*` (root) grants universal access to all scopes
 *
 * ## Example Usage
 * ```kotlin
 * val adminScope = RequiredScope("admin")
 * val userManagementScope = RequiredScope("admin:users")
 * val rootScope = RequiredScope.root // Grants access to everything
 *
 * // Check if a scope contains another
 * adminScope.contains(userManagementScope) // true
 * userManagementScope.contains(adminScope) // false
 *
 * // Create subscopes
 * val postsScope = adminScope.subscope(Subscope("posts")) // "admin:posts"
 * ```
 *
 * @property asString The string representation of the scope (e.g., "admin:users:read")
 * @see GrantedScope
 * @see Subscope
 */
@JvmInline
@Serializable
public value class RequiredScope(public val asString: String) {
    public companion object {
        /**
         * The root scope (`*`) that grants universal access to all scopes.
         */
        public val root: RequiredScope = RequiredScope("*")
    }

    internal val subscopes: List<String> get() = asString.split(':')

    /**
     * Creates a new [RequiredScope] by appending a subscope to this scope.
     *
     * If this scope is [root], the subscope replaces it entirely rather than appending.
     *
     * @param sub The subscope to append
     * @return A new [RequiredScope] with the subscope appended
     *
     * Example:
     * ```kotlin
     * val admin = RequiredScope("admin")
     * val users = admin.subscope(Subscope("users")) // "admin:users"
     *
     * RequiredScope.root.subscope(Subscope("foo")) // "foo" (not "*:foo")
     * ```
     */
    public fun subscope(sub: Subscope): RequiredScope =
        if (this == root) RequiredScope(sub.asString) else RequiredScope("$asString:${sub.asString}")

    /**
     * Checks if this scope contains (grants access to) another scope.
     *
     * A scope contains another if:
     * - This scope is [root] (universal access), OR
     * - The other scope's hierarchy starts with this scope's hierarchy
     *
     * @param scope The scope to check for containment
     * @return `true` if this scope grants access to the given scope
     *
     * Example:
     * ```kotlin
     * RequiredScope("admin") in RequiredScope("admin")        // true
     * RequiredScope("admin:users") in RequiredScope("admin")  // true
     * RequiredScope("admin") in RequiredScope("admin:users")  // false
     * RequiredScope("users") in RequiredScope.root            // true
     * ```
     */
    public operator fun contains(scope: RequiredScope): Boolean =
        this == root || scope.subscopes.startsWith(this.subscopes)

    override fun toString(): String = asString
}

/**
 * Represents a scope granted to an [Authentication] token.
 *
 * Granted scopes determine what resources and endpoints an authenticated entity can access.
 * Like [RequiredScope], granted scopes use hierarchical naming with colon (`:`) delimiters.
 *
 * ## Access Rules
 * - A granted scope satisfies requirements for itself and all child subscopes
 * - Granted scope `foo` meets requirements for `foo`, `foo:bar`, `foo:bar:baz`, etc.
 * - Granted scope `foo:bar` does **not** meet requirement for parent scope `foo`
 * - The special scope `*` (root) meets all requirements except [RequiredScope.root] itself (unless also granted root)
 *
 * ## Example Usage
 * ```kotlin
 * val adminGrant = GrantedScope("admin")
 * val readOnlyGrant = GrantedScope("admin:read")
 *
 * adminGrant.meetsRequirements(RequiredScope("admin:users"))      // true
 * readOnlyGrant.meetsRequirements(RequiredScope("admin:users"))   // true (more specific is fine)
 * readOnlyGrant.meetsRequirements(RequiredScope("admin"))         // false (cannot access parent)
 *
 * // Root scope behavior
 * GrantedScope.root.meetsRequirements(RequiredScope("anything"))  // true
 * GrantedScope("anything").meetsRequirements(RequiredScope.root)  // false (only root grant meets root requirement)
 * ```
 *
 * @property asString The string representation of the granted scope
 * @see RequiredScope
 */
@JvmInline
@Serializable
public value class GrantedScope(public val asString: String) {
    public companion object {
        /**
         * The root granted scope (`*`) that meets requirements for all non-root scopes.
         *
         * Note: Only a granted root scope can meet a [RequiredScope.root] requirement.
         */
        public val root: GrantedScope = GrantedScope("*")
    }

    internal val subscopes: List<String> get() = asString.split(':')

    /**
     * Checks if this granted scope satisfies a required scope.
     *
     * A granted scope meets requirements if:
     * - This is [root] (universal access) and the requirement is not [RequiredScope.root], OR
     * - The required scope's hierarchy starts with this granted scope's hierarchy
     *
     * **Important**: Required [RequiredScope.root] can only be met by granted [root].
     *
     * @param other The required scope to check against
     * @return `true` if this granted scope satisfies the requirement
     */
    public fun meetsRequirements(other: RequiredScope): Boolean {
        if (this == root) return true
        if (other == RequiredScope.root) return false // we already checked that we don't have root access

        return other.subscopes.startsWith(subscopes)
    }

    /**
     * Creates a more restricted [GrantedScope] by appending a subscope.
     *
     * If this scope is [root], the subscope replaces it entirely rather than appending.
     *
     * @param sub The subscope to append
     * @return A new [GrantedScope] with the subscope appended
     *
     * Example:
     * ```kotlin
     * val admin = GrantedScope("admin")
     * val restricted = admin.restrict(Subscope("read")) // "admin:read"
     *
     * GrantedScope.root.restrict(Subscope("foo")) // "foo" (not "*:foo")
     * ```
     */
    public fun restrict(sub: Subscope): GrantedScope =
        if (this == root) GrantedScope(sub.asString) else GrantedScope("$asString:${sub.asString}")

    override fun toString(): String = asString
}

/**
 * Represents a component used to build hierarchical scope paths.
 *
 * Subscopes are the building blocks for constructing [RequiredScope] and [GrantedScope]
 * hierarchies. They can be combined using the `+` operator to create deeper hierarchies.
 *
 * @property asString The string identifier for this subscope component
 *
 * Example:
 * ```kotlin
 * val admin = Subscope("admin")
 * val users = Subscope("users")
 * val combined = admin + users // represents "admin:users"
 *
 * val scope = RequiredScope.root.subscope(combined) // "admin:users"
 * ```
 */
@JvmInline
@Serializable
public value class Subscope(public val asString: String) {
    /**
     * Combines this subscope with another to create a deeper hierarchy.
     *
     * @param other The subscope to append
     * @return A new [Subscope] representing the combined hierarchy
     */
    public operator fun plus(other: Subscope): Subscope = Subscope("$asString:${other.asString}")
}

/**
 * Creates a new set of [RequiredScope] by appending multiple subscopes to each scope in this set.
 *
 * This is useful for applying a set of sub-scopes to multiple parent scopes at once.
 *
 * @param subscopes The subscopes to append to each scope
 * @return A new set containing all combinations of original scopes with each subscope
 *
 * Example:
 * ```kotlin
 * val scopes = setOf(RequiredScope("admin"), RequiredScope("user"))
 * val subs = listOf(Subscope("read"), Subscope("write"))
 * val result = scopes.subscope(subs)
 * // result: ["admin:read", "admin:write", "user:read", "user:write"]
 * ```
 */
public fun Set<RequiredScope>.subscope(subscopes: Iterable<Subscope>): Set<RequiredScope> =
    flatMapTo(HashSet()) { required ->
        subscopes.map { required.subscope(it) }
    }

/**
 * Creates a new set of [GrantedScope] by appending multiple subscopes to each scope in this set.
 *
 * This restricts the granted scopes to more specific subscopes.
 *
 * @param subscopes The subscopes to append to each scope
 * @return A new set containing all combinations of original scopes with each subscope
 *
 * Example:
 * ```kotlin
 * val grants = setOf(GrantedScope("admin"))
 * val subs = listOf(Subscope("read"), Subscope("write"))
 * val result = grants.restrict(subs)
 * // result: ["admin:read", "admin:write"]
 * ```
 */
public fun Set<GrantedScope>.restrict(subscopes: Iterable<Subscope>): Set<GrantedScope> =
    flatMapTo(HashSet()) { granted ->
        subscopes.map { granted.restrict(it) }
    }

/**
 * Checks if this set of granted scopes satisfies all required scopes.
 *
 * Returns `true` only if every required scope has at least one granted scope that meets its requirements.
 *
 * @param other The set of required scopes to check against
 * @return `true` if all requirements are met
 *
 * Example:
 * ```kotlin
 * val grants = setOf(GrantedScope("admin"), GrantedScope("user:read"))
 * val requirements = setOf(RequiredScope("admin:users"), RequiredScope("user:read"))
 * grants.meetsRequirements(requirements) // true
 * ```
 */
public fun Set<GrantedScope>.meetsRequirements(other: Set<RequiredScope>): Boolean =
    other.all { scope ->
        this.any { it.meetsRequirements(scope) }
    }

/**
 * Simplifies a collection of required scopes by removing redundant entries.
 *
 * This function removes scopes that are already covered by broader scopes in the collection.
 * For example, if both `admin` and `admin:users` are present, only `admin` is kept since
 * it already grants access to `admin:users`.
 *
 * **Algorithm**: For each scope, if a broader scope already exists that contains it, skip it.
 * If the incoming scope is broader than existing scopes, remove those narrower scopes.
 *
 * @return A simplified set containing only the necessary scopes
 *
 * Example:
 * ```kotlin
 * val scopes = listOf(
 *     RequiredScope("admin"),
 *     RequiredScope("admin:users"),
 *     RequiredScope("admin:posts"),
 *     RequiredScope("other")
 * )
 * val simplified = scopes.simplify()
 * // result: [RequiredScope("admin"), RequiredScope("other")]
 * ```
 */
public fun Iterable<RequiredScope>.simplify(): Set<RequiredScope> {
    val current = HashSet<RequiredScope>()
    for (scope in this) {
        // If an existing broader scope already covers this scope, skip it
        if (current.any { it.contains(scope) }) continue
        // Remove any existing scopes that are narrower than the incoming scope
        val toRemove = current.filter { scope.contains(it) }
        if (toRemove.isNotEmpty()) current.removeAll(toRemove.toSet())
        current.add(scope)
    }
    return current
}

/**
 * Checks if this list starts with all elements from another list.
 *
 * @param other The list to check as a prefix
 * @return `true` if this list starts with all elements from [other]
 */
private fun <T> List<T>.startsWith(other: List<T>): Boolean {
    if (this.size < other.size) return false
    for (i in other.indices) {
        if (this[i] != other[i]) return false
    }
    return true
}

/*
 * TODO: API Recommendations
 *
 * 1. Consider adding validation for scope strings to prevent malformed scopes:
 *    - Empty strings
 *    - Consecutive colons ("admin::users")
 *    - Leading/trailing colons (":admin" or "admin:")
 *    - Invalid characters in scope names
 *
 * 2. Consider adding a factory function or builder pattern for creating scopes
 *    to encourage proper validation at construction time:
 *    `RequiredScope.of("admin", "users")` instead of `RequiredScope("admin:users")`
 *
 *
 * 4. Consider adding convenience functions for common patterns:
 *    - `RequiredScope.allOf(vararg scopes: String)` to create multiple required scopes
 *    - `GrantedScope.anyOf(vararg scopes: String)` for multiple granted scopes
 *
 * 5. Consider making subscopes immutable and providing a `subscopes()` public accessor
 *    that returns List<String> for introspection purposes (debugging, logging, UI display).
 *
 * 6. The Set<GrantedScope>.meetsRequirements extension might benefit from short-circuit
 *    optimization for common cases (empty sets, root scope present).
 */