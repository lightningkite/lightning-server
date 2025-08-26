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
import kotlin.time.Instant


public typealias AuthCacheKey<SUBJECT, T> = SerializableCache.CalculatingKey<Authentication<SUBJECT>, T>

context(_: ServerRuntime)
public suspend operator fun <SUBJECT : HasId<ID>, ID : Comparable<ID>, T> Authentication<SUBJECT>.get(
    key: AuthCacheKey<SUBJECT, T>
): T = cache.get(key, this)

public val ServerBuilder.authReaders: ListRegistry<Authentication.Reader<*, *>> by Authentication.Reader


context(server: ServerRuntime)
public fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> PrincipalType<SUBJECT, ID>.testAuth(
    subject: SUBJECT,
    issuedAt: Instant = server.clock.now(),
    limitTo: RequestPredicates? = null,
    forbid: RequestPredicates? = null,
): Authentication<SUBJECT> = Authentication(this, subject._id, issuedAt, limitTo, forbid)


public fun <S : HasId<*>> Authentication<S>.limitToEndpoints(
    vararg endpoints: HttpEndpoint<PathSpec>
): Authentication<S> =
    limitTo { for (endpoint in endpoints) methods.getOrPut(endpoint.method, ::ArrayList).add(endpoint.path.toPredicate()) }

public fun <S : HasId<*>> Authentication<S>.limitToEndpoints(
    vararg endpoints: Locationed<HttpEndpoint<PathSpec>, *>
): Authentication<S> =
    limitTo { for (endpoint in endpoints) methods.getOrPut(endpoint.location.method, ::ArrayList).add(endpoint.location.path.toPredicate()) }

@Suppress("FINAL_UPPER_BOUND") public fun <T : HttpMethod, S : HasId<*>> Authentication<S>.limitToMethods(
    vararg methods: T
): Authentication<S> =
    limitTo { for (method in methods) this.methods.getOrPut(method, ::ArrayList).clear() }

public fun <S : HasId<*>> Authentication<S>.limitToHeaders(
    headers: HttpHeaders
): Authentication<S> =
    limitTo { this.headers.set(headers) }

public fun <S : HasId<*>> Authentication<S>.limitToQueryParameters(
    vararg queryParameters: Pair<String, String>
): Authentication<S> =
    limitTo { this.queryParameters.addAll(queryParameters) }

public fun <S : HasId<*>> Authentication<S>.limitToScopes(
    vararg scopes: String
): Authentication<S> =
    limitTo { this.scopes.addAll(scopes) }



public fun <S : HasId<*>> Authentication<S>.forbidEndpoints(
    vararg endpoints: HttpEndpoint<PathSpec>
): Authentication<S> =
    forbid { for (endpoint in endpoints) this.methods.getOrPut(endpoint.method, ::ArrayList).add(endpoint.path.toPredicate()) }

public fun <S : HasId<*>> Authentication<S>.forbidEndpoints(
    vararg endpoints: Locationed<HttpEndpoint<PathSpec>, *>
): Authentication<S> =
    forbid { for (endpoint in endpoints) this.methods.getOrPut(endpoint.location.method, ::ArrayList).add(endpoint.location.path.toPredicate()) }

@Suppress("FINAL_UPPER_BOUND") public fun <T : HttpMethod, S : HasId<*>> Authentication<S>.forbidMethods(
    vararg methods: T
): Authentication<S> =
    forbid { for (method in methods) this.methods.getOrPut(method, ::ArrayList).clear() }

public fun <S : HasId<*>> Authentication<S>.forbidHeaders(
    headers: HttpHeaders
): Authentication<S> =
    forbid { this.headers.set(headers) }

public fun <S : HasId<*>> Authentication<S>.forbidQueryParameters(
    vararg queryParameters: Pair<String, String>
): Authentication<S> =
    forbid { this.queryParameters.addAll(queryParameters) }

public fun <S : HasId<*>> Authentication<S>.forbidScopes(
    vararg scopes: String
): Authentication<S> =
    forbid { this.scopes.addAll(scopes) }