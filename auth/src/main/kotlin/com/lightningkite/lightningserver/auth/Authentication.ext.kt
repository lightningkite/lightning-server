package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.data.SerializableCache
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.ListRegistry
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.database.HasId
import kotlin.time.Instant


public typealias AuthCacheKey<SUBJECT, T> = SerializableCache.CalculatingKey<Authentication<SUBJECT>, T>

context(_: ServerRuntime)
public suspend fun <SUBJECT : HasId<ID>, ID : Comparable<ID>, T> Authentication<SUBJECT>.get(
    key: AuthCacheKey<SUBJECT, T>,
): T = cache.get(key, this)


private object AuthReaders : ListRegistryExtension<Authentication.Reader<*>>

public val ServerBuilder.authReaders: ListRegistry<Authentication.Reader<*>> by AuthReaders
public val ServerDefinition.authReaders: List<Authentication.Reader<*>> by AuthReaders


context(server: ServerRuntime)
public fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> PrincipalType<SUBJECT, ID>.testAuth(
    subject: SUBJECT,
    issuedAt: Instant = server.clock.now(),
    scopes: Set<GrantedScope> = setOf(GrantedScope.root),
): Authentication<SUBJECT> =
    Authentication(this, id = subject._id, issuedAt = issuedAt, expiration = null, scopes = scopes, sessionId = null)

public fun Authentication<*>.meetsRequirements(scopes: Set<RequiredScope>): Boolean =
    this.scopes.meetsRequirements(scopes)

context(server: ServerRuntime)
public fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> Authentication(
    principalType: PrincipalType<SUBJECT, ID>,
    subject: SUBJECT,
    sessionId: String?,
    issuedAt: Instant = server.clock.now(),
    expiration: Instant? = null,
    scopes: Set<GrantedScope> = setOf(GrantedScope.root),
    fromMasquerade: Authentication<*>? = null,
): Authentication<SUBJECT> =
    Authentication(
        principalType,
        subject._id,
        sessionId,
        issuedAt,
        expiration,
        scopes,
        fromMasquerade,
        SerializableCache().apply {
            set(principalType.subjectCacheKey, subject)
        }
    )