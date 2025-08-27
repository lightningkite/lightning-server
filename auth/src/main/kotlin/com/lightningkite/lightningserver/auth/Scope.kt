package com.lightningkite.lightningserver.auth

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
public typealias Scope = String

private val Scope.subscopes: List<String> get() = this.split(':')

public fun Scope.acceptsScope(other: Scope): Boolean {
    if (this == "*") return true
    if (other == "*") return false // we already checked that we don't have root access

    val otherSubs = other.subscopes
    for ((idx, sub) in subscopes.withIndex()) {
        if (sub != otherSubs.getOrNull(idx)) return false
    }
    return true
}

public fun Set<Scope>.acceptsAllScopes(other: Set<Scope>): Boolean =
    other.all { scope ->
        this.any { it.acceptsScope(scope) }
    }

public fun Set<Scope>.acceptsAnyScopes(other: Set<Scope>): Boolean =
    other.any { scope ->
        this.any { it.acceptsScope(scope) }
    }


public fun Authentication<*>.acceptsScopes(scopes: Set<String>): Boolean {
    if (scopes.isEmpty()) return true

    limitTo?.scopes?.let { limits ->
        if (limits.isEmpty() || limits.contains("*")) return@let
        if (scopes.contains("*")) return false // we know limits doesn't have root access
        if (!limits.acceptsAllScopes(scopes)) return false
    }
    forbid?.scopes?.let { forbidden ->
        if (forbidden.isEmpty()) return@let
        if (forbidden.contains("*")) return false
        if (forbidden.acceptsAnyScopes(scopes)) return false
    }

    return true
}