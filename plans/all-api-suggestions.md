# Lightning Server API Suggestions and TODOs

**Generated:** 2025-11-12 11:30:30
**Source:** Extracted from TODO/API comments in all Kotlin files

This file consolidates all API improvement suggestions, recommendations, and TODOs
scattered throughout the Lightning Server codebase.

---

## Summary

- **Total Checklist Items:** 673
- **Modules Covered:** 19

- **auth:** 25 items
- **auth-shared:** 12 items
- **core:** 337 items
- **core-shared:** 24 items
- **engine-jdk-server:** 6 items
- **engine-ktor:** 11 items
- **engine-netty:** 22 items
- **media:** 14 items
- **media-shared:** 8 items
- **notifications:** 2 items
- **notifications-shared:** 13 items
- **sessions:** 102 items
- **sessions-email:** 6 items
- **sessions-oauth:** 17 items
- **sessions-oauth-shared:** 8 items
- **sessions-shared:** 12 items
- **sessions-sms:** 18 items
- **typed:** 9 items
- **typed-shared:** 27 items

---

## Module: auth

### `auth/src/main/kotlin/com/lightningkite/lightningserver/auth/Authentication.kt`

- [ ] **`Authentication.kt:346`** TODO: API Recommendations
- [ ] **`Authentication.kt:347`** The masquerade functionality in CacheKey.calculate() (lines 236-264) has several considerations:
- [ ] **`Authentication.kt:348`** The X-Masquerade header format "principal/id" could be documented more clearly
- [ ] **`Authentication.kt:349`** Consider adding a dedicated MasqueradeRequest data class instead of parsing strings
- [ ] **`Authentication.kt:350`** The permitMasquerade check happens AFTER creating the mask Authentication, which could be inefficient. Consider checking permission before construction.
- [ ] **`Authentication.kt:352`** The precache() function iterates serially. For better performance, consider:```kotlin
coroutineScope {
keys.map { key -> async { cache.get(key, this@Authentication) } }.awaitAll()
}
```
- [ ] **`Authentication.kt:358`** The copy() method only allows changing expiration and scopes. Users may want to copy with modified sessionId or other fields. Consider making it more flexible or documenting why these are the only mutable fields.
- [ ] **`Authentication.kt:361`** The toString() includes cache contents which could be very large. Consider truncating or summarizing the cache for better log readability.
- [ ] **`Authentication.kt:363`** The @Deprecated methods for caching auth within itself are good, but the error messages could be more helpful (explain WHY this is problematic).
- [ ] **`Authentication.kt:365`** Consider adding a method to check if authentication is expired:```kotlin
context(server: ServerRuntime)
fun isExpired(): Boolean = expiration?.let { it < server.clock.now() } ?: false
```
- [ ] **`Authentication.kt:370`** The Reader interface could benefit from a default implementation or helper for common patterns (e.g., reading from Authorization header with different schemes).

### `auth/src/main/kotlin/com/lightningkite/lightningserver/auth/PrincipalType.kt`

- [ ] **`PrincipalType.kt:190`** TODO: API Recommendations
- [ ] **`PrincipalType.kt:191`** The fetchByProperty method could be more efficient with an index-based lookup system. Consider adding a registration mechanism for indexed properties:```kotlin
val indices = mapOf(
"email" to { email: String -> database().table<User>().find { it.email eq email }.first() }
)
```
- [ ] **`PrincipalType.kt:198`** The subjectCacheKey has hard-coded expiration (5 minutes) and localOnly (true). Consider making these configurable per principal type for different use cases.
- [ ] **`PrincipalType.kt:200`** The hasProperty and getProperty methods use reflection-like behavior through serialization. For performance-critical paths, consider adding a compile-time code generation approach.
- [ ] **`PrincipalType.kt:202`** The permitMasquerade logic defaults to false (secure by default - good!), but users might not realize they need to override it. Consider adding a logging statement when masquerade is attempted but not permitted.
- [ ] **`PrincipalType.kt:205`** The precache list could benefit from a way to conditionally include keys based on scopes or other authentication properties to avoid loading unnecessary data.

### `auth/src/main/kotlin/com/lightningkite/lightningserver/auth/AuthRequirement.kt`

- [ ] **`AuthRequirement.kt:418`** TODO: API Recommendations
- [ ] **`AuthRequirement.kt:419`** Consider adding a convenience method for checking if a requirement would accept a given auth without throwing exceptions: `fun wouldAccept(auth: Authentication<*>?): Boolean`
- [ ] **`AuthRequirement.kt:421`** The maxAge check uses `now() - issuedAt` which could overflow for very old tokens. Consider adding a guard or using a safer comparison method.
- [ ] **`AuthRequirement.kt:423`** The custom `requirement` lambda in Authenticated and AuthenticatedAs lacks documentation about exception handling. Should exceptions be caught and converted to Rejected results? Current behavior may surprise users.
- [ ] **`AuthRequirement.kt:426`** AuthSetting.Scoped seems to have a potential issue at line 205: `wraps.setting()?.subscope(subscopes)?.check(auth) ?: wraps.check(auth)` If setting() returns null but wraps.default exists, this falls back to wraps.check(auth), which would check the default without the subscope applied. Consider whether this is intended.
- [ ] **`AuthRequirement.kt:430`** Options.check() collects all rejection reasons but only returns them if all fail. Consider logging these internally for debugging, as they may help diagnose auth issues.
- [ ] **`AuthRequirement.kt:432`** Consider adding a builder pattern for complex requirements:```kotlin
AuthRequirement.build<User> {
principalType(UserPrincipal)
scope(RequiredScope("admin"))
maxAge(10.minutes)
require { it.fetch().emailVerified }
}
```
- [ ] **`AuthRequirement.kt:441`** The Result sealed interface could benefit from additional information in Rejected, such as which specific check failed (scope, maxAge, custom requirement, etc.) for better error messages and debugging.

## Module: auth-shared

### `auth-shared/src/commonMain/kotlin/com/lightningkite/lightningserver/auth/Scope.kt`

- [ ] **`Scope.kt:325`** TODO: API Recommendations
- [ ] **`Scope.kt:326`** Consider adding validation for scope strings to prevent malformed scopes:
- [ ] **`Scope.kt:327`** Empty strings
- [ ] **`Scope.kt:328`** Consecutive colons ("admin::users")
- [ ] **`Scope.kt:329`** Leading/trailing colons (":admin" or "admin:")
- [ ] **`Scope.kt:330`** Invalid characters in scope names
- [ ] **`Scope.kt:331`** Consider adding a factory function or builder pattern for creating scopes to encourage proper validation at construction time: `RequiredScope.of("admin", "users")` instead of `RequiredScope("admin:users")`
- [ ] **`Scope.kt:334`** Consider adding convenience functions for common patterns:
- [ ] **`Scope.kt:335`** `RequiredScope.allOf(vararg scopes: String)` to create multiple required scopes
- [ ] **`Scope.kt:336`** `GrantedScope.anyOf(vararg scopes: String)` for multiple granted scopes
- [ ] **`Scope.kt:337`** Consider making subscopes immutable and providing a `subscopes()` public accessor that returns List<String> for introspection purposes (debugging, logging, UI display).
- [ ] **`Scope.kt:339`** The Set<GrantedScope>.meetsRequirements extension might benefit from short-circuit optimization for common cases (empty sets, root scope present).

## Module: core

### `core/src/main/kotlin/com/lightningkite/lightningserver/encryption/SecureHash.kt`

- [ ] **`SecureHash.kt:159`** TODO: API Recommendations
- [ ] **`SecureHash.kt:160`** Add rate limiting guidance: Document that these functions should be protected by rate limiting in production to prevent denial-of-service attacks.
- [ ] **`SecureHash.kt:162`** Consider blocking versions: Add secureHashBlocking() and checkAgainstHashBlocking() for consistency with the rest of the API.
- [ ] **`SecureHash.kt:164`** Iteration count configuration: Consider making the iteration count configurable (with a secure default) to allow tuning based on performance requirements.
- [ ] **`SecureHash.kt:166`** Consider Argon2: PBKDF2 is secure but Argon2id is the current best practice for password hashing. Consider adding Argon2id support if the cryptography library supports it.

### `core/src/main/kotlin/com/lightningkite/lightningserver/encryption/Signer.kt`

- [ ] **`Signer.kt:219`** TODO: API Recommendations
- [ ] **`Signer.kt:220`** Consider adding helper methods for RSA signers similar to the ECDSA helpers (ES256, ES384, ES512).
- [ ] **`Signer.kt:221`** Error handling: Consider making verify() return a Result type or throwing exceptions on signature format errors to distinguish between invalid signatures and malformed data.

### `core/src/main/kotlin/com/lightningkite/lightningserver/encryption/SecretBasis.ciphers.kt`

- [ ] **`SecretBasis.ciphers.kt:238`** TODO: API Recommendations
- [ ] **`SecretBasis.ciphers.kt:239`** Consider consistency in naming: The convention uses both `cipher()` and `AES_GCM()` patterns. It might be clearer to have `cipher()` always use GCM and require explicit mode selection otherwise.
- [ ] **`SecretBasis.ciphers.kt:241`** Add ChaCha20-Poly1305 support: This is a modern AEAD cipher that may be preferable to AES-GCM in some contexts (especially non-hardware-accelerated environments).

### `core/src/main/kotlin/com/lightningkite/lightningserver/encryption/SecretBasis.kt`

- [ ] **`SecretBasis.kt:274`** TODO: API Recommendations
- [ ] **`SecretBasis.kt:275`** Consider adding a companion factory method: `SecretBasis.fromBytes(bytes: ByteArray)` for users who want to construct from raw bytes instead of Base64.

### `core/src/main/kotlin/com/lightningkite/lightningserver/encryption/SecretBasis.signers.kt`

- [ ] **`SecretBasis.signers.kt:136`** TODO: API Recommendations
- [ ] **`SecretBasis.signers.kt:137`** Add blocking versions: Consider adding HS256_Blocking, HS384_Blocking, HS512_Blocking for consistency with other APIs.
- [ ] **`SecretBasis.signers.kt:139`** Consider EdDSA support: For asymmetric signing (public/private key pairs), EdDSA (Ed25519) would be a valuable addition for use cases requiring public key verification.

### `core/src/main/kotlin/com/lightningkite/lightningserver/settings/ServerSettings.ext.kt`

- [ ] **`ServerSettings.ext.kt:197`** TODO: API Recommendations for ServerSettings.ext.kt
- [ ] **`ServerSettings.ext.kt:198`** **POTENTIAL ISSUE**: Properties format parsing uses `substringAfter('=')` which only works for simple values. If a property value contains '=' (like a URL or connection string), only the first part is kept. Should use `substringAfter('=', missingDelimiterValue = "")` or similar.
- [ ] **`ServerSettings.ext.kt:201`** **POTENTIAL ISSUE**: Properties parsing treats '#' as comment anywhere on line, but doesn't handle escaped '#'. A value like "color=#FF0000" would be truncated. Need proper escaping.
- [ ] **`ServerSettings.ext.kt:203`** The file extension check for properties format only matches exactly "properties". Files like "config.props" or "settings.property" won't be detected. Consider contains() or regex.
- [ ] **`ServerSettings.ext.kt:205`** loadFromFile() auto-generates a settings file with defaults if it doesn't exist, then throws MissingSettingFile. This is a good workflow but could be surprising - some might expect it to succeed using the defaults. Document this behavior prominently.
- [ ] **`ServerSettings.ext.kt:208`** The decryption feature reads from environment variable but doesn't validate the password strength or check if decryption actually succeeded (could return garbage). Add validation.
- [ ] **`ServerSettings.ext.kt:210`** When missing required settings, a "suggested" file is created but there's no indication of what changed between the original and suggested files. Consider generating a diff or annotating which settings were missing.
- [ ] **`ServerSettings.ext.kt:213`** The "defaults" property feature (referencing another JSON file) is documented but not shown in this file. This feature appears to be implemented in SettingsSerializer.kt. Consider adding cross-reference docs or moving logic here.
- [ ] **`ServerSettings.ext.kt:216`** No validation that the file isn't too large or contains malicious content. Consider adding size limits or sandboxing for untrusted settings files.
- [ ] **`ServerSettings.ext.kt:218`** The settingsFormat function returns EmptySerializersModule for properties format but uses the provided module for actual serialization. This inconsistency is confusing.

