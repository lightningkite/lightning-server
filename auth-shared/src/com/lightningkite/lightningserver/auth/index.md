# auth-shared Package

Cross-platform (multiplatform) authentication scope definitions for Lightning Server.

## Overview

This package provides the core scope-based authorization primitives used throughout Lightning Server's authentication
system. These types are platform-agnostic and can be used in both JVM and shared multiplatform code.

## Files

### Scope.kt

Defines the scope hierarchy system for fine-grained access control:

- **RequiredScope** - Represents a scope required to access a resource. Hierarchical scopes use `:` as delimiter (e.g.,
  `admin:users:write`)
- **GrantedScope** - Represents a scope granted to an authenticated entity. Determines what resources the entity can
  access
- **Subscope** - Building block for constructing hierarchical scope paths. Can be combined using `+` operator

#### Key Functions

- `Set<GrantedScope>.meetsRequirements(Set<RequiredScope>)` - Check if granted scopes satisfy requirements
- `Iterable<RequiredScope>.simplify()` - Remove redundant scopes from a collection
- `Set<RequiredScope>.subscope(Iterable<Subscope>)` - Create scoped variants of requirements
- `Set<GrantedScope>.restrict(Iterable<Subscope>)` - Create more restricted granted scopes

## Scope Hierarchy

Scopes follow a hierarchical containment model:

- Broader scopes grant access to narrower subscopes
- `admin` grants access to `admin:users`, `admin:posts`, etc.
- `admin:users` does NOT grant access to `admin`
- The special scope `*` (root) grants universal access

## Usage Example

```kotlin
// Define required scope for an endpoint
val readUsersScope = RequiredScope("api:users:read")

// Grant scopes to authentication
val userScopes = setOf(
    GrantedScope("api:users:read"),
    GrantedScope("api:posts:write")
)

// Check if requirements are met
val canAccess = userScopes.meetsRequirements(setOf(readUsersScope)) // true

// Create subscopes
val adminScope = RequiredScope("admin")
val usersSubscope = Subscope("users")
val adminUsersScope = adminScope.subscope(usersSubscope) // "admin:users"

// Simplify redundant scopes
val scopes = listOf(
    RequiredScope("admin"),
    RequiredScope("admin:users"),
    RequiredScope("admin:posts")
).simplify() // Results in just [RequiredScope("admin")]
```

## Design Notes

- All scope types are value classes (zero runtime overhead)
- Scopes are case-sensitive
- No validation is performed on scope strings (consider adding validation before construction)
- Root scope (`*`) has special semantics - see `GrantedScope.meetsRequirements` documentation
