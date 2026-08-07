# auth Package

JVM-side authentication and authorization system for Lightning Server.

## Overview

This package provides the complete authentication and authorization infrastructure for Lightning Server applications. It
includes:

- Authentication token management
- Authorization requirement definitions
- Principal type system
- Support for multiple authentication methods
- Masquerading capabilities
- Extensible authentication readers

## Core Files

### Authentication.kt

The core authentication token type:

- **Authentication<SUBJECT>** - Represents an authenticated entity with associated scopes, expiration, and caching
- **Authentication.Reader** - Interface for reading authentication from HTTP requests
- **AuthCacheKey** - Type alias for cache keys specific to authentication

#### Key Features

- Type-safe subject access via `principalType` and `id` extension properties
- Automatic caching of fetched subjects with `fetch()` extension
- Masquerading support via `fromMasquerade` property
- Session tracking with `sessionId`
- Scope-based permissions with `scopes` property
- Cache for expensive computations

### AuthRequirement.kt

Defines requirements for accessing protected resources:

- **AuthRequirement.None** - No authentication required (public access)
- **AuthRequirement.Authenticated** - Any authentication required (type-agnostic)
- **AuthRequirement.AuthenticatedAs** - Specific principal type required
- **AuthRequirement.AuthSetting** - Runtime-configurable requirement
- **AuthRequirement.Options** - Multiple alternative requirements (OR logic)

#### Built-in Auth Settings

- **IsSuperUser** - Highest privilege level
- **IsAdmin** - Administrative access (defaults to IsSuperUser)
- **IsDeveloper** - Developer access (defaults to IsSuperUser)

#### Checking Requirements

- `AuthRequirement.check(auth)` - Returns `Result.Accepted` or `Result.Rejected`
- `AuthRequirement.assert(auth)` - Throws `ForbiddenException` if rejected
- `AuthRequirement.accepts(auth)` - Returns boolean for quick checks

### PrincipalType.kt

Defines types of authenticated principals:

- **PrincipalType<SUBJECT, ID>** - Interface for defining user/entity types
- **register(type)** - Extension to register principal types in ServerBuilder
- **principalTypeFor<T>()** - Lookup registered principal types by subject type

#### Key Methods

- `fetch(id)` - Retrieve subject data from storage
- `permitMasquerade(from, into)` - Control masquerading authorization
- `fetchByProperty(property, value)` - Look up subjects by properties
- `getProperty(principal, property)` - Extract property values
- `hasProperty(property)` - Check if property exists

### AuthRequirement.ext.kt

Extension functions for working with auth requirements:

- `noAuth` - Convenience val for `AuthRequirement.None`
- `anyAuth` - Any authentication with no scope requirements
- `recentRootAuth` - Recent authentication with root scope (10 minute max age)
- `PrincipalType.require(...)` - Create typed auth requirements
- `AuthRequirement.or(other)` - Combine requirements with OR logic
- `AuthRequirement.subscope(...)` - Add subscope restrictions
- `AuthRequirement.naturalLanguage()` - Human-readable requirement description

### Authentication.ext.kt

Extension functions for working with authentication:

- `Authentication.get(key)` - Access cached values
- `Authentication.principalType` - Type-safe principal type access
- `Authentication.id` - Type-safe ID access
- `Authentication.fetch()` - Fetch and cache subject data
- `Authentication.meetsRequirements(scopes)` - Check scope requirements
- `PrincipalType.testAuth(...)` - Create test authentication instances
- `authReaders` - Registry for authentication readers

### PrincipalType.ext.kt

Extension functions for principal types:

- `PrincipalType.idString(id)` - Serialize ID to string
- `PrincipalType.fetchUserIdString(property, value)` - Fetch and return serialized ID

## Usage Examples

### Defining a Principal Type

```kotlin
@Serializable
data class User(
    override val _id: Uuid,
    val email: String,
    val name: String,
    val isAdmin: Boolean
) : HasId<Uuid> {
    companion object : PrincipalType<User, Uuid> {
        override val idSerializer = Uuid.serializer()
        override val subjectSerializer = serializer()

        // The table itself lives on the ServerBuilder, declared once:
        //     val userTable = database.registerTable<User>("User")
        // registerTable requires a ServerBuilder in context, so it cannot be
        // declared here — reference it instead.
        context(server: ServerRuntime)
        override suspend fun fetch(id: Uuid): User {
            return Server.userTable().get(id)
                ?: throw NotFoundException("User not found")
        }

        context(server: ServerRuntime)
        override suspend fun permitMasquerade(
            from: Authentication<*>,
            into: Authentication<User>
        ): Boolean {
            // Allow admins to masquerade
            return from.meetsRequirements(setOf(RequiredScope("admin")))
        }
    }
}
```

### Registering Principal Types

```kotlin
object Server : ServerBuilder() {
    init {
        register(User)
    }
}
```

### Creating Authentication Requirements

```kotlin
// No authentication required
val publicEndpoint = noAuth

// Any authenticated user
val authRequired = anyAuth

// Specific user type with root scope
val userRequired = User.require()

// Specific scope requirements
val apiReadOnly = User.require(scope = RequiredScope("api:read"))

// With time constraints
val recentAuth = User.require(maxAge = 10.minutes)

// With custom validation
val verifiedEmail = User.require { it.fetch().emailVerified }

// Multiple options
val adminOrUser = Admin.require() or User.require()
val optionalUser = User.require() or noAuth
```

### Using Authentication in Endpoints

```kotlin
val getProfile = path.path("profile").get.api(
    summary = "Get user profile",
    authOptions = User.require(),
    implementation = { auth: Authentication<User> ->
        val user = auth.fetch()
        UserProfileResponse(
            name = user.name,
            email = user.email
        )
    }
)
```

### Implementing Authentication Readers

```kotlin
object BearerTokenReader : Authentication.Reader<User> {
    override val priority = 100.0

    context(server: ServerRuntime)
    override suspend fun read(request: Request<*>): Authentication<User>? {
        val token = request.headers[HttpHeader.Authorization]
            ?.root
            ?.removePrefix("Bearer ")
            ?: return null

        val session = validateAndDecodeToken(token) ?: return null

        return Authentication(
            principalType = User,
            id = session.userId,
            sessionId = session.id,
            scopes = session.scopes
        )
    }
}

// Register in ServerBuilder
object Server : ServerBuilder() {
    init {
        authReaders.add(BearerTokenReader)
    }
}
```

## Design Principles

1. **Type Safety** - Authentication is parameterized by subject type for compile-time safety
2. **Composability** - Requirements can be combined with `or` operator for flexible authorization
3. **Extensibility** - Custom principal types, readers, and requirements are first-class
4. **Caching** - Built-in caching for expensive subject fetches and computations
5. **Runtime Configuration** - AuthSettings allow runtime customization of requirements
6. **Masquerading** - First-class support for admin impersonation with permission checking

## Security Considerations

- Always use HTTPS in production for authentication tokens
- Implement proper token validation in authentication readers
- Use appropriate scope granularity for your application
- Consider maxAge constraints for sensitive operations
- Review and test masquerade permissions carefully
- Validate authentication expiration times