### `core/src/main/kotlin/com/lightningkite/lightningserver/settings/ServerSettings.kt`

- [ ] **`ServerSettings.kt:220`** TODO: API Recommendations for ServerSettings.kt
- [ ] **`ServerSettings.kt:221`** The `ready()` function throws `Error` (not Exception) when settings fail to preload. This is unusual - consider using a more specific exception type that extends Exception.
- [ ] **`ServerSettings.kt:223`** The missing settings check in `ready()` doesn't distinguish between truly required settings and optional settings with no default. The error message could be more helpful.
- [ ] **`ServerSettings.kt:225`** The `get()` function performs lazy transformation but the caching isn't thread-safe. If called concurrently during initial ready phase, could transform the same setting twice. (Though this is documented in ServerSetting.kt TODO, worth noting here too.)
- [ ] **`ServerSettings.kt:228`** `readyUsingDefaults()` bypasses all validation with a warning, but doesn't actually enforce that defaults exist for all settings. Could still throw during get().
- [ ] **`ServerSettings.kt:230`** The serializable registry uses `Any?` type which loses type safety. A setting configured with the wrong type won't be caught until transformation time, potentially late in startup.
- [ ] **`ServerSettings.kt:232`** No way to query if a specific setting has been configured before calling ready(). Adding `isSet(setting)` or `hasValue(setting)` would be useful for conditional logic.
- [ ] **`ServerSettings.kt:234`** The `allSerializable()` and `allGoals()` functions create new maps on every call. Consider caching these after ready() since they won't change.
- [ ] **`ServerSettings.kt:236`** No mechanism to reload or hot-reload settings after ready(). Once ready, settings are permanently locked. Consider adding support for reloadable settings.

### `core/src/main/kotlin/com/lightningkite/lightningserver/exceptions.kt`

- [ ] **`exceptions.kt:133`** TODO: API Recommendations
- [ ] **`exceptions.kt:134`** Consider adding more common HTTP status exceptions (e.g., ConflictException for 409, UnprocessableEntityException for 422, TooManyRequestsException for 429)
- [ ] **`exceptions.kt:136`** Consider adding builder-style methods for adding headers to exceptions
- [ ] **`exceptions.kt:137`** The detail field could benefit from documentation explaining its intended use vs message
- [ ] **`exceptions.kt:138`** Consider making LSError a documented public type if it isn't already

### `core/src/main/kotlin/com/lightningkite/lightningserver/pathing/PathSpecMap.kt`

- [ ] **`PathSpecMap.kt:152`** TODO: API Recommendations for PathSpecMap.kt
- [ ] **`PathSpecMap.kt:153`** The match() methods don't document the priority order when multiple paths could match. For example, /users/admin vs /users/{id} - which wins? Document the precedence rules clearly.
- [ ] **`PathSpecMap.kt:155`** Match failures don't provide diagnostic information about what paths were tried or why they failed. Consider adding a matchWithDiagnostics() method that returns a Result with error details.
- [ ] **`PathSpecMap.kt:157`** The getter function in match() is powerful but unusual. Consider renaming to 'selector' or 'filter' to make the intent clearer, or providing convenience methods like matchByHttpMethod().
- [ ] **`PathSpecMap.kt:159`** The Map interface implementation (containsKey, containsValue, etc.) is inefficient - it converts the entire sequence to a collection. Consider caching these or documenting the performance implications.
- [ ] **`PathSpecMap.kt:161`** No way to check for overlapping/ambiguous routes at build time. Consider adding a validate() method that detects potential ambiguities or shadowed routes.
- [ ] **`PathSpecMap.kt:163`** The buildPathSpecMap and toSealedPathSpecMap functions would benefit from KDoc explaining when to use each.
- [ ] **`PathSpecMap.kt:164`** Performance: The matching algorithm has to walk through the tree. For servers with hundreds of routes, consider adding metrics or optimizations (e.g., early exit for exact constant path matches).

### `core/src/main/kotlin/com/lightningkite/lightningserver/pathing/PathSpec.kt`

- [ ] **`PathSpec.kt:341`** TODO: API Recommendations for PathSpec.kt
- [ ] **`PathSpec.kt:342`** PathSpec equality check uses segment equality, but Wildcard<T> is a data class that includes the serializer. Two wildcards with different serializers for the same type would be unequal. Document this behavior or consider only comparing names for wildcards in path equality.
- [ ] **`PathSpec.kt:345`** Segment.Constant has validation (no slashes) but no validation for other problematic characters like '?', '#', or '%'. Consider adding more comprehensive validation.
- [ ] **`PathSpec.kt:347`** The Segment.fromString function always creates String-typed wildcards. This loses type information and could lead to runtime deserialization issues. Document this limitation clearly.
- [ ] **`PathSpec.kt:349`** PathSpec.Afterwards.fromString uses simple string matching. A path like "/test/{arg}" would incorrectly detect TrailingSegments if arg name happens to be "...}". Use more robust parsing.
- [ ] **`PathSpec.kt:351`** The DummyPathSpecSerializer is marked as "Dummy" suggesting it's incomplete or for testing only. Either implement properly or document why serialization is limited.
- [ ] **`PathSpec.kt:353`** No validation that wildcard names are unique within a path. Having two wildcards with the same name could cause confusion. Consider adding validation in the make() factory.
- [ ] **`PathSpec.kt:355`** The PathSpec classes (PathSpec0-3, PathSpecMany) have significant code duplication. Consider using inline classes or sealed interfaces to reduce duplication.
- [ ] **`PathSpec.kt:357`** No way to check if two PathSpecs are ambiguous/overlapping. For example, /users/{id} and /users/admin could match the same request. Consider adding a PathSpec.overlapsWith(other: PathSpec) method.
- [ ] **`PathSpec.kt:359`** The make() factory uses when() on wildcard count but doesn't handle negative counts (impossible) or validate that the wildcard count matches actual wildcards in segments.
- [ ] **`PathSpec.kt:361`** Consider adding a PathSpec.matches(pathSegments: PathSegments): Boolean for quick match testing without needing the full PathSpecMap infrastructure.

### `core/src/main/kotlin/com/lightningkite/lightningserver/serialization/Serialization.kt`

- [ ] **`Serialization.kt:75`** TODO: API Recommendations for Serialization.kt
- [ ] **`Serialization.kt:76`** The class is open and all properties are open, allowing subclasses to override formats. However, there's no clear use case documented for when/why you'd subclass this. Consider making it final or documenting the extension points.
- [ ] **`Serialization.kt:79`** Both JSON configurations use isLenient=true which accepts non-standard JSON. This could allow malformed input to pass through. Document the security implications or consider making this configurable.
- [ ] **`Serialization.kt:82`** No validation limits on the formats (max nesting depth, max string length, etc.). Large or deeply nested JSON could cause DoS. Consider adding configurable limits.
- [ ] **`Serialization.kt:84`** The serializersModule defaults to empty, which means no polymorphic serialization or contextual serializers by default. This could be surprising. Document clearly.
- [ ] **`Serialization.kt:86`** No prettyPrint option exposed for JSON. While prettyPrint=false is usually right for production, having a debug mode could be useful. Consider adding a companion object factory method like Serialization.debug().

### `core/src/main/kotlin/com/lightningkite/lightningserver/serialization/MediaTypeCoder.kt`

- [ ] **`MediaTypeCoder.kt:162`** TODO: API Recommendations for MediaTypeCoder.kt
- [ ] **`MediaTypeCoder.kt:163`** The priority system allows multiple coders for the same media type, but there's no documentation on how ties are resolved when priorities are equal. Document the behavior (first registered? last registered? undefined?).
- [ ] **`MediaTypeCoder.kt:166`** The accepts() function defaults to returning true, meaning coders accept all parameters by default. This could lead to incorrect handling of charset or other parameters. Consider requiring explicit parameter handling or at least logging when accepts() is not overridden.
- [ ] **`MediaTypeCoder.kt:169`** MediaTypeEncoder.ws() converts Data.Text to Text frame but everything else to Binary. JSON is typically text but would be sent as Binary. Document this behavior or consider checking the media type (application/json -> Text, application/octet-stream -> Binary).
- [ ] **`MediaTypeCoder.kt:172`** MediaTypeEncoder.streaming() has a default implementation but no guidance on when to override. Document use cases like JSON streaming, CSV generation, etc.
- [ ] **`MediaTypeCoder.kt:174`** No error handling guidance for malformed input in invoke(). Should implementations throw specific exceptions? Return null? Document expected error handling patterns.
- [ ] **`MediaTypeCoder.kt:176`** The MediaTypeCoder interface has duplicate default implementations of priority and accepts() due to inheriting from both interfaces. While this works, it's redundant and could be confusing.
- [ ] **`MediaTypeCoder.kt:178`** No size limits or validation requirements documented. Implementations could accept unbounded input leading to memory issues. Add guidance on defensive parsing.

### `core/src/main/kotlin/com/lightningkite/lightningserver/runtime/ServerRuntime.kt`

- [ ] **`ServerRuntime.kt:134`** TODO: API Recommendations for ServerRuntime.kt
- [ ] **`ServerRuntime.kt:135`** The openTelemetry property returns null by default with a TODO comment. This should either be implemented or the TODO removed if telemetry is truly optional. Document the expected behavior.
- [ ] **`ServerRuntime.kt:137`** No lifecycle methods for server startup/shutdown. Consider adding:
- [ ] **`ServerRuntime.kt:138`** suspend fun start()
- [ ] **`ServerRuntime.kt:139`** suspend fun stop()
- [ ] **`ServerRuntime.kt:140`** val isRunning: Boolean
- [ ] **`ServerRuntime.kt:141`** The Task.invoke() method doesn't provide any feedback about task execution status or errors. Consider returning a result or providing a callback mechanism for task completion/failure.
- [ ] **`ServerRuntime.kt:143`** sendWebSocketSubscriptionMessage doesn't document what happens if no connections are subscribed. Does it silently succeed? Document this behavior.
- [ ] **`ServerRuntime.kt:145`** No way to query the server state (number of active connections, running tasks, etc.). Consider adding health/metrics accessors for observability.
- [ ] **`ServerRuntime.kt:147`** The serverId and serverVersion have no validation. Consider validating that these are set properly during initialization or providing defaults.

### `core/src/main/kotlin/com/lightningkite/lightningserver/runtime/ServerRuntimeBase.kt`

- [ ] **`ServerRuntimeBase.kt:124`** TODO: API Recommendations for ServerRuntimeBase.kt
- [ ] **`ServerRuntimeBase.kt:125`** The runStartupTasks() method launches all tasks concurrently but doesn't limit concurrency. For servers with many startup tasks, this could create resource contention. Consider adding a concurrency limit or sequential execution option.
- [ ] **`ServerRuntimeBase.kt:128`** Startup task failures don't provide context about which task failed or the dependency chain. Consider wrapping exceptions with more context before rethrowing.
- [ ] **`ServerRuntimeBase.kt:130`** The lazy initialization of serialization could fail with an unclear error if the module returns null or throws. Consider eager initialization with better error messages.
- [ ] **`ServerRuntimeBase.kt:132`** Settings are automatically augmented with system settings (general, secret, telemetry, logging). If user code defines settings with the same names, they'll be silently overridden by the toSet(). Consider detecting conflicts and throwing an error, or documenting the override behavior.
- [ ] **`ServerRuntimeBase.kt:135`** SharedResources is created but never cleaned up in this base class. Subclasses should call sharedResources.close() on shutdown, but there's no enforcement. Consider adding a cleanup method.
- [ ] **`ServerRuntimeBase.kt:137`** The settings list is deduplicated by toSet() but ServerSetting equality is based on object identity (data class), so settings with the same name but different instances won't be deduplicated. This could lead to duplicate settings. Consider using distinctBy { it.name }.

