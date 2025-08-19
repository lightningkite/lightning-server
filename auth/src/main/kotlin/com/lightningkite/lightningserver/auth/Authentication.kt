package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.Caching
import com.lightningkite.lightningserver.ForbiddenException
import com.lightningkite.lightningserver.Request
import com.lightningkite.lightningserver.SerializableCache
import com.lightningkite.lightningserver.UnauthorizedException
import com.lightningkite.lightningserver.definition.ListRegistryExtension
import com.lightningkite.lightningserver.definition.Locationed
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpMethod
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.toPredicate
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.database.HasId
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Transient
import kotlinx.serialization.builtins.NothingSerializer
import kotlinx.serialization.builtins.nullable
import kotlin.collections.getOrPut
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
    ) : this(
        principalName = principalType.name,
        rawId = server.internalSerialization.json.encodeToString(principalType.idSerializer, id),
        issuedAt,
        fromMasquerade,
        limitTo,
        forbid
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

    public interface Reader<SUBJECT : HasId<ID>, ID : Comparable<ID>> {
        context(server: ServerRuntime)
        public suspend fun read(request: Request<*>): Authentication<SUBJECT, ID>?

        public companion object : ListRegistryExtension<Reader<*, *>>
    }

    public class Builder {
        public val limitTo: RequestPredicates.Builder = RequestPredicates.Builder()
        public val forbid: RequestPredicates.Builder = RequestPredicates.Builder()

        public fun limitToEndpoints(vararg endpoints: HttpEndpoint<PathSpec>) { for (endpoint in endpoints) limitTo.methods.getOrPut(endpoint.method, ::ArrayList).add(endpoint.path.toPredicate()) }
        public fun limitToEndpoints(vararg endpoints: Locationed<HttpEndpoint<PathSpec>, *>) { for (endpoint in endpoints) limitTo.methods.getOrPut(endpoint.location.method, ::ArrayList).add(endpoint.location.path.toPredicate()) }
        @Suppress("FINAL_UPPER_BOUND") public fun <T : HttpMethod> limitToMethods(vararg methods: T) { for (method in methods) limitTo.methods.getOrPut(method, ::ArrayList).clear() }
        public fun limitToHeaders(headers: HttpHeaders) { limitTo.headers.set(headers) }
        public fun limitToQueryParameters(vararg queryParameters: Pair<String, String>) { limitTo.queryParameters.addAll(queryParameters) }
        public fun limitToScopes(vararg scopes: String) { limitTo.scopes.addAll(scopes) }
        public fun forbidEndpoints(vararg endpoints: HttpEndpoint<PathSpec>) { for (endpoint in endpoints) forbid.methods.getOrPut(endpoint.method, ::ArrayList).add(endpoint.path.toPredicate()) }
        public fun forbidEndpoints(vararg endpoints: Locationed<HttpEndpoint<PathSpec>, *>) { for (endpoint in endpoints) forbid.methods.getOrPut(endpoint.location.method, ::ArrayList).add(endpoint.location.path.toPredicate()) }
        @Suppress("FINAL_UPPER_BOUND") public fun <T : HttpMethod> forbidMethods(vararg methods: T) { for (method in methods) forbid.methods.getOrPut(method, ::ArrayList).clear() }
        public fun forbidHeaders(headers: HttpHeaders) { forbid.headers.set(headers) }
        public fun forbidQueryParameters(vararg queryParameters: Pair<String, String>) { forbid.queryParameters.addAll(queryParameters) }
        public fun forbidScopes(vararg scopes: String) { forbid.scopes.addAll(scopes) }
    }
}