package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.data.SerializableCache
import com.lightningkite.lightningserver.definition.Locationed
import com.lightningkite.lightningserver.definition.builder.ListRegistry
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.getValue
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpMethod
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.toPredicate
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.serializerOrContextual
import java.lang.IllegalArgumentException
import kotlin.time.Instant

context(server: ServerRuntime)
public fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> Authentication(
    principalType: PrincipalType<SUBJECT, ID>,
    id: ID,
    issuedAt: Instant = server.clock.now(),
    limitTo: RequestPredicates? = null,
    forbid: RequestPredicates? = null,
    fromMasquerade: Authentication<*, *>? = null,
    cache: SerializableCache? = null
): Authentication<SUBJECT, ID> = Authentication(server, principalType, id, issuedAt, limitTo, forbid, fromMasquerade, cache)

public typealias AuthCacheKey<SUBJECT, ID, T> = SerializableCache.CalculatingKey<Authentication<SUBJECT, ID>, T>

context(_: ServerRuntime)
public suspend operator fun <SUBJECT : HasId<ID>, ID : Comparable<ID>, T> Authentication<SUBJECT, ID>.get(
    key: AuthCacheKey<SUBJECT, ID, T>
): T = cache.get(key, this)

public val ServerBuilder.authReaders: ListRegistry<Authentication.Reader<*, *>> by Authentication.Reader

@Suppress("UNCHECKED_CAST")
context(server: ServerRuntime)
public fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> PrincipalType<SUBJECT, ID>.testAuth(
    subject: SUBJECT,
    issuedAt: Instant = server.clock.now(),
    limitTo: RequestPredicates? = null,
    forbid: RequestPredicates? = null,
): Authentication<SUBJECT, ID> = Authentication(server, this, subject._id, issuedAt, limitTo, forbid)


public fun <S : HasId<ID>, ID : Comparable<ID>> Authentication<S, ID>.limitToEndpoints(
    vararg endpoints: HttpEndpoint<PathSpec>
): Authentication<S, ID> =
    limitTo { for (endpoint in endpoints) methods.getOrPut(endpoint.method, ::ArrayList).add(endpoint.path.toPredicate()) }

public fun <S : HasId<ID>, ID : Comparable<ID>> Authentication<S, ID>.limitToEndpoints(
    vararg endpoints: Locationed<HttpEndpoint<PathSpec>, *>
): Authentication<S, ID> =
    limitTo { for (endpoint in endpoints) methods.getOrPut(endpoint.location.method, ::ArrayList).add(endpoint.location.path.toPredicate()) }

@Suppress("FINAL_UPPER_BOUND") public fun <T : HttpMethod, S : HasId<ID>, ID : Comparable<ID>> Authentication<S, ID>.limitToMethods(
    vararg methods: T
): Authentication<S, ID> =
    limitTo { for (method in methods) this.methods.getOrPut(method, ::ArrayList).clear() }

public fun <S : HasId<ID>, ID : Comparable<ID>> Authentication<S, ID>.limitToHeaders(
    headers: HttpHeaders
): Authentication<S, ID> =
    limitTo { this.headers.set(headers) }

public fun <S : HasId<ID>, ID : Comparable<ID>> Authentication<S, ID>.limitToQueryParameters(
    vararg queryParameters: Pair<String, String>
): Authentication<S, ID> =
    limitTo { this.queryParameters.addAll(queryParameters) }

public fun <S : HasId<ID>, ID : Comparable<ID>> Authentication<S, ID>.limitToScopes(
    vararg scopes: String
): Authentication<S, ID> =
    limitTo { this.scopes.addAll(scopes) }



public fun <S : HasId<ID>, ID : Comparable<ID>> Authentication<S, ID>.forbidEndpoints(
    vararg endpoints: HttpEndpoint<PathSpec>
): Authentication<S, ID> =
    forbid { for (endpoint in endpoints) this.methods.getOrPut(endpoint.method, ::ArrayList).add(endpoint.path.toPredicate()) }

public fun <S : HasId<ID>, ID : Comparable<ID>> Authentication<S, ID>.forbidEndpoints(
    vararg endpoints: Locationed<HttpEndpoint<PathSpec>, *>
): Authentication<S, ID> =
    forbid { for (endpoint in endpoints) this.methods.getOrPut(endpoint.location.method, ::ArrayList).add(endpoint.location.path.toPredicate()) }

@Suppress("FINAL_UPPER_BOUND") public fun <T : HttpMethod, S : HasId<ID>, ID : Comparable<ID>> Authentication<S, ID>.forbidMethods(
    vararg methods: T
): Authentication<S, ID> =
    forbid { for (method in methods) this.methods.getOrPut(method, ::ArrayList).clear() }

public fun <S : HasId<ID>, ID : Comparable<ID>> Authentication<S, ID>.forbidHeaders(
    headers: HttpHeaders
): Authentication<S, ID> =
    forbid { this.headers.set(headers) }

public fun <S : HasId<ID>, ID : Comparable<ID>> Authentication<S, ID>.forbidQueryParameters(
    vararg queryParameters: Pair<String, String>
): Authentication<S, ID> =
    forbid { this.queryParameters.addAll(queryParameters) }

public fun <S : HasId<ID>, ID : Comparable<ID>> Authentication<S, ID>.forbidScopes(
    vararg scopes: String
): Authentication<S, ID> =
    forbid { this.scopes.addAll(scopes) }