### `core/src/main/kotlin/com/lightningkite/lightningserver/runtime/implementationHelpers.kt`

- [ ] **`implementationHelpers.kt:401`** TODO: API Recommendations for implementationHelpers.kt
- [ ] **`implementationHelpers.kt:402`** The handle() function is extremely complex (160+ lines) with multiple responsibilities: routing, compression, HEAD/OPTIONS handling, trailing slash redirects, exception handling. Consider breaking into smaller, testable functions.
- [ ] **`implementationHelpers.kt:405`** GZIP compression logic has magic numbers (256 bytes, 1024 bytes) without constants. Define these as named constants with documentation explaining the thresholds.
- [ ] **`implementationHelpers.kt:407`** The compression denylist (images, videos, fonts, archives) is hardcoded. Consider making this configurable via settings for applications with different compression needs.
- [ ] **`implementationHelpers.kt:409`** The automatic HEAD support silently falls back to GET. This could be surprising and cause unnecessary computation for expensive GET handlers. Document this behavior clearly or add a way to opt out.
- [ ] **`implementationHelpers.kt:412`** Trailing slash redirect uses PathSegments.toString() which may not preserve query parameters or fragments. Verify this behavior and document it.
- [ ] **`implementationHelpers.kt:414`** The exception handler itself can throw exceptions (catch block line 152-163), but those are caught and return a generic 500 with no logging. The error is silently swallowed.
- [ ] **`implementationHelpers.kt:416`** Compression for Data.Sink and Data.Source always returns `true` for compressed flag even if GZIP might fail or produce larger output. Consider checking actual compression ratio.
- [ ] **`implementationHelpers.kt:418`** The telemetry span names use different formats: "http.route" vs "WEBSOCKET.WILLCONNECT". Standardize naming conventions for consistency.
- [ ] **`implementationHelpers.kt:420`** The *WithMetrics functions are public but marked as internal in some cases with @PublishedApi. Clarify the intended visibility and usage patterns.
- [ ] **`implementationHelpers.kt:422`** UnregisteredException provides minimal context - just "Item $item is unregistered". Consider adding which server it was looked up in, or suggestions for common mistakes.

### `core/src/main/kotlin/com/lightningkite/lightningserver/AnonType.kt`

- [ ] **`AnonType.kt:114`** TODO: API Recommendations
- [ ] **`AnonType.kt:115`** Consider adding a method to check if the value has been deserialized without triggering deserialization
- [ ] **`AnonType.kt:116`** Thread safety: Consider whether concurrent access scenarios need to be documented or handled

### `core/src/main/kotlin/com/lightningkite/lightningserver/websockets/WebSocket.kt`

- [ ] **`WebSocket.kt:171`** TODO: API Recommendations for WebSocket.kt
- [ ] **`WebSocket.kt:172`** WebSocketTopic constructor is internal but there's no public factory method. How do users create topics? Document the intended creation mechanism.
- [ ] **`WebSocket.kt:174`** The queueStateUpdate() function has no documentation on when queued updates are applied, ordering guarantees, or what happens if the connection closes before updates are applied.
- [ ] **`WebSocket.kt:176`** No way to query current subscriptions for a connection. Adding a `val subscriptions: Set<WebSocketSubscriptionRequest<*, *>>` would be useful for debugging and state inspection.
- [ ] **`WebSocket.kt:178`** WebSocketConnection extends ServerRuntime which means every connection has its own runtime context. Document how this relates to the parent server runtime and if there are any scoping implications.
- [ ] **`WebSocket.kt:180`** The subscribe/unsubscribe operations don't return success/failure. If a topic doesn't exist or subscription fails, how does the caller know? Consider returning Boolean or throwing exceptions.
- [ ] **`WebSocket.kt:182`** No ping/pong support exposed in the API. WebSocket implementations typically need this for connection keepalive. Consider adding automatic ping or exposing manual ping control.
- [ ] **`WebSocket.kt:184`** The currentState property could be stale if queueStateUpdate is used. Document the consistency model (eventual consistency? last-write-wins?).

### `core/src/main/kotlin/com/lightningkite/lightningserver/shortcuts.kt`

- [ ] **`shortcuts.kt:204`** TODO: API Recommendations
- [ ] **`shortcuts.kt:205`** Consider removing or uncommenting the commented-out json() functions, or document why they're commented
- [ ] **`shortcuts.kt:206`** The path() function should handle the case where fileMetadataOrNull returns null more gracefully
- [ ] **`shortcuts.kt:207`** Consider adding HttpResponse.Companion.json() as an active function (currently commented out)
- [ ] **`shortcuts.kt:208`** Consider standardizing parameter order across similar functions (some have headers before body params, some after)

### `core/src/main/kotlin/com/lightningkite/lightningserver/definition/ScheduledTask.kt`

- [ ] **`ScheduledTask.kt:138`** TODO: API Recommendations for ScheduledTask.kt
- [ ] **`ScheduledTask.kt:139`** Add support for missed execution handling:
- [ ] **`ScheduledTask.kt:140`** enum class MissedExecutionBehavior { SKIP, RUN_IMMEDIATELY, RUN_ALL_MISSED }
- [ ] **`ScheduledTask.kt:141`** val missedExecutionBehavior: MissedExecutionBehavior This is important when server is down during scheduled time.
- [ ] **`ScheduledTask.kt:143`** Add execution tracking/history:
- [ ] **`ScheduledTask.kt:144`** val lastExecutionTime: Instant?
- [ ] **`ScheduledTask.kt:145`** val nextExecutionTime: Instant?
- [ ] **`ScheduledTask.kt:146`** val executionCount: Long
- [ ] **`ScheduledTask.kt:147`** Consider adding conditional execution:
- [ ] **`ScheduledTask.kt:148`** suspend fun shouldExecute(): Boolean Allows tasks to check conditions before running.
- [ ] **`ScheduledTask.kt:150`** Add lifecycle hooks for monitoring:
- [ ] **`ScheduledTask.kt:151`** suspend fun onScheduled()
- [ ] **`ScheduledTask.kt:152`** suspend fun onSkipped(reason: String)
- [ ] **`ScheduledTask.kt:153`** Add support for exclusive execution to prevent overlapping runs:
- [ ] **`ScheduledTask.kt:154`** val allowConcurrent: Boolean = false

### `core/src/main/kotlin/com/lightningkite/lightningserver/definition/ServerSetting.kt`

- [ ] **`ServerSetting.kt:341`** TODO: API Recommendations for ServerSetting.kt
- [ ] **`ServerSetting.kt:342`** **THREAD SAFETY**: The Cached implementations use mutable var without synchronization. Document that these are not thread-safe, or add synchronization for concurrent access. In a typical server, settings are initialized once during startup, but this should be documented.
- [ ] **`ServerSetting.kt:345`** The default instructions "No instructions" is not helpful. Consider making instructions a required parameter or using a more descriptive default like "No configuration instructions provided".
- [ ] **`ServerSetting.kt:347`** Add validation support to ServerSetting to catch invalid configurations early:
- [ ] **`ServerSetting.kt:348`** fun validate(setting: SETTING): List<String> // Returns validation errors
- [ ] **`ServerSetting.kt:349`** Consider adding a ServerSetting.lazy() variant that defers initialization until first use rather than during settings loading. Useful for optional services.
- [ ] **`ServerSetting.kt:351`** The NullWrapper is used to cache nullable values, but the pattern could be clearer. Consider using a sealed class: sealed class CacheState<out T> { object Empty, data class Filled<T>(val value: T) }
- [ ] **`ServerSetting.kt:353`** Add a way to observe setting changes for hot-reloading:
- [ ] **`ServerSetting.kt:354`** interface SettingObserver<T> { fun onSettingChanged(old: T, new: T) }

### `core/src/main/kotlin/com/lightningkite/lightningserver/definition/Task.kt`

- [ ] **`Task.kt:93`** TODO: API Recommendations for Task.kt
- [ ] **`Task.kt:94`** The launch() extension calls invoke() on the Task, but Task doesn't define an invoke operator. This appears to be calling server.invoke(input) which suggests there's runtime lookup logic. Document this behavior or make the invocation path more explicit.
- [ ] **`Task.kt:97`** Add support for task retries on failure:
- [ ] **`Task.kt:98`** val maxRetries: Int
- [ ] **`Task.kt:99`** val retryDelay: Duration
- [ ] **`Task.kt:100`** fun shouldRetry(exception: Exception): Boolean
- [ ] **`Task.kt:101`** Consider adding task priority for queue-based execution:
- [ ] **`Task.kt:102`** enum class TaskPriority { LOW, NORMAL, HIGH, CRITICAL }
- [ ] **`Task.kt:103`** val priority: TaskPriority
- [ ] **`Task.kt:104`** Add lifecycle hooks for observability:
- [ ] **`Task.kt:105`** suspend fun onStart(input: INPUT)
- [ ] **`Task.kt:106`** suspend fun onComplete(input: INPUT)
- [ ] **`Task.kt:107`** suspend fun onFailure(input: INPUT, exception: Exception)
- [ ] **`Task.kt:108`** Add a way to cancel running tasks:
- [ ] **`Task.kt:109`** suspend fun cancel(reason: String)

### `core/src/main/kotlin/com/lightningkite/lightningserver/definition/StartupTask.kt`

- [ ] **`StartupTask.kt:105`** TODO: API Recommendations for StartupTask.kt
- [ ] **`StartupTask.kt:106`** Add failure handling options:
- [ ] **`StartupTask.kt:107`** enum class FailureBehavior { FAIL_STARTUP, LOG_AND_CONTINUE, RETRY }
- [ ] **`StartupTask.kt:108`** val failureBehavior: FailureBehavior Currently a failed startup task likely crashes the server.
- [ ] **`StartupTask.kt:110`** Add task naming for better logging/debugging:
- [ ] **`StartupTask.kt:111`** val name: String This would help identify which task failed during startup.
- [ ] **`StartupTask.kt:113`** Consider adding priority within the same dependency level:
- [ ] **`StartupTask.kt:114`** val priority: Int For tasks with no dependencies, determines execution order.
- [ ] **`StartupTask.kt:116`** Add lifecycle hooks:
- [ ] **`StartupTask.kt:117`** suspend fun onComplete()
- [ ] **`StartupTask.kt:118`** suspend fun onFailure(exception: Exception) For observability and cleanup.
- [ ] **`StartupTask.kt:120`** Consider adding conditional execution:
- [ ] **`StartupTask.kt:121`** suspend fun shouldExecute(): Boolean Allows skipping tasks based on environment or configuration.

### `core/src/main/kotlin/com/lightningkite/lightningserver/http/HttpHandler.kt`

- [ ] **`HttpHandler.kt:78`** TODO: API Recommendations for HttpHandler.kt
- [ ] **`HttpHandler.kt:79`** Consider adding timeout validation to prevent nonsensical values:
- [ ] **`HttpHandler.kt:80`** Negative durations
- [ ] **`HttpHandler.kt:81`** Excessively long timeouts (e.g., > 10 minutes for HTTP handlers)
- [ ] **`HttpHandler.kt:82`** Add a mechanism to observe or intercept timeouts for logging/metrics:
- [ ] **`HttpHandler.kt:83`** onTimeout: ((HttpRequest<PATH>) -> Unit)? parameter
- [ ] **`HttpHandler.kt:84`** Consider providing common handler compositions:
- [ ] **`HttpHandler.kt:85`** fun <PATH> cached(handler: HttpHandler<PATH>): HttpHandler<PATH>
- [ ] **`HttpHandler.kt:86`** fun <PATH> withRetry(handler: HttpHandler<PATH>): HttpHandler<PATH>
- [ ] **`HttpHandler.kt:87`** Add documentation about what happens when a timeout occurs:
- [ ] **`HttpHandler.kt:88`** Is an exception thrown?
- [ ] **`HttpHandler.kt:89`** What response is sent to the client?
- [ ] **`HttpHandler.kt:90`** Are resources cleaned up properly?

