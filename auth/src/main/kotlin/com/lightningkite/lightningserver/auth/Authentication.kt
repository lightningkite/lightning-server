package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.ForbiddenException
import com.lightningkite.lightningserver.UnauthorizedException
import com.lightningkite.lightningserver.data.Caching
import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.data.SerializableCache
import com.lightningkite.lightningserver.data.getOrPut
import com.lightningkite.lightningserver.definition.ListRegistryExtension
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.database.HasId
import kotlinx.serialization.*
import kotlinx.serialization.builtins.NothingSerializer
import kotlinx.serialization.builtins.nullable
import kotlin.time.Instant

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

    @Suppress("UNCHECKED_CAST")
    context(server: ServerRuntime)
    public val untypedPrincipal: PrincipalType<SUBJECT, *>
        get() = cachedType
            ?: (server.server.principalTypes[principalName] as? PrincipalType<SUBJECT, *>)?.also { cachedType = it }
            ?: throw UnauthorizedException("Principal type $principalName is unrecognized")

    @Transient
    public var cachedId: Comparable<*>? = null

    context(server: ServerRuntime)
    public val untypedId: Comparable<*>
        get() = cachedId
            ?: server.internalSerialization.stringArrayFormat.decodeFromString(untypedPrincipal.idSerializer, rawId)
                .also { cachedId = it }

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
        override val serializer: KSerializer<Authentication<*>?>
            get() = serializer(NothingSerializer()).nullable as KSerializer<Authentication<*>?>

        context(server: ServerRuntime)
        override suspend fun calculate(input: Request<*>): Authentication<*>? {
            for (reader in server.server.authReaders.sortedByDescending { it.priority }) {
                val auth = reader.read(input) ?: continue

                input.headers[HttpHeader.XMasquerade]?.root?.let { masquerade ->
                    val principal = masquerade.substringBefore('/')

                    val handler = server.server.principalTypes[principal] as? PrincipalType<HasId<*>, *>
                        ?: throw BadRequestException("Principal type $principal is unrecognized for masquerade")

                    val mask = Authentication<HasId<*>>(
                        principal,
                        rawId = masquerade.substringAfter('/'),
                        sessionId = null,
                        issuedAt = server.clock.now(),
                        expiration = auth.expiration,
                        scopes = auth.scopes,
                        fromMasquerade = auth,
                    )

                    try {
                        mask.untypedId
                    } catch (_: SerializationException) {
                        throw BadRequestException(message = "Invalid masquerade id", data = mask.rawId)
                    }

                    if (handler.permitMasquerade(auth, mask)) return mask
                    else throw ForbiddenException("You are not allowed to masquerade as $masquerade")
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

    public fun interface Reader<SUBJECT : HasId<*>> {
        public val priority: Double get() = 0.0

        context(server: ServerRuntime)
        public suspend fun read(request: Request<*>): Authentication<SUBJECT>?
    }
}

@Suppress("UNCHECKED_CAST")
context(server: ServerRuntime)
public val <SUBJECT : HasId<ID>, ID : Comparable<ID>> Authentication<SUBJECT>.principalType: PrincipalType<SUBJECT, ID>
    get() = untypedPrincipal as PrincipalType<SUBJECT, ID>

@Suppress("UNCHECKED_CAST")
context(server: ServerRuntime)
public val <SUBJECT : HasId<ID>, ID : Comparable<ID>> Authentication<SUBJECT>.id: ID
    get() = untypedId as ID

@Suppress("UNCHECKED_CAST")
context(server: ServerRuntime)
public suspend fun <SUBJECT : HasId<*>> Authentication<SUBJECT>.fetch(): SUBJECT =
    cache.getOrPut(untypedPrincipal.subjectCacheKey) { (untypedPrincipal as PrincipalType<SUBJECT, Comparable<*>>).fetch(untypedId) }