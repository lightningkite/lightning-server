package com.lightningkite.lightningserver.auth

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline


/**
 * A scope used for authentication. Scopes can contain subscopes delimited
 * by a colon `:`.
 *
 * [Authentication] limited to the scope `foo` will be able to access
 * endpoints with the scope `foo` or any sub-scope, such as `foo:bar`,
 * `foo:bar:baz`, etc.
 *
 * On the other hand, [Authentication] limited to a sub-scope like `foo:bar`
 * will not be able to access parent scopes (`foo`).
 * */
@JvmInline
@Serializable
public value class RequiredScope(public val asString: String) {
    public companion object {
        public val root: RequiredScope = RequiredScope("*")
    }

    internal val subscopes: List<String> get() = asString.split(':')

    public fun subscope(sub: Subscope): RequiredScope =
        if (this == root) RequiredScope(sub.asString) else RequiredScope("$asString:${sub.asString}")

    public operator fun contains(scope: RequiredScope): Boolean =
        this == root || scope.subscopes.startsWith(this.subscopes)

    override fun toString(): String = asString
}

@JvmInline
@Serializable
public value class GrantedScope(public val asString: String) {
    public companion object {
        public val root: GrantedScope = GrantedScope("*")
    }

    internal val subscopes: List<String> get() = asString.split(':')

    public fun meetsRequirements(other: RequiredScope): Boolean {
        if (this.asString == "*") return true
        if (other.asString == "*") return false // we already checked that we don't have root access

        return other.subscopes.startsWith(subscopes)
    }

    public fun restrict(sub: Subscope): GrantedScope =
        if (this == root) GrantedScope(sub.asString) else GrantedScope("$asString:${sub.asString}")

    override fun toString(): String = asString
}

public object GrantedScopes {
    public val root: Set<GrantedScope> = setOf(GrantedScope.root)
}

public object RequiredScopes {
    public val root: Set<RequiredScope> = setOf(RequiredScope.root)
}

@JvmInline
@Serializable
public value class Subscope(public val asString: String)

public fun Set<RequiredScope>.subscope(subscopes: Iterable<Subscope>): Set<RequiredScope> =
    flatMapTo(HashSet()) { required ->
        subscopes.map { required.subscope(it) }
    }

public fun Set<GrantedScope>.restrict(subscopes: Iterable<Subscope>): Set<GrantedScope> =
    flatMapTo(HashSet()) { granted ->
        subscopes.map { granted.restrict(it) }
    }

public fun Set<GrantedScope>.meetsRequirements(other: Set<RequiredScope>): Boolean =
    other.all { scope ->
        this.any { it.meetsRequirements(scope) }
    }

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

private fun <T> List<T>.startsWith(other: List<T>): Boolean {
    if (this.size < other.size) return false
    for (i in other.indices) {
        if (this[i] != other[i]) return false
    }
    return true
}