### `core/src/main/kotlin/com/lightningkite/lightningserver/http/ExceptionHttpHandler.kt`

- [ ] **`ExceptionHttpHandler.kt:59`** TODO: API Recommendations for ExceptionHttpHandler.kt
- [ ] **`ExceptionHttpHandler.kt:60`** Add lifecycle hooks for exception logging/monitoring before response generation:
- [ ] **`ExceptionHttpHandler.kt:61`** fun onException(request: HttpRequest<PathSpec>, exception: Exception) This would allow centralized error tracking without duplicating response logic.
- [ ] **`ExceptionHttpHandler.kt:63`** Consider supporting exception handler chains similar to interceptors:
- [ ] **`ExceptionHttpHandler.kt:64`** Allow multiple exception handlers to try handling an exception
- [ ] **`ExceptionHttpHandler.kt:65`** Fall back to next handler if one returns null
- [ ] **`ExceptionHttpHandler.kt:66`** Add a way to provide context-specific error details based on the exception type:
- [ ] **`ExceptionHttpHandler.kt:67`** Interface could include error codes, user-friendly messages, etc.
- [ ] **`ExceptionHttpHandler.kt:68`** The timeout applies to exception handling but what happens if handling times out? Document the fallback behavior or add a simple emergency handler.

### `core/src/main/kotlin/com/lightningkite/lightningserver/http/HttpStatus.kt`

- [ ] **`HttpStatus.kt:147`** TODO: API Recommendations for HttpStatus.kt
- [ ] **`HttpStatus.kt:148`** Add convenience properties for status code categories:
- [ ] **`HttpStatus.kt:149`** val isInformational: Boolean (1xx)
- [ ] **`HttpStatus.kt:150`** val isRedirection: Boolean (3xx)
- [ ] **`HttpStatus.kt:151`** val isClientError: Boolean (4xx)
- [ ] **`HttpStatus.kt:152`** val isServerError: Boolean (5xx)
- [ ] **`HttpStatus.kt:153`** Missing some common status codes:
- [ ] **`HttpStatus.kt:154`** 418 I'm a teapot (often used for testing/jokes)
- [ ] **`HttpStatus.kt:155`** 451 Unavailable For Legal Reasons
- [ ] **`HttpStatus.kt:156`** 425 Too Early
- [ ] **`HttpStatus.kt:157`** Consider adding a description property that returns the standard text for the code:
- [ ] **`HttpStatus.kt:158`** val description: String?
- [ ] **`HttpStatus.kt:159`** Add validation to ensure status codes are in valid range (100-599):
- [ ] **`HttpStatus.kt:160`** init { require(code in 100..599) { "Invalid HTTP status code: $code" } }

### `core/src/main/kotlin/com/lightningkite/lightningserver/http/HttpRequest.kt`

- [ ] **`HttpRequest.kt:85`** TODO: API Recommendations for HttpRequest.kt
- [ ] **`HttpRequest.kt:86`** The @Transient annotation on body means it won't be serialized, which could cause issues if someone tries to serialize a request. Consider documenting this limitation or providing a warning in the class KDoc.
- [ ] **`HttpRequest.kt:89`** Add convenience extension functions for common body operations:
- [ ] **`HttpRequest.kt:90`** suspend fun HttpRequest<*>.textBody(): String
- [ ] **`HttpRequest.kt:91`** suspend fun <T> HttpRequest<*>.jsonBody(serializer: KSerializer<T>): T
- [ ] **`HttpRequest.kt:92`** fun HttpRequest<*>.requireBody(): TypedData (throws if null)
- [ ] **`HttpRequest.kt:93`** Consider adding a toString() method that doesn't include the full body content for logging purposes (to avoid logging sensitive data or large payloads).
- [ ] **`HttpRequest.kt:95`** The cache is mutable but shared across request copies, which could lead to unexpected behavior if not documented clearly. Consider adding a warning about cache sharing in copyWithNewPathType documentation.

### `core/src/main/kotlin/com/lightningkite/lightningserver/http/HttpResponse.kt`

- [ ] **`HttpResponse.kt:46`** TODO: API Recommendations for HttpResponse.kt
- [ ] **`HttpResponse.kt:47`** The default status calculation in the constructor parameter could be surprising when explicitly setting status with a body. Consider documenting this behavior more clearly or requiring explicit status when body is provided.
- [ ] **`HttpResponse.kt:50`** Add convenience methods directly on HttpResponse for common modifications:
- [ ] **`HttpResponse.kt:51`** fun withHeader(name: String, value: String): HttpResponse
- [ ] **`HttpResponse.kt:52`** fun withStatus(status: HttpStatus): HttpResponse
- [ ] **`HttpResponse.kt:53`** fun withCookie(cookie: Cookie): HttpResponse
- [ ] **`HttpResponse.kt:54`** Consider validation to catch common mistakes:
- [ ] **`HttpResponse.kt:55`** Warn/error if body is present for 204 No Content
- [ ] **`HttpResponse.kt:56`** Warn/error if body is missing for 200 OK
- [ ] **`HttpResponse.kt:57`** Validate that redirect status codes (3xx) have Location header
- [ ] **`HttpResponse.kt:58`** Add a method to check if the response is cacheable based on status and headers:
- [ ] **`HttpResponse.kt:59`** val cacheable: Boolean
- [ ] **`HttpResponse.kt:60`** The companion object is empty - consider moving the extension functions (plainText, json, etc.) to be members of the companion object for better discoverability via autocomplete.

### `core/src/main/kotlin/com/lightningkite/lightningserver/http/parse.kt`

- [ ] **`parse.kt:200`** TODO: API Recommendations and Issues for parse.kt
- [ ] **`parse.kt:201`** **ISSUE**: PathAndParams.parse() doesn't handle paths with multiple '?' correctly. Only splits on first '?' but subsequent '?' in query string will be kept as-is. This is probably fine but worth documenting.
- [ ] **`parse.kt:204`** **ISSUE**: PathSegments.parse() with an empty string results in a single empty segment [""] instead of an empty list. This is because "".split("/") returns [""]. Consider: if (path.isEmpty()) return EMPTY
- [ ] **`parse.kt:207`** **ISSUE**: QueryParameters.parse() with an empty string results in one entry with empty key and value instead of EMPTY. Consider: if (path.isEmpty()) return EMPTY
- [ ] **`parse.kt:209`** **CRITICAL TODO**: The pathHack() function is marked as a "fugly hack" and should be removed. This appears to be a workaround for WebSocket authentication. Document the proper fix.
- [ ] **`parse.kt:211`** QueryParameters could benefit from a getAll(key: String): List<String> method for retrieving all values for a given key (e.g., multiple tags).
- [ ] **`parse.kt:215`** PathSegments removes leading slash but not trailing slash. A path like "/api/users/" will have an empty string as the last segment. Document or handle this behavior.
- [ ] **`parse.kt:217`** Consider adding a merge/combine method for QueryParameters to easily combine query strings from different sources.
- [ ] **`parse.kt:219`** The toString() implementation uses deprecated URLEncoder.encode(String, Charset). While it still works, consider updating to URLEncoder.encode(String, String) with "UTF-8".

### `core/src/main/kotlin/com/lightningkite/lightningserver/http/DefaultExceptionHttpHandler.kt`

- [ ] **`DefaultExceptionHttpHandler.kt:60`** TODO: API Recommendations for DefaultExceptionHttpHandler.kt
- [ ] **`DefaultExceptionHttpHandler.kt:61`** The handler doesn't log exceptions - consider adding logging here for all unhandled exceptions to ensure errors are captured even if monitoring/logging interceptors aren't configured.
- [ ] **`DefaultExceptionHttpHandler.kt:63`** Stack traces in debug mode could expose sensitive information (file paths, internal logic). Consider sanitizing or limiting stack trace depth even in debug mode.
- [ ] **`DefaultExceptionHttpHandler.kt:65`** The generic error message in production ("An unknown error occurred") isn't helpful for debugging. Consider including a correlation ID that maps to server-side logs.
- [ ] **`DefaultExceptionHttpHandler.kt:67`** No special handling for common exception types like IllegalArgumentException, NullPointerException. These could be mapped to 400 Bad Request instead of 500 Internal Server Error when appropriate.
- [ ] **`DefaultExceptionHttpHandler.kt:69`** The toTypedData call could fail if the Accept header specifies an unsupported format. Consider wrapping this in a try-catch and falling back to JSON or plain text.

### `core/src/main/kotlin/com/lightningkite/lightningserver/http/HttpHeaderValue.kt`

- [ ] **`HttpHeaderValue.kt:96`** TODO: API Recommendations for HttpHeaderValue.kt
- [ ] **`HttpHeaderValue.kt:97`** The parsing doesn't handle quoted values in parameters (common in headers like Content-Disposition). For example: filename="file; with; semicolons.txt" would be incorrectly parsed. Consider supporting RFC 2616 quoted-string format.
- [ ] **`HttpHeaderValue.kt:100`** The parseCookies function may not correctly handle cookies without values (just the name). substringAfter('=', "") returns empty string but should probably check if '=' exists.
- [ ] **`HttpHeaderValue.kt:102`** Parameter names and values should preserve case sensitivity, but the implementation doesn't document this. Some headers have case-sensitive parameters.
- [ ] **`HttpHeaderValue.kt:104`** The parse() method could fail silently on malformed input. Consider throwing an exception or returning a Result type for invalid header values.
- [ ] **`HttpHeaderValue.kt:106`** Add convenience methods for common parameter access patterns:
- [ ] **`HttpHeaderValue.kt:107`** fun getParameter(name: String, ignoreCase: Boolean = false): String?
- [ ] **`HttpHeaderValue.kt:108`** fun hasParameter(name: String): Boolean
- [ ] **`HttpHeaderValue.kt:109`** The emptyMap import from kotlinx.html is unusual - should use kotlin.collections.emptyMap()

### `core/src/main/kotlin/com/lightningkite/lightningserver/http/HttpEndpoint.kt`

- [ ] **`HttpEndpoint.kt:51`** TODO: API Recommendations for HttpEndpoint.kt
- [ ] **`HttpEndpoint.kt:52`** Consider adding extension properties for less common HTTP methods if needed:
- [ ] **`HttpEndpoint.kt:53`** TRACE, CONNECT (though these are rarely used in REST APIs)
- [ ] **`HttpEndpoint.kt:54`** The extension properties create new HttpEndpoint instances on every access. For frequently accessed endpoints, consider caching or using lazy properties.
- [ ] **`HttpEndpoint.kt:56`** Add a method to check if a path matches this endpoint's pattern:
- [ ] **`HttpEndpoint.kt:57`** fun matches(method: HttpMethod, path: PathSegments): Boolean

### `core/src/main/kotlin/com/lightningkite/lightningserver/http/HttpHeaders.kt`

- [ ] **`HttpHeaders.kt:402`** TODO: API Improvement Recommendations for HttpHeaders.kt:
- [ ] **`HttpHeaders.kt:403`** HttpHeaders - Add typed accessors for common headers
- [ ] **`HttpHeaders.kt:404`** Currently users must manually parse most headers
- [ ] **`HttpHeaders.kt:405`** Consider adding: authorization, userAgent, referer, etc. as properties like contentType
- [ ] **`HttpHeaders.kt:406`** Example: val authorization: String? - parsed from Authorization header
- [ ] **`HttpHeaders.kt:407`** HttpHeaders.cookies - Inconsistent with setCookie
- [ ] **`HttpHeaders.kt:408`** cookies returns Map<String, String> for reading Cookie header
- [ ] **`HttpHeaders.kt:409`** setCookie sets Set-Cookie header with different structure
- [ ] **`HttpHeaders.kt:410`** Consider adding setResponseCookie and getCookies/getResponseCookies for clarity

### `core/src/main/kotlin/com/lightningkite/lightningserver/http/HttpInterceptor.kt`

- [ ] **`HttpInterceptor.kt:120`** TODO: API Recommendations for HttpInterceptor.kt
- [ ] **`HttpInterceptor.kt:121`** Add a priority or ordering mechanism for interceptors to ensure correct execution order (e.g., authentication should run before authorization). Currently order depends on installation order which is implicit.
- [ ] **`HttpInterceptor.kt:124`** Consider adding lifecycle hooks for interceptors:
- [ ] **`HttpInterceptor.kt:125`** fun onServerStart(runtime: ServerRuntime)
- [ ] **`HttpInterceptor.kt:126`** fun onServerStop(runtime: ServerRuntime) This would allow interceptors to initialize/cleanup resources.
- [ ] **`HttpInterceptor.kt:128`** The compileAndInstrument logic is complex and uses idx checking that's fragile. The comment "will start at 1" suggests the logic is not immediately obvious. Consider simplifying or adding more detailed comments about why idx==1 is special.
- [ ] **`HttpInterceptor.kt:131`** Add a way to skip remaining interceptors and jump directly to the handler:
- [ ] **`HttpInterceptor.kt:132`** This would be useful for caching interceptors that want to return cached responses without executing authentication, etc.
- [ ] **`HttpInterceptor.kt:134`** Consider adding typed metadata that can be attached to requests by interceptors for downstream interceptors/handlers to use (e.g., authenticated user, rate limit info). Currently this must be done via the SerializableCache which requires serialization.
- [ ] **`HttpInterceptor.kt:137`** The fun interface is convenient but limits having state in interceptors unless you use a class. Document the pattern for stateful interceptors clearly.

### `core/src/main/kotlin/com/lightningkite/lightningserver/cors/CorsSettings.kt`

- [ ] **`CorsSettings.kt:102`** TODO: API Recommendations

### `core/src/main/kotlin/com/lightningkite/lightningserver/cors/CorsInterceptor.kt`

- [ ] **`CorsInterceptor.kt:184`** TODO: API Recommendations

### `core/src/main/kotlin/com/lightningkite/lightningserver/data/SerializableCache.kt`

- [ ] **`SerializableCache.kt:333`** TODO: API Recommendations for SerializableCache.kt
- [ ] **`SerializableCache.kt:334`** Add thread-safety documentation - is this cache safe for concurrent access? If not, consider adding synchronization or documenting usage constraints.
- [ ] **`SerializableCache.kt:336`** Consider adding a remove() method to explicitly invalidate cache entries:
- [ ] **`SerializableCache.kt:337`** fun remove(key: Key<*>): Boolean
- [ ] **`SerializableCache.kt:338`** Add bulk operations for efficiency:
- [ ] **`SerializableCache.kt:339`** fun removeAll(predicate: (String) -> Boolean)
- [ ] **`SerializableCache.kt:340`** fun getAll(keys: List<Key<*>>): Map<String, Any?>
- [ ] **`SerializableCache.kt:341`** The equals() implementation could be expensive for large caches due to contentEquals on every ByteArray. Consider caching hash codes or using a different approach.
- [ ] **`SerializableCache.kt:343`** Add size/statistics methods to help with debugging and monitoring:
- [ ] **`SerializableCache.kt:344`** val size: Int (number of cached entries)
- [ ] **`SerializableCache.kt:345`** val memorySize: Long (approximate size in bytes)
- [ ] **`SerializableCache.kt:346`** fun getStats(): CacheStats (hit/miss ratios, etc.)
- [ ] **`SerializableCache.kt:347`** Consider adding a max size limit with eviction policy (LRU, LFU) to prevent unbounded growth in long-running applications.
- [ ] **`SerializableCache.kt:349`** The Key interface could benefit from a validation method to ensure id uniqueness at compile time or startup rather than at runtime during retrieval.
- [ ] **`SerializableCache.kt:351`** Add a typed CalculatingKey factory method similar to the Key factory for consistency

### `core/src/main/kotlin/com/lightningkite/lightningserver/data/Expiring.kt`

- [ ] **`Expiring.kt:60`** TODO: API Recommendations for Expiring.kt
- [ ] **`Expiring.kt:61`** Add a method to refresh/extend expiration:
- [ ] **`Expiring.kt:62`** context(server: ServerRuntime) fun extend(by: Duration): Expiring<T>
- [ ] **`Expiring.kt:63`** Consider adding a timeRemaining property:
- [ ] **`Expiring.kt:64`** context(server: ServerRuntime) val timeRemaining: Duration? Returns null if never expires, negative if expired, positive if time remaining
- [ ] **`Expiring.kt:66`** Add a non-context version that accepts an Instant directly for testing:
- [ ] **`Expiring.kt:67`** fun isExpired(at: Instant): Boolean

### `core/src/main/kotlin/com/lightningkite/lightningserver/data/KFile.ext.kt`

- [ ] **`KFile.ext.kt:34`** TODO: API Recommendations for KFile.ext.kt
- [ ] **`KFile.ext.kt:35`** Consider adding path validation or normalization to handle edge cases like:
- [ ] **`KFile.ext.kt:36`** Relative vs absolute paths
- [ ] **`KFile.ext.kt:37`** Path separators on different platforms
- [ ] **`KFile.ext.kt:38`** Symlinks and canonical paths
- [ ] **`KFile.ext.kt:39`** Add conversion functions for other common Java file types if needed:
- [ ] **`KFile.ext.kt:40`** java.nio.file.Path.toKFile()
- [ ] **`KFile.ext.kt:41`** KFile.toNioPath()

### `core/src/main/kotlin/com/lightningkite/lightningserver/data/Request.kt`

- [ ] **`Request.kt:65`** TODO: API Recommendations for Request.kt
- [ ] **`Request.kt:66`** Consider adding convenience accessors for common operations:
- [ ] **`Request.kt:67`** val fullUrl: String (protocol + domain + path)
- [ ] **`Request.kt:68`** val isSecure: Boolean (protocol == "https")
- [ ] **`Request.kt:69`** val origin: String (protocol + domain)
- [ ] **`Request.kt:70`** The sourceIp field doesn't account for proxies/load balancers. Consider adding realIp that checks X-Forwarded-For or similar headers.
- [ ] **`Request.kt:72`** Add utility methods for common header checks:
- [ ] **`Request.kt:73`** fun accepts(mediaType: MediaType): Boolean
- [ ] **`Request.kt:74`** fun isAjax(): Boolean (X-Requested-With header check)
- [ ] **`Request.kt:75`** Consider adding a typed body accessor pattern to avoid repetitive deserialization code
- [ ] **`Request.kt:76`** The Request class could benefit from a toString() implementation for logging/debugging

### `core/src/main/kotlin/com/lightningkite/lightningserver/data/LongBits.kt`

- [ ] **`LongBits.kt:141`** TODO: API Recommendations for LongBits.kt
- [ ] **`LongBits.kt:142`** Consider adding a first() extension that throws if empty (similar to Iterable.first()):
- [ ] **`LongBits.kt:143`** Currently lowestIncluding(0) returns -1 if empty
- [ ] **`LongBits.kt:144`** Add a companion object parse() method to parse from string format:
- [ ] **`LongBits.kt:145`** LongBits.parse("0,5,10-15") for creating from string representation

### `core/src/main/kotlin/com/lightningkite/lightningserver/data/Cron.kt`

- [ ] **`Cron.kt:363`** TODO: API Recommendations for Cron.kt
- [ ] **`Cron.kt:364`** Complete implementation of advanced day-of-month features:
- [ ] **`Cron.kt:365`** CronDayOfMonth.Last (last day of month)
- [ ] **`Cron.kt:366`** CronDayOfMonth.NearestWeekday (nearest weekday to a given day)
- [ ] **`Cron.kt:367`** CronDayOfWeek.last and CronDayOfWeek.recurrence (nth occurrence patterns)
- [ ] **`Cron.kt:368`** Add cron string parsing functionality:
- [ ] **`Cron.kt:369`** CronPattern.parse(cronString: String): CronPattern This would allow users to create patterns from standard cron expressions
- [ ] **`Cron.kt:371`** Add validation for day-of-month values (1-31) in CronDayOfMonth.Day constructor to fail fast on invalid input rather than at pattern execution time
- [ ] **`Cron.kt:373`** Consider adding a nextOccurrence() or getNextRun() method that doesn't modify the receiver datetime, making the API more explicit:
- [ ] **`Cron.kt:375`** fun CronPattern.nextOccurrence(after: LocalDateTime): LocalDateTime
- [ ] **`Cron.kt:376`** Add timezone-aware scheduling support by accepting Instant instead of just LocalDateTime, to handle DST transitions correctly
- [ ] **`Cron.kt:378`** Consider adding a method to list next N occurrences:
- [ ] **`Cron.kt:379`** fun CronPattern.nextOccurrences(after: LocalDateTime, count: Int): List<LocalDateTime>

## Module: core-shared

### `core-shared/src/commonMain/kotlin/com/lightningkite/lightningserver/LSError.kt`

- [ ] **`LSError.kt:70`** TODO: API Recommendations
- [ ] **`LSError.kt:71`** LSError: Consider adding factory methods for common error types:
- [ ] **`LSError.kt:72`** LSError.notFound(message: String, detail: String = "not-found")
- [ ] **`LSError.kt:73`** LSError.badRequest(message: String, detail: String = "bad-request")
- [ ] **`LSError.kt:74`** LSError.unauthorized(message: String, detail: String = "unauthorized") This would make error creation more consistent and less error-prone.
- [ ] **`LSError.kt:76`** LSError: The 'data' field is a String but typically contains JSON. Consider:
- [ ] **`LSError.kt:77`** Making it more type-safe with a generic parameter or JsonElement type
- [ ] **`LSError.kt:78`** Adding a helper method: inline fun <reified T> dataAs(): T to deserialize
- [ ] **`LSError.kt:79`** Documenting that it should be valid JSON
- [ ] **`LSError.kt:80`** LSError: Consider adding validation that 'http' is a valid HTTP status code (100-599)
- [ ] **`LSError.kt:81`** MultiplexMessage: The mutual exclusivity of 'data' and 'error' is mentioned in docs but not enforced. Consider:
- [ ] **`LSError.kt:83`** Using a sealed interface with DataMessage and ErrorMessage subclasses
- [ ] **`LSError.kt:84`** Adding an init block that validates only one is set
- [ ] **`LSError.kt:85`** Using a when-exhaustive pattern helper
- [ ] **`LSError.kt:86`** MultiplexMessage: Consider adding validation that 'path' and 'queryParams' are only present when 'start' is true (or document if other combinations are valid)
- [ ] **`LSError.kt:88`** Both classes: Consider adding convenience methods for common patterns:
- [ ] **`LSError.kt:89`** LSError.isClientError: Boolean (http in 400..499)
- [ ] **`LSError.kt:90`** LSError.isServerError: Boolean (http in 500..599)
- [ ] **`LSError.kt:91`** MultiplexMessage.isControl: Boolean (start || end)

### `core-shared/src/commonMain/kotlin/com/lightningkite/lightningserver/HttpMethod.kt`

- [ ] **`HttpMethod.kt:66`** TODO: API Recommendations
- [ ] **`HttpMethod.kt:67`** Consider adding an equality check method that's case-insensitive for robustness: fun equalsIgnoreCase(other: HttpMethod): Boolean HTTP methods should be case-sensitive per RFC 7231, but defensive parsing could be valuable.
- [ ] **`HttpMethod.kt:70`** Add a validation method to check if a method is standard/safe: val isStandard: Boolean (checks if it's one of the companion object constants) val isSafe: Boolean (true for GET, HEAD, OPTIONS - methods that don't modify state) val isIdempotent: Boolean (true for GET, PUT, DELETE, HEAD, OPTIONS)
- [ ] **`HttpMethod.kt:74`** Consider adding a factory method that validates and normalizes strings: fun fromString(method: String): HttpMethod that uppercases the input This would prevent accidental lowercase method names.
- [ ] **`HttpMethod.kt:77`** The private constructor means users can't create custom methods. If this is intentional, document it clearly. If custom methods should be supported, make the constructor public and possibly add validation.

## Module: engine-jdk-server

### `engine-jdk-server/src/main/kotlin/com/lightningkite/lightningserver/engine/jdk/JdkEngine.kt`

- [ ] **`JdkEngine.kt:241`** TODO: API Recommendations
- [ ] **`JdkEngine.kt:242`** The DEFAULT_BUFFER constant is defined but never used - remove or implement buffering
- [ ] **`JdkEngine.kt:243`** Consider adding graceful shutdown support (currently runs indefinitely)
- [ ] **`JdkEngine.kt:244`** The error handling in start() catches all exceptions and sends generic 500 - consider more specific error responses based on exception type
- [ ] **`JdkEngine.kt:246`** The adapt() function splits comma-separated headers, but some headers (like Set-Cookie) shouldn't be split. Consider header-specific handling.
- [ ] **`JdkEngine.kt:248`** Document the WebSocket limitation more prominently (e.g., throw exception if WebSocket endpoints are registered)

## Module: engine-ktor

### `engine-ktor/src/main/kotlin/com/lightningkite/lightningserver/engine/ktor/extensions.kt`

- [ ] **`extensions.kt:122`** TODO: API Recommendations
- [ ] **`extensions.kt:123`** Complete the MultiPart support implementation or remove the commented code
- [ ] **`extensions.kt:124`** The Headers.adapt() function splits comma-separated values, but some headers (like Set-Cookie) shouldn't be split this way. Consider header-specific handling.
- [ ] **`extensions.kt:126`** Consider adding error handling for invalid content types in adapt()
- [ ] **`extensions.kt:127`** The typo "MutliPart" appears in comments - should be "MultiPart"

### `engine-ktor/src/main/kotlin/com/lightningkite/lightningserver/engine/ktor/KtorEngine.kt`

- [ ] **`KtorEngine.kt:321`** TODO: API Recommendations
- [ ] **`KtorEngine.kt:322`** The start() method uses runBlocking which could block the calling thread unexpectedly. Consider documenting this behavior or providing a suspending alternative.
- [ ] **`KtorEngine.kt:324`** The watchPaths parameter in embeddedServer is always empty - consider exposing this for development-time auto-reload functionality.
- [ ] **`KtorEngine.kt:326`** The realIpHeader warning logs but doesn't fail - consider documenting the security implications of a missing real IP header when behind a proxy.
- [ ] **`KtorEngine.kt:328`** Consider adding a graceful shutdown method that stops schedules and drains connections.
- [ ] **`KtorEngine.kt:329`** The WebSocket path is extracted from query parameter with a "pathHack" - this seems like a workaround that should be documented or cleaned up.

## Module: engine-netty

### `engine-netty/src/main/kotlin/com/lightningkite/lightningserver/engine/netty/NettyEngine.kt`

- [ ] **`NettyEngine.kt:652`** TODO: API Recommendations
- [ ] **`NettyEngine.kt:653`** Consider extracting magic numbers to named constants:
- [ ] **`NettyEngine.kt:654`** Idle timeout (120 seconds, line 156)
- [ ] **`NettyEngine.kt:655`** Write buffer water marks (32 KiB / 64 KiB, line 145)
- [ ] **`NettyEngine.kt:656`** Boss thread count (1, line 96/102/108)
- [ ] **`NettyEngine.kt:657`** The toLightningHeaders() function splits comma-separated headers, but some headers (like Set-Cookie) shouldn't be split. Consider header-specific handling.
- [ ] **`NettyEngine.kt:659`** Consider adding metrics/telemetry for:
- [ ] **`NettyEngine.kt:660`** Active connection count
- [ ] **`NettyEngine.kt:661`** Request throughput
- [ ] **`NettyEngine.kt:662`** WebSocket connection count
- [ ] **`NettyEngine.kt:663`** Event loop queue depth
- [ ] **`NettyEngine.kt:664`** The toNettyResponse() function loads the entire response body into memory (line 489). Consider streaming support for large responses.
- [ ] **`NettyEngine.kt:666`** Consider making the idle timeout configurable via NettyRuntimeSettings instead of hardcoding to 120 seconds.
- [ ] **`NettyEngine.kt:667`** The error handling for WebSocket operations catches and ignores Throwables silently (line 387, 413). Consider adding logging or metrics for these failures.
- [ ] **`NettyEngine.kt:669`** Consider documenting the thread safety characteristics of currentState in LocalWebSocketConnection, as modifications are not synchronized.
- [ ] **`NettyEngine.kt:671`** The TypeRetriever class at the end appears unused in this file. Consider removing if not referenced elsewhere.

### `engine-netty/src/main/kotlin/com/lightningkite/lightningserver/engine/netty/NettyRuntimeSettings.kt`

- [ ] **`NettyRuntimeSettings.kt:52`** TODO: API Recommendations
- [ ] **`NettyRuntimeSettings.kt:53`** Consider validating workerThreads is positive when non-null
- [ ] **`NettyRuntimeSettings.kt:54`** Consider adding separate settings for boss thread count (currently hardcoded to 1)
- [ ] **`NettyRuntimeSettings.kt:55`** The backlog parameter uses DataSize but is converted to int - consider using Int directly for clarity
- [ ] **`NettyRuntimeSettings.kt:56`** Consider adding documentation about when to adjust recvBufBytes/sendBufBytes for performance tuning
- [ ] **`NettyRuntimeSettings.kt:57`** Consider adding idle timeout configuration (currently hardcoded to 120 seconds in NettyEngine)

## Module: media

### `media/src/main/kotlin/com/lightningkite/lightningserver/media/MediaPreviewOptions.kt`

- [ ] **`MediaPreviewOptions.kt:185`** TODO: API Recommendations
- [ ] **`MediaPreviewOptions.kt:186`** Consider validating that sizeInPixels is positive and forceRatio is positive in MediaPreviewOptions. Currently, negative or zero values could cause unexpected behavior.
- [ ] **`MediaPreviewOptions.kt:188`** Consider adding support for APNG and AVIF formats, which are becoming more common for web use.
- [ ] **`MediaPreviewOptions.kt:189`** The quality parameter could have better validation/documentation. Consider enforcing 0.0-1.0 range or documenting what happens with out-of-range values.
- [ ] **`MediaPreviewOptions.kt:191`** Consider adding a callback or progress indicator for long-running image processing operations.
- [ ] **`MediaPreviewOptions.kt:192`** The resizeToRatio extension function is called but not defined in this file. Consider documenting where it comes from or making it part of this API.
- [ ] **`MediaPreviewOptions.kt:194`** Consider adding an option to preserve EXIF metadata in the output file for cases where this is desired.

### `media/src/main/kotlin/com/lightningkite/lightningserver/media/processing.kt`

- [ ] **`processing.kt:244`** TODO: API Recommendations
- [ ] **`processing.kt:245`** Consider adding a hybrid approach: fast synchronous processing for small images, background queue for large ones, with a configurable size threshold.
- [ ] **`processing.kt:247`** The interceptImagesForProcessing methods process on every update, even if the file field didn't change. Consider optimizing to only process when the field actually changes.
- [ ] **`processing.kt:249`** Consider adding error handling and retry logic for failed image processing operations.
- [ ] **`processing.kt:250`** The naming distinction between processImagesInBackground and interceptImagesForProcessing could be clearer. Consider renaming to emphasize sync vs async (e.g., processImagesAsync and processImagesSync).
- [ ] **`processing.kt:253`** Consider adding telemetry/metrics for image processing operations (processing time, file sizes, success/failure rates) to help users optimize their preview configurations.
- [ ] **`processing.kt:255`** Consider allowing users to specify which operations to intercept (create only, update only, or both) for more granular control.

## Module: media-shared

### `media-shared/src/commonMain/kotlin/com/lightningkite/lightningserver/media/models.kt`

- [ ] **`models.kt:101`** TODO: API Recommendations
- [ ] **`models.kt:102`** Consider adding a `findBestPreview()` method that returns a single preview (or the original) rather than a sequence, as this is likely a common use case.
- [ ] **`models.kt:104`** The sorting penalty value (2000) in the `previews()` method is a magic number. Consider:
- [ ] **`models.kt:105`** Making it a configurable parameter with a sensible default
- [ ] **`models.kt:106`** Or documenting why 2000 was chosen
- [ ] **`models.kt:107`** Consider adding a `totalSize` property to ServerFileWithMetadata that includes the sum of all preview sizes, useful for storage management.
- [ ] **`models.kt:109`** The `previews()` method could benefit from accepting a lambda for custom sorting logic, allowing callers to define their own "best match" criteria.
- [ ] **`models.kt:111`** Consider adding validation that prevents width/height from being set on non-image files, or at minimum document the expected behavior.

## Module: notifications

### `notifications/src/main/kotlin/com/lightningkite/lightningserver/notifications/events/EventHandler.kt`

- [ ] **`EventHandler.kt:107`** TODO: API Recommendations for EventHandler.kt:
- [ ] **`EventHandler.kt:108`** Add helper for batch event processing if multiple events of the same type need to be launched together efficiently.

## Module: notifications-shared

### `notifications-shared/src/commonMain/kotlin/com/lightningkite/lightningserver/notifications/notificationModels.kt`

- [ ] **`notificationModels.kt:231`** TODO: API Recommendations for notificationModels.kt:
- [ ] **`notificationModels.kt:232`** Add a helper method to Frequency for common patterns:
- [ ] **`notificationModels.kt:233`** Frequency.disabled() or Frequency.never() to explicitly represent disabled channels
- [ ] **`notificationModels.kt:234`** Consider if null should represent "not configured" vs "disabled"
- [ ] **`notificationModels.kt:235`** Consider adding validation to Frequency:
- [ ] **`notificationModels.kt:236`** batchMinutes should probably have a minimum value (e.g., 1 minute)
- [ ] **`notificationModels.kt:237`** Maximum batch interval might be useful
- [ ] **`notificationModels.kt:238`** The Notification class could benefit from helper methods:
- [ ] **`notificationModels.kt:239`** isRead(): Boolean = read != null
- [ ] **`notificationModels.kt:240`** hasUnsentChannels(at: Instant): Boolean to check if any channel needs sending
- [ ] **`notificationModels.kt:241`** needsSending(at: Instant): Boolean to consolidate the private needsSending logic
- [ ] **`notificationModels.kt:242`** Consider adding a MarkAsReadModification or similar to make marking notifications read easier and more consistent across implementations.
- [ ] **`notificationModels.kt:244`** The IndexSet annotation only includes ["user", "sendAt"] but queries likely need to filter by sent status and sendAt together. Consider adding a composite index like ["user", "email.sent", "email.sendAt"] or similar for each channel.

## Module: sessions

### `sessions/src/main/kotlin/com/lightningkite/lightningserver/sessions/AuthEndpoints.kt`

