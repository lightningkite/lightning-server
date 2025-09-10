package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.data.SerializableCache
import com.lightningkite.lightningserver.definition.Locationed
import com.lightningkite.lightningserver.definition.builder.ListRegistry
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.getValue
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.HttpMethod
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

public val ServerBuilder.authReaders: ListRegistry<Authentication.Reader<*>> by Authentication.Reader


context(server: ServerRuntime)
public fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> PrincipalType<SUBJECT, ID>.testAuth(
    subject: SUBJECT,
    issuedAt: Instant = server.clock.now(),
    scopes: Set<GrantedScope> = GrantedScopes.root
): Authentication<SUBJECT> = Authentication(this, id = subject._id, issuedAt = issuedAt, scopes = scopes, sessionId = null)

public fun Authentication<*>.meetsRequirements(scopes: Set<RequiredScope>): Boolean = this.scopes.meetsRequirements(scopes)