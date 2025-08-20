package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.data.Caching
import com.lightningkite.lightningserver.ForbiddenException
import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.data.SerializableCache
import com.lightningkite.lightningserver.UnauthorizedException
import com.lightningkite.lightningserver.data.Expiring
import com.lightningkite.lightningserver.data.getOrPut
import com.lightningkite.lightningserver.definition.ListRegistryExtension
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.database.HasId
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Transient
import kotlinx.serialization.builtins.NothingSerializer
import kotlinx.serialization.builtins.nullable
import kotlin.String
import kotlin.time.Duration
import kotlin.time.Instant

@Serializable
public class Authentication<SUBJECT : HasId<ID>, ID : Comparable<ID>> private constructor(
    public val principalName: String,
    public val rawId: String,
    public val issuedAt: Instant,
    public val fromMasquerade: Authentication<*, *>? = null,
    public val limitTo: RequestPredicates? = null,
    public val forbid: RequestPredicates? = null,
    override val cache: SerializableCache = SerializableCache()
) : Caching {

    // type-safe constructor
    public constructor(
        server: ServerRuntime,
        principalType: PrincipalType<SUBJECT, ID>,
        id: ID,
        issuedAt: Instant = server.clock.now(),
        fromMasquerade: Authentication<*, *>? = null,
        limitTo: RequestPredicates? = null,
        forbid: RequestPredicates? = null,
        precache: SerializableCache? = null
    ) : this(
        principalName = principalType.name,
        rawId = server.internalSerialization.json.encodeToString(principalType.idSerializer, id),
        issuedAt,
        fromMasquerade,
        limitTo,
        forbid,
        precache ?: SerializableCache()
    ) {
        cachedType = principalType
        cachedId = id
    }

    // check for contradictions
    init {
        if (limitTo != null && forbid != null) limitTo.intersect(forbid).let { intersection ->
            if (intersection.isNotEmpty()) throw IllegalArgumentException("Authentication limitTo and forbid cannot have any common predicates, as this leads to a contradiction. Intersection: $intersection")
        }
    }

    // typed parameters

    @Transient
    private var cachedType: PrincipalType<SUBJECT, ID>? = null

    @Suppress("UNCHECKED_CAST")
    context(server: ServerRuntime)
    public val principalType: PrincipalType<SUBJECT, ID> get() = cachedType
        ?: (server.server.principalTypes[principalName] as? PrincipalType<SUBJECT, ID>)?.also { cachedType = it }
        ?: throw UnauthorizedException("Principal type $principalName is unrecognized")

    @Transient
    private var cachedId: ID? = null

    context(server: ServerRuntime)
    public val id: ID get() = cachedId
        ?: server.internalSerialization.json.decodeFromString(principalType.idSerializer, rawId).also { cachedId = it }

    @Transient
    private var _subjectCacheKey: SerializableCache.Key<SUBJECT>? = null

    context(server: ServerRuntime)
    private val subjectCacheKey get() = _subjectCacheKey
        ?: object : SerializableCache.Key<SUBJECT> {
            override val id: String = "${principalType.name}-subject-cache"
            override val serializer: KSerializer<SUBJECT> = principalType.subjectSerializer
            override val expireAfter: Duration? = principalType.subjectCacheExpiration
        }.also { _subjectCacheKey = it }

    context(server: ServerRuntime)
    public suspend fun fetch(): SUBJECT = cache.getOrPut(subjectCacheKey) { principalType.fetch(id) }


    override fun toString(): String = listOfNotNull(
        fromMasquerade?.let { "$it masquerading as" },
        principalName,
        rawId,
        cache.let { "cached: $it" },
    ).joinToString(" ")


    // caching

    @Suppress("UNCHECKED_CAST")
    public object CacheKey : SerializableCache.CalculatingKey<Request<*>, Authentication<*, *>?> {
        override val id: String = "authentication"

        @OptIn(ExperimentalSerializationApi::class)
        override val serializer: KSerializer<Authentication<*, *>?>
            get() = serializer(NothingSerializer(), NothingSerializer()).nullable as KSerializer<Authentication<*, *>?>

        context(server: ServerRuntime)
        override suspend fun calculate(input: Request<*>): Authentication<*, *>? {
            for (reader in server.server.extensions[Reader] ?: emptyList()) {
                val auth = reader.read(input) ?: continue

                auth.limitTo?.let {
                    if (!it.matchesAll(input)) throw ForbiddenException(detail = "limitTo-violated", message = "Request outside limitations", data = it.toString())
                }
                auth.forbid?.let {
                    if (it.matchesAny(input)) throw ForbiddenException(detail = "forbid-violated", message = "Request touches forbidden resources", data = it.toString())
                }

                input.headers[HttpHeader.XMasquerade]?.root?.let { masquerade ->
                    val principal = masquerade.substringBefore('/')

                    val handler = server.server.principalTypes[principal] as? PrincipalType<HasId<Comparable<Any?>>, Comparable<Any?>>
                        ?: throw BadRequestException("Principal type $principal is unrecognized for masquerade")

                    val mask = Authentication<HasId<Comparable<Any?>>, Comparable<Any?>>(
                        principal,
                        rawId = masquerade.substringAfter('/'),
                        issuedAt = server.clock.now(),
                        fromMasquerade = auth,
                        limitTo = auth.limitTo,
                        forbid = auth.forbid
                    )

                    try {
                        mask.id
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

    @Deprecated("Dont cache auth within itself.", level = DeprecationLevel.ERROR) public operator fun get(key: CacheKey): Authentication<*, *> = throw NotImplementedError()
    @Deprecated("Dont cache auth within itself.", level = DeprecationLevel.ERROR) public fun get(key: CacheKey, input: Request<*>): Authentication<*, *> = throw NotImplementedError()


    // related types

    public fun interface Reader<SUBJECT : HasId<ID>, ID : Comparable<ID>> {
        context(server: ServerRuntime)
        public fun read(request: Request<*>): Authentication<SUBJECT, ID>?

        public companion object : ListRegistryExtension<Reader<*, *>>
    }

    public fun limitTo(builder: RequestPredicates.Builder.() -> Unit): Authentication<SUBJECT, ID> =
        Authentication(
            principalName,
            rawId,
            issuedAt,
            fromMasquerade,
            limitTo = limitTo?.copy(builder = builder) ?: RequestPredicates.Builder().apply(builder).build().takeUnless { it.isEmpty() },
            forbid,
            cache
        )

    public fun forbid(builder: RequestPredicates.Builder.() -> Unit): Authentication<SUBJECT, ID> =
        Authentication(
            principalName,
            rawId,
            issuedAt,
            fromMasquerade,
            limitTo,
            forbid = forbid?.copy(builder = builder) ?: RequestPredicates.Builder().apply(builder).build().takeUnless { it.isEmpty() },
            cache
        )
}