- [ ] **`AuthEndpoints.kt:503`** TODO: API Recommendations
- [ ] **`AuthEndpoints.kt:504`** PROOF STRENGTH CONFIGURATION
- [ ] **`AuthEndpoints.kt:505`** Consider adding an endpoint to query global proof method information (strength values, descriptions)
- [ ] **`AuthEndpoints.kt:506`** This would help clients understand why they need multiple proofs
- [ ] **`AuthEndpoints.kt:507`** Example: GET /auth/proof-methods -> List<ProofMethodInfo>
- [ ] **`AuthEndpoints.kt:508`** SESSION MANAGEMENT IMPROVEMENTS
- [ ] **`AuthEndpoints.kt:509`** Add bulk session revocation: DELETE /sessions?all=true (logout from all devices except current)
- [ ] **`AuthEndpoints.kt:510`** Add session activity tracking: GET /sessions/{id}/activity to see last used time, IP, etc.
- [ ] **`AuthEndpoints.kt:511`** Consider adding session refresh endpoint separate from token refresh
- [ ] **`AuthEndpoints.kt:512`** PROGRESSIVE AUTHENTICATION FEEDBACK
- [ ] **`AuthEndpoints.kt:513`** The current design requires clients to submit all proofs at once
- [ ] **`AuthEndpoints.kt:514`** Consider supporting incremental proof submission where each call returns updated state
- [ ] **`AuthEndpoints.kt:515`** This would improve UX for step-by-step authentication flows
- [ ] **`AuthEndpoints.kt:516`** Example: POST /login/add-proof with state token to accumulate proofs across requests
- [ ] **`AuthEndpoints.kt:517`** PROOF METHOD DISCOVERY
- [ ] **`AuthEndpoints.kt:518`** Add endpoint for unauthenticated users to discover available proof methods for an identifier
- [ ] **`AuthEndpoints.kt:519`** Example: POST /auth/methods with {email: "user@example.com"} -> available methods
- [ ] **`AuthEndpoints.kt:520`** This helps clients show appropriate auth forms without prior knowledge
- [ ] **`AuthEndpoints.kt:521`** Security: Consider rate limiting and returning generic responses to prevent user enumeration
- [ ] **`AuthEndpoints.kt:522`** ERROR RESPONSES
- [ ] **`AuthEndpoints.kt:523`** Some errors expose detailed information that could aid attackers (e.g., "no user found")
- [ ] **`AuthEndpoints.kt:524`** Consider generic "authentication failed" responses for production security
- [ ] **`AuthEndpoints.kt:525`** Provide detailed errors only in development mode or to authenticated admins
- [ ] **`AuthEndpoints.kt:526`** PROOF EXPIRATION
- [ ] **`AuthEndpoints.kt:527`** The 1-hour default proof expiration may be too long for high-security contexts
- [ ] **`AuthEndpoints.kt:528`** Consider making expiration configurable per proof method (SMS code: 5 min, password: 1 hour)
- [ ] **`AuthEndpoints.kt:529`** Add endpoint to extend proof expiration for active authentication flows
- [ ] **`AuthEndpoints.kt:530`** NAMING CLARITY
- [ ] **`AuthEndpoints.kt:531`** "login2" is not intuitive - consider renaming to "login-advanced" or "login-with-options"
- [ ] **`AuthEndpoints.kt:532`** Consider deprecating "login" in favor of "login2" to reduce API surface area
- [ ] **`AuthEndpoints.kt:533`** Alternatively, merge both into one endpoint with optional parameters
- [ ] **`AuthEndpoints.kt:534`** WEBSOCKET SUPPORT
- [ ] **`AuthEndpoints.kt:535`** For real-time authentication flows (especially for device-to-device auth)
- [ ] **`AuthEndpoints.kt:536`** Consider WebSocket endpoint that streams authentication state changes
- [ ] **`AuthEndpoints.kt:537`** Useful for "approve login from another device" flows
- [ ] **`AuthEndpoints.kt:538`** PROOF REUSE PREVENTION
- [ ] **`AuthEndpoints.kt:539`** Current implementation validates proof signatures but doesn't prevent replay attacks within expiration
- [ ] **`AuthEndpoints.kt:540`** Consider adding nonce/jti to proofs and tracking used proof IDs
- [ ] **`AuthEndpoints.kt:541`** This prevents attackers from reusing intercepted proofs
- [ ] **`AuthEndpoints.kt:542`** DOCUMENTATION IMPROVEMENTS
- [ ] **`AuthEndpoints.kt:543`** Add OpenAPI examples showing complete authentication flows
- [ ] **`AuthEndpoints.kt:544`** Document recommended strength values for different security levels
- [ ] **`AuthEndpoints.kt:545`** Provide client SDK examples for common authentication patterns

### `sessions/src/main/kotlin/com/lightningkite/lightningserver/sessions/SessionManager.kt`

- [ ] **`SessionManager.kt:626`** TODO: SessionManager API Improvements and Recommendations
- [ ] **`SessionManager.kt:627`** BULK SESSION TERMINATION
- [ ] **`SessionManager.kt:628`** Add endpoint to terminate all sessions for a user (except current)
- [ ] **`SessionManager.kt:629`** Add endpoint to terminate all derived sub-sessions from a parent session
- [ ] **`SessionManager.kt:630`** Useful for "logout from all devices" and cascading termination
- [ ] **`SessionManager.kt:631`** REFRESH TOKEN ROTATION
- [ ] **`SessionManager.kt:632`** Implement refresh token rotation for better security
- [ ] **`SessionManager.kt:633`** Each refresh invalidates the old token and issues a new one
- [ ] **`SessionManager.kt:634`** Helps detect token theft (both attacker and victim get invalid token)
- [ ] **`SessionManager.kt:635`** Consider making this configurable per-principal
- [ ] **`SessionManager.kt:636`** DEVICE FINGERPRINTING
- [ ] **`SessionManager.kt:637`** Currently tracks user agent and IP, but doesn't enforce consistency
- [ ] **`SessionManager.kt:638`** Consider optional "trusted device" mode that alerts on IP/UA changes
- [ ] **`SessionManager.kt:639`** Add anomaly detection for suspicious session usage patterns
- [ ] **`SessionManager.kt:640`** SCOPE VALIDATION FOR SUB-SESSIONS
- [ ] **`SessionManager.kt:641`** Current implementation trusts client to provide valid scopes
- [ ] **`SessionManager.kt:642`** Add validation that requested scopes are subset of parent session scopes
- [ ] **`SessionManager.kt:643`** Prevent scope escalation attacks
- [ ] **`SessionManager.kt:644`** SESSION REFRESH OPTIMIZATION
- [ ] **`SessionManager.kt:645`** Currently updates session metadata on every refresh token use (DB write)
- [ ] **`SessionManager.kt:646`** Consider batching updates or using cache with periodic flush
- [ ] **`SessionManager.kt:647`** High-traffic applications may experience performance issues
- [ ] **`SessionManager.kt:648`** Alternative: Only update lastUsed if > N minutes since last update
- [ ] **`SessionManager.kt:649`** PRESIGN TOKEN METHODS
- [ ] **`SessionManager.kt:650`** Currently commented out presignToken methods
- [ ] **`SessionManager.kt:651`** These are useful for server-to-server operations (webhooks, background jobs)
- [ ] **`SessionManager.kt:652`** Consider enabling with clear documentation about security implications
- [ ] **`SessionManager.kt:653`** Presigned tokens bypass session validation - use with extreme caution
- [ ] **`SessionManager.kt:654`** OAUTH INTEGRATION
- [ ] **`SessionManager.kt:655`** oauthClient field exists but is unused (TODO comment in code)
- [ ] **`SessionManager.kt:656`** Complete OAuth 2.0 flow implementation
- [ ] **`SessionManager.kt:657`** Support OAuth refresh token to Lightning refresh token mapping
- [ ] **`SessionManager.kt:658`** Handle OAuth token revocation
- [ ] **`SessionManager.kt:659`** SESSION EVENTS AND HOOKS
- [ ] **`SessionManager.kt:660`** Add hooks for session lifecycle events (created, used, expired, terminated)
- [ ] **`SessionManager.kt:661`** Useful for audit logging, analytics, and security monitoring
- [ ] **`SessionManager.kt:662`** Consider: onSessionCreated, onSessionUsed, onSessionExpired, onSessionTerminated
- [ ] **`SessionManager.kt:663`** RATE LIMITING
- [ ] **`SessionManager.kt:664`** Add rate limiting for token refresh endpoint to prevent abuse
- [ ] **`SessionManager.kt:665`** Protect against brute force attacks on refresh tokens
- [ ] **`SessionManager.kt:666`** Consider exponential backoff on failed authentication attempts
- [ ] **`SessionManager.kt:667`** CONCURRENT SESSION LIMITS
- [ ] **`SessionManager.kt:668`** Add configurable maximum active sessions per user
- [ ] **`SessionManager.kt:669`** Auto-terminate oldest sessions when limit exceeded
- [ ] **`SessionManager.kt:670`** Different limits for different user tiers/plans
- [ ] **`SessionManager.kt:671`** SESSION POLICIES
- [ ] **`SessionManager.kt:672`** Make expiration policies more flexible (per-user, per-role, per-client)
- [ ] **`SessionManager.kt:673`** Support different policies for mobile vs web vs API-only sessions
- [ ] **`SessionManager.kt:674`** Allow runtime policy updates without code changes
- [ ] **`SessionManager.kt:675`** SECURITY ENHANCEMENTS
- [ ] **`SessionManager.kt:676`** Add support for JWT revocation lists (for access token invalidation)
- [ ] **`SessionManager.kt:677`** Implement session binding to client certificates or device tokens
- [ ] **`SessionManager.kt:678`** Add "step-up" authentication for sensitive operations
- [ ] **`SessionManager.kt:679`** Support for hardware security tokens (WebAuthn, FIDO2)
- [ ] **`SessionManager.kt:680`** DOCUMENTATION
- [ ] **`SessionManager.kt:681`** Add example implementations for common scenarios
- [ ] **`SessionManager.kt:682`** Document best practices for client-side token storage
- [ ] **`SessionManager.kt:683`** Security guidelines for different deployment scenarios
- [ ] **`SessionManager.kt:684`** Migration guide for existing auth systems

## Module: sessions-email

### `sessions-email/src/main/kotlin/com/lightningkite/lightningserver/sessions/proofs/EmailProofEndpoints.kt`

- [ ] **`EmailProofEndpoints.kt:103`** TODO API Recommendations:
- [ ] **`EmailProofEndpoints.kt:104`** Consider adding a built-in magic link template as an alternative to PIN codes for better UX.
- [ ] **`EmailProofEndpoints.kt:105`** Consider adding email normalization beyond lowercase (e.g., Gmail dot/plus address handling).
- [ ] **`EmailProofEndpoints.kt:106`** The verifyEmail function could be enhanced to return an error message that can be displayed to the user (e.g., "Please use your work email address").
- [ ] **`EmailProofEndpoints.kt:108`** Consider adding support for HTML email templates in addition to the current plain text support.
- [ ] **`EmailProofEndpoints.kt:109`** Consider adding a rate limiter parameter to prevent email bombing attacks.

## Module: sessions-oauth

### `sessions-oauth/src/main/kotlin/com/lightningkite/lightningserver/sessions/proofs/OauthProofEndpoints.kt`

- [ ] **`OauthProofEndpoints.kt:158`** TODO: API Recommendations
- [ ] **`OauthProofEndpoints.kt:159`** The callback endpoint constructs a redirect URL with manually encoded query parameters. Consider using a URL builder utility for safety and readability.
- [ ] **`OauthProofEndpoints.kt:161`** The continueUiAuthUrl function returns a String, but it's concatenated with query params. Consider documenting that it should NOT include a trailing '?' or existing query params, or make it more robust by handling both cases.
- [ ] **`OauthProofEndpoints.kt:164`** Consider adding error handling for when profile.email is null with more specific error messages indicating which OAuth provider failed to provide an email.
- [ ] **`OauthProofEndpoints.kt:166`** The 'backend' query parameter is added to the redirect but never used in the documented flow. Consider documenting its purpose or removing it if unused.
- [ ] **`OauthProofEndpoints.kt:168`** Consider adding telemetry/metrics for OAuth login attempts, successes, and failures to help diagnose provider-specific issues.
- [ ] **`OauthProofEndpoints.kt:170`** The UUID state parameter in callback is generated but not validated. Consider using the state parameter for CSRF protection by storing and validating it.

### `sessions-oauth/src/main/kotlin/com/lightningkite/lightningserver/sessions/proofs/oauth/OauthProviderInfo.kt`

- [ ] **`OauthProviderInfo.kt:317`** TODO: API Recommendations
- [ ] **`OauthProviderInfo.kt:318`** The pathName and identifierName transformations replace non-alphanumeric characters with '-' and '_', but consecutive non-alphanumeric characters become consecutive delimiters (e.g., "My  Provider" -> "my--provider"). Consider collapsing consecutive delimiters into a single one.
- [ ] **`OauthProviderInfo.kt:322`** Consider making the 'all' list immutable (List instead of ArrayList) to prevent accidental modification. Providers should be registered during initialization only.
- [ ] **`OauthProviderInfo.kt:324`** The Apple provider decodes the JWT id_token manually (line 148). Consider using a JWT library for proper validation (signature, expiration, issuer, audience). Current implementation doesn't verify the JWT signature, which is a potential security risk.
- [ ] **`OauthProviderInfo.kt:327`** Error handling for profile retrieval could be more specific. Consider wrapping provider-specific exceptions with context about which provider failed.
- [ ] **`OauthProviderInfo.kt:329`** The Google provider checks `verified_email` but other providers have different verification approaches. Consider documenting the email verification guarantees for each provider.
- [ ] **`OauthProviderInfo.kt:332`** Consider adding a 'validate()' method to check if required configuration is present and URLs are well-formed.
- [ ] **`OauthProviderInfo.kt:334`** The GitHub provider makes two API calls (user + emails). Consider if the user endpoint's email field could be used when it's available and verified to save an API call.
- [ ] **`OauthProviderInfo.kt:336`** HTTP client configuration (timeouts, retries) is not exposed. Consider making it configurable for production reliability.
- [ ] **`OauthProviderInfo.kt:338`** The accessToken methods for refresh tokens (line 99) don't handle the case where the refresh token is expired or revoked. Consider more specific error handling.

## Module: sessions-oauth-shared

### `sessions-oauth-shared/src/commonMain/kotlin/com/lightningkite/lightningserver/sessions/proofs/oauth/models.kt`

- [ ] **`models.kt:138`** TODO: API Recommendations
- [ ] **`models.kt:139`** Consider adding validation for OauthClient.redirectUris to ensure they are valid URIs and potentially enforce HTTPS in production environments.
- [ ] **`models.kt:141`** The OauthClient._id is a String which could be any value. Consider documenting requirements/best practices (e.g., should it be a UUID? random string? specific format?)
- [ ] **`models.kt:143`** Consider adding a method to OauthClient to check if a redirect URI is valid: fun isValidRedirectUri(uri: String): Boolean
- [ ] **`models.kt:145`** OauthClientSecret.masked should have documented format/rules to ensure consistency (e.g., "first 3 chars + *** + last 3 chars").
- [ ] **`models.kt:147`** Consider adding an isActive or isValid method to OauthClientSecret that checks disabledAt.
- [ ] **`models.kt:148`** The OauthCode.error field could benefit from being an enum or sealed class representing standard OAuth error codes (invalid_request, unauthorized_client, access_denied, etc.)
- [ ] **`models.kt:150`** Consider adding doc comments for OauthTokenRequest, OauthCode, OauthCodeRequest fields to explain the OAuth flow context.

## Module: sessions-shared

### `sessions-shared/src/commonMain/kotlin/com/lightningkite/lightningserver/sessions/proofs/WebAuthN.kt`

- [ ] **`WebAuthN.kt:459`** TODO API Recommendations:
- [ ] **`WebAuthN.kt:460`** Consider adding helper functions for common WebAuthn operations:
- [ ] **`WebAuthN.kt:461`** Generating random challenges with appropriate entropy
- [ ] **`WebAuthN.kt:462`** Validating origin/RP ID matches
- [ ] **`WebAuthN.kt:463`** Verifying signature format before server-side verification
- [ ] **`WebAuthN.kt:464`** Consider adding configuration models for:
- [ ] **`WebAuthN.kt:465`** Timeout defaults (currently nullable Int, consider Duration type)
- [ ] **`WebAuthN.kt:466`** Challenge expiration policies
- [ ] **`WebAuthN.kt:467`** Allowed authenticator types per application security policy
- [ ] **`WebAuthN.kt:468`** The attestationObject in WebAuthNCredential contains the public key but it's Base64 encoded. Consider adding a parsed representation or helper to extract the public key for easier verification.
- [ ] **`WebAuthN.kt:470`** Sign count security: Consider adding a helper function or validation to detect sign count anomalies (backwards movement indicating cloned authenticators).
- [ ] **`WebAuthN.kt:472`** Transport hints: Consider using an enum instead of List<String> for transports to provide type safety and prevent invalid values.

## Module: sessions-sms

### `sessions-sms/src/main/kotlin/com/lightningkite/lightningserver/sessions/proofs/SmsProofEndpoints.kt`

- [ ] **`SmsProofEndpoints.kt:138`** TODO API Recommendations:
- [ ] **`SmsProofEndpoints.kt:139`** Phone number normalization could be improved:
- [ ] **`SmsProofEndpoints.kt:140`** Add support for more international formats
- [ ] **`SmsProofEndpoints.kt:141`** Consider using a library like libphonenumber for robust parsing
- [ ] **`SmsProofEndpoints.kt:142`** Add validation to reject clearly invalid numbers before sending
- [ ] **`SmsProofEndpoints.kt:143`** Consider adding SMS provider detection to block:
- [ ] **`SmsProofEndpoints.kt:144`** VOIP numbers (Google Voice, Skype, etc.)
- [ ] **`SmsProofEndpoints.kt:145`** Temporary/disposable phone numbers
- [ ] **`SmsProofEndpoints.kt:146`** Premium rate numbers
- [ ] **`SmsProofEndpoints.kt:147`** Add built-in rate limiting per phone number to prevent:
- [ ] **`SmsProofEndpoints.kt:148`** SMS bombing attacks
- [ ] **`SmsProofEndpoints.kt:149`** Unexpected cost spikes
- [ ] **`SmsProofEndpoints.kt:150`** Suggested: 3 SMS per phone per hour, 10 per day
- [ ] **`SmsProofEndpoints.kt:151`** Consider adding message length validation:
- [ ] **`SmsProofEndpoints.kt:152`** SMS messages over 160 characters may be split/charged multiple times
- [ ] **`SmsProofEndpoints.kt:153`** Warn or prevent overly long templates
- [ ] **`SmsProofEndpoints.kt:154`** Add support for internationalization of SMS templates based on phone country code.
- [ ] **`SmsProofEndpoints.kt:155`** Consider adding delivery receipt tracking to detect failed deliveries.

## Module: typed

### `typed/src/main/kotlin/com/lightningkite/lightningserver/typed/ApiHttpHandler.kt`

- [ ] **`ApiHttpHandler.kt:136`** TODO: API Improvements
- [ ] **`ApiHttpHandler.kt:137`** Consider adding rate limiting support at the endpoint level (annotations or configuration)
- [ ] **`ApiHttpHandler.kt:138`** Add request/response logging hooks for debugging and monitoring
- [ ] **`ApiHttpHandler.kt:139`** Consider providing a way to specify cache headers (ETag, Last-Modified, Cache-Control)
- [ ] **`ApiHttpHandler.kt:140`** Add support for conditional requests (If-None-Match, If-Modified-Since)
- [ ] **`ApiHttpHandler.kt:141`** Consider adding built-in support for request tracing (distributed tracing IDs)
- [ ] **`ApiHttpHandler.kt:142`** The errorCases list could be enhanced with response examples for each error
- [ ] **`ApiHttpHandler.kt:143`** Add deprecation support (@Deprecated equivalent for endpoints in SDK generation)
- [ ] **`ApiHttpHandler.kt:144`** Consider adding request/response schema validation levels (strict vs lenient)

## Module: typed-shared

### `typed-shared/src/commonMain/kotlin/com/lightningkite/lightningserver/typed/Fetcher.kt`

- [ ] **`Fetcher.kt:81`** TODO: API Improvements
- [ ] **`Fetcher.kt:82`** Consider adding request/response interceptors for logging, retry logic, and custom error handling
- [ ] **`Fetcher.kt:83`** Add timeout configuration support at the Fetcher level
- [ ] **`Fetcher.kt:84`** Consider providing a builder pattern for Fetcher configuration (headers, timeouts, base URL, etc.)
- [ ] **`Fetcher.kt:85`** Add support for request cancellation (returning Job or providing CancellationToken)
- [ ] **`Fetcher.kt:86`** Consider adding built-in retry logic with exponential backoff for failed requests
- [ ] **`Fetcher.kt:87`** The url() method could benefit from a URLEncoder abstraction for platform-specific encoding

### `typed-shared/src/commonMain/kotlin/com/lightningkite/lightningserver/typed/ClientModelRestEndpoints.kt`

- [ ] **`ClientModelRestEndpoints.kt:250`** TODO: API Improvements
- [ ] **`ClientModelRestEndpoints.kt:251`** Consider adding cursor-based pagination support in addition to skip/limit
- [ ] **`ClientModelRestEndpoints.kt:252`** Add optimistic locking support via ETags or version fields to prevent lost updates
- [ ] **`ClientModelRestEndpoints.kt:253`** Consider providing batch variants that return partial success information (which succeeded, which failed)
- [ ] **`ClientModelRestEndpoints.kt:254`** The default() method could accept parameters to customize the default instance
- [ ] **`ClientModelRestEndpoints.kt:255`** Add support for partial updates via JSON Patch or similar standard
- [ ] **`ClientModelRestEndpoints.kt:256`** Consider adding a watch() method for long-polling as an alternative to WebSocket updates
- [ ] **`ClientModelRestEndpoints.kt:257`** Aggregate methods could support multiple aggregations in one request for efficiency
- [ ] **`ClientModelRestEndpoints.kt:258`** Add transaction support for bulk operations that should be atomic

### `typed-shared/src/commonMain/kotlin/com/lightningkite/lightningserver/typed/models.kt`

- [ ] **`models.kt:298`** TODO: API Improvements
- [ ] **`models.kt:299`** ServerHealth could benefit from a timestamp field to know when the health check was performed
- [ ] **`models.kt:300`** LightningServerKSchema could include version information for schema evolution tracking

### `typed-shared/src/commonMain/kotlin/com/lightningkite/lightningserver/typed/ClientWebSocket.kt`

- [ ] **`ClientWebSocket.kt:76`** TODO: API Improvements
- [ ] **`ClientWebSocket.kt:77`** Consider adding onError callback for handling connection errors separately from close events
- [ ] **`ClientWebSocket.kt:78`** Add reconnection support with configurable retry strategy
- [ ] **`ClientWebSocket.kt:79`** The close() method should accept WebSocket standard close codes (enum) instead of raw Short
- [ ] **`ClientWebSocket.kt:80`** Consider adding a suspend send() variant that confirms message delivery
- [ ] **`ClientWebSocket.kt:81`** Add message queue support for sending messages before connection is established
- [ ] **`ClientWebSocket.kt:82`** Consider providing a Flow<RECEIVE> API in addition to callback-based onMessage
- [ ] **`ClientWebSocket.kt:83`** Add ping/pong support for connection health monitoring

---

## How to Use This File

1. **Review by module:** Jump to the module you're working on to see relevant suggestions
2. **Check off completed items:** Mark items as `[x]` when implemented
3. **Prioritize:** Not all suggestions are equal - focus on those that impact your use case
4. **Track in source:** Update or remove TODO comments in source files as you implement them
5. **Re-generate:** Run this script again to get an updated list after source changes

## Generation Command

```bash
kotlin local/extract-api-suggestions.kts
```

---

*This file is auto-generated. Do not edit manually. Regenerate using the script above.*
