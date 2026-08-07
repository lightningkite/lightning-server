package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.data.*
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.database.HasId
import kotlinx.serialization.*
import kotlinx.serialization.builtins.NothingSerializer
import kotlinx.serialization.builtins.nullable
import kotlin.time.Instant

/**
 * Factory function for creating [Authentication] instances.
 *
 * This is the primary way to create authentication tokens. It handles ID serialization
 * and cache initialization automatically.
 *
 * @param principalType The type of principal being authenticated
 * @param id The ID of the authenticated subject
 * @param sessionId Optional session identifier for tracking user sessions
 * @param issuedAt When the authentication was issued (defaults to current time)
 * @param expiration When the authentication expires (null = no expiration)
 * @param scopes The scopes granted to this authentication (defaults to root)
 * @param fromMasquerade If this is a masquerade, the original authentication
 * @param cache Optional cache for storing fetched data
 * @return A new [Authentication] instance
 */
context(server: ServerRuntime)
public fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> Authentication(
    principalType: PrincipalType<SUBJECT, ID>,
    id: ID,
    sessionId: String?,
    issuedAt: Instant = server.clock.now(),
    expiration: Instant? = null,
    scopes: Set<GrantedScope> = setOf(GrantedScope.root),
    fromMasquerade: Authentication<*>? = null,
    cache: SerializableCache? = null,
): Authentication<SUBJECT> = Authentication(
    principalType = principalType,
    id = id,
    rawId = principalType.idString(id),
    sessionId = sessionId,
    issuedAt = issuedAt,
    expiration = expiration,
    scopes = scopes,
    fromMasquerade = fromMasquerade,
    cache = cache
)

/**
 * Represents an authenticated entity with associated permissions and metadata.
 *
 * [Authentication] is the core authentication token used throughout Lightning Server.
 * It encapsulates:
 * - The **subject** (who is authenticated)
 * - The **scopes** (what they can access)
 * - **Temporal constraints** (when issued, when it expires)
 * - **Session tracking** (sessionId)
 * - **Masquerading support** (fromMasquerade)
 * - **Caching** for expensive lookups
 *
 * ## Creating Authentication
 *
 * Use the factory function with a [PrincipalType]:
 * ```kotlin
 * context(server: ServerRuntime) {
 *     val auth = Authentication(
 *         principalType = UserPrincipal,
 *         id = userId,
 *         sessionId = "session-abc-123",
 *         scopes = setOf(GrantedScope("api:read"), GrantedScope("api:write"))
 *     )
 * }
 * ```
 *
 * ## Accessing the Subject
 *
 * ```kotlin
 * context(server: ServerRuntime) {
 *     val userId: UUID = auth.id
 *     val user: User = auth.fetch() // Cached automatically
 * }
 * ```
 *
 * ## Masquerading
 *
 * Masquerading allows one authenticated user to impersonate another. This is useful
 * for support/admin scenarios. The original authentication is preserved in [fromMasquerade].
 *
 * ```kotlin
 * val adminAuth = Authentication(AdminPrincipal, adminId, ...)
 * val masqueradeAuth = Authentication(
 *     UserPrincipal,
 *     targetUserId,
 *     fromMasquerade = adminAuth,
 *     scopes = adminAuth.scopes // Inherit admin's scopes
 * )
 * ```
 *
 * ## Caching
 *
 * Authentication includes a [cache] for storing expensive-to-compute values.
 * Use [AuthCacheKey] to define cached computations:
 * ```kotlin
 * val userData = auth[userDataCacheKey]
 * ```
 *
 * @param SUBJECT The type of authenticated entity
 * @property principalName The name of the principal type (for deserialization)
 * @property rawId The serialized ID of the subject
 * @property sessionId Optional session identifier for tracking
 * @property issuedAt When this authentication was created
 * @property expiration When this authentication expires (null = no expiration)
 * @property scopes The permissions granted to this authentication
 * @property fromMasquerade If masquerading, the original authentication
 * @property cache Cache for storing computed values
 *
 * @see PrincipalType
 * @see GrantedScope
 * @see AuthRequirement
 */
@Serializable
@ConsistentCopyVisibility
public data class Authentication<SUBJECT : HasId<*>> private constructor(
    public val principalName: String,
    public val rawId: String,
    public val sessionId: String?,
    public val issuedAt: Instant,
    public val expiration: Instant? = null,
    public val scopes: Set<GrantedScope> = setOf(GrantedScope.root),
    public val fromMasquerade: Authentication<*>? = null,
    override val cache: SerializableCache = SerializableCache(),
) : Caching {
    internal constructor(
        // I wish kotlin had file-private visibility
        principalType: PrincipalType<SUBJECT, *>,
        id: Comparable<*>,
        rawId: String,
        sessionId: String?,
        issuedAt: Instant,
        expiration: Instant?,
        scopes: Set<GrantedScope>,
        fromMasquerade: Authentication<*>?,
        cache: SerializableCache?,
    ) : this(
        principalName = principalType.name,
        rawId = rawId,
        sessionId = sessionId,
        issuedAt = issuedAt,
        expiration = expiration,
        scopes = scopes,
        fromMasquerade = fromMasquerade,
        cache = cache ?: SerializableCache()
    ) {
        cachedId = id
        cachedType = principalType
    }

    // typed parameters

    @Transient
    private var cachedType: PrincipalType<SUBJECT, *>? = null

    /**
     * Retrieves the [PrincipalType] for this authentication.
     *
     * This property lazily resolves the principal type from the server's registry
     * and caches it for subsequent access.
     *
     * @throws UnauthorizedException if the principal type is not registered
     */
    @Suppress("UNCHECKED_CAST")
    context(server: ServerRuntime)
    public val untypedPrincipal: PrincipalType<SUBJECT, *>
        get() = cachedType
            ?: (server.server.principalTypes[principalName] as? PrincipalType<SUBJECT, *>)?.also { cachedType = it }
            ?: throw UnauthorizedException("Principal type $principalName is unrecognized")

    @Transient
    public var cachedId: Comparable<*>? = null

    /**
     * Retrieves the deserialized ID for this authentication's subject.
     *
     * The ID is lazily deserialized from [rawId] and cached for subsequent access.
     */
    context(server: ServerRuntime)
    public val untypedId: Comparable<*>
        get() = cachedId
            ?: server.internalSerialization.stringArrayFormat
                .decodeFromString(untypedPrincipal.idSerializer, rawId)
                .also { cachedId = it }

    /**
     * Pre-populates the cache with values for the given keys.
     *
     * This is useful for eagerly loading data that will be needed to avoid
     * multiple round trips or N+1 queries.
     *
     * @param keys The cache keys to pre-populate
     */
    context(server: ServerRuntime)
    public suspend fun precache(keys: Iterable<AuthCacheKey<SUBJECT, *>>) {
        for (key in keys) cache.get(key, this)
    }

    override fun toString(): String = listOfNotNull(
        fromMasquerade?.let { "$it masquerading as" },
        principalName,
        rawId,
        cache.let { "cached: $it" },
    ).joinToString(" ")

    public fun copy(
        expiration: Instant? = this.expiration,
        scopes: Set<GrantedScope> = this.scopes,
    ): Authentication<SUBJECT> = Authentication(
        principalName = this.principalName,
        rawId = this.rawId,
        sessionId = this.sessionId,
        issuedAt = this.issuedAt,
        expiration = expiration,
        scopes = scopes,
        fromMasquerade = this.fromMasquerade,
        cache = this.cache,
    )

    // caching

    @Suppress("UNCHECKED_CAST")
    public object CacheKey : SerializableCache.CalculatingKey<Request<*>, Authentication<*>?> {
        override val id: String = "authentication"

        @OptIn(ExperimentalSerializationApi::class)
        override val serializer: KSerializer<Authentication<*>?> =
            serializer(NothingSerializer()).nullable as KSerializer<Authentication<*>?>

        context(server: ServerRuntime)
        override suspend fun calculate(input: Request<*>): Authentication<*>? {
            for (reader in server.server.authReaders.sortedByDescending { it.priority }) {
                val auth = reader.read(input) ?: continue

                input.headers[HttpHeader.XMasquerade]?.let { header ->
                    val masquerade = header.root

                    val validEncodings = mapOf(
                        "str-array" to server.externalSerialization.stringArrayFormat,
                        "json" to server.externalSerialization.json
                    )

                    if (!masquerade.contains('/')) throw BadRequestException(
                        """Invalid masquerade value. Expected to be in the form: <principal-name>/<subject-id> (; encoding=[${
                            validEncodings.keys.withIndex().joinToString(" | ") { (idx, enc) -> 
                                if (idx == 0) "${enc}(default)"
                                else enc
                            }
                        }])"""
                    )

                    val principalName = masquerade.substringBefore('/')
                    val principal = server.server.principalTypes[principalName] as? PrincipalType<HasId<*>, *>
                        ?: throw BadRequestException("Principal type $principalName is unrecognized for masquerade")

                    val encoding = header.parameters["encoding"]
                        ?.let {
                            validEncodings[it] ?: throw BadRequestException("Invalid encoding type. Supported types are ${validEncodings.keys}")
                        }
                        ?: validEncodings.values.first()

                    val idString = masquerade.substringAfter('/')
                    val id = try {
                        encoding.decodeFromString(principal.idSerializer, idString)
                    } catch (e: SerializationException) {
                        throw BadRequestException(
                            message = "Invalid masquerade id: ${e.message}",
                            data = idString,
                            cause = e
                        )
                    }

                    val mask = Authentication(
                        principalType = principal,
                        id = id,
                        rawId = idString,
                        sessionId = null,
                        issuedAt = server.clock.now(),
                        expiration = auth.expiration,
                        scopes = auth.scopes,
                        fromMasquerade = auth,
                        cache = auth.cache,
                    )

                    if (principal.permitMasquerade(auth, mask)) return mask
                    else {
                        server.logger.warn { "$auth denied masquerade as $masquerade" }
                        throw ForbiddenException("You are not allowed to masquerade as $masquerade")
                    }
                }

                return auth
            }
            return null
        }
    }

    @Deprecated("Dont cache auth within itself.", level = DeprecationLevel.ERROR)
    public operator fun get(key: CacheKey): Authentication<*> = throw NotImplementedError()

    @Deprecated("Dont cache auth within itself.", level = DeprecationLevel.ERROR)
    public fun get(key: CacheKey, input: Request<*>): Authentication<*> = throw NotImplementedError()


    // related types

    /**
     * Interface for reading [Authentication] from HTTP requests.
     *
     * Readers extract authentication information from requests (e.g., from headers,
     * cookies, query parameters) and construct [Authentication] instances.
     *
     * Multiple readers can be registered, and they are tried in priority order
     * (highest priority first) until one successfully reads authentication.
     *
     * ## Implementing a Reader
     *
     * ```kotlin
     * object BearerTokenReader : Authentication.Reader<User> {
     *     override val priority = 100.0 // Higher priority = checked first
     *
     *     context(server: ServerRuntime)
     *     override suspend fun read(request: Request<*>): Authentication<User>? {
     *         val token = request.headers[HttpHeader.Authorization]
     *             ?.root
     *             ?.removePrefix("Bearer ")
     *             ?: return null
     *
     *         val userId = validateToken(token) ?: return null
     *         return Authentication(UserPrincipal, userId, sessionId = null)
     *     }
     * }
     * ```
     *
     * ## Registering Readers
     *
     * ```kotlin
     * object Server : ServerBuilder() {
     *     init {
     *         authReaders.register(BearerTokenReader)
     *     }
     * }
     * ```
     *
     * @param SUBJECT The type of subject this reader can authenticate
     * @property priority Priority for this reader (higher = checked first)
     */
    public fun interface Reader<SUBJECT : HasId<*>> {
        public val priority: Double get() = 0.0

        context(server: ServerRuntime)
        public suspend fun read(request: Request<*>): Authentication<SUBJECT>?
    }
}

/*
 * TODO: API Recommendations
 *
 * 1. The masquerade functionality in CacheKey.calculate() (lines 236-264) has several considerations:
 *    - The X-Masquerade header format "principal/id" could be documented more clearly
 *    - Consider adding a dedicated MasqueradeRequest data class instead of parsing strings
 *    - The permitMasquerade check happens AFTER creating the mask Authentication, which could
 *      be inefficient. Consider checking permission before construction.
 *
 * 2. The precache() function iterates serially. For better performance, consider:
 *    ```kotlin
 *    coroutineScope {
 *        keys.map { key -> async { cache.get(key, this@Authentication) } }.awaitAll()
 *    }
 *    ```
 *
 * 3. The copy() method only allows changing expiration and scopes. Users may want to copy
 *    with modified sessionId or other fields. Consider making it more flexible or documenting
 *    why these are the only mutable fields.
 *
 * 4. The toString() includes cache contents which could be very large. Consider truncating
 *    or summarizing the cache for better log readability.
 *
 * 5. The @Deprecated methods for caching auth within itself are good, but the error messages
 *    could be more helpful (explain WHY this is problematic).
 *
 * 6. Consider adding a method to check if authentication is expired:
 *    ```kotlin
 *    context(server: ServerRuntime)
 *    fun isExpired(): Boolean = expiration?.let { it < server.clock.now() } ?: false
 *    ```
 *
 * 7. The Reader interface could benefit from a default implementation or helper for common
 *    patterns (e.g., reading from Authorization header with different schemes).
 */

@Suppress("UNCHECKED_CAST")
context(server: ServerRuntime)
public val <SUBJECT : HasId<ID>, ID : Comparable<ID>> Authentication<SUBJECT>.principalType: PrincipalType<SUBJECT, ID>
    get() = untypedPrincipal as PrincipalType<SUBJECT, ID>

@Suppress("UNCHECKED_CAST")
context(server: ServerRuntime)
public val <SUBJECT : HasId<ID>, ID : Comparable<ID>> Authentication<SUBJECT>.id: ID
    get() = untypedId as ID

@Suppress("UNCHECKED_CAST", "UPPER_BOUND_VIOLATED_IN_TYPE_OPERATOR_OR_PARAMETER_BOUNDS_WARNING")
context(server: ServerRuntime)
public suspend fun <SUBJECT : HasId<*>> Authentication<SUBJECT>.fetch(): SUBJECT =
    cache.getOrPut(untypedPrincipal.subjectCacheKey) {
        (untypedPrincipal as PrincipalType<SUBJECT, Comparable<*>>).fetch(
            untypedId
        )
    }