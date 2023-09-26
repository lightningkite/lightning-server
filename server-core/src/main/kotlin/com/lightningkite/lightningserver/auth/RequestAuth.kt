package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningdb.Description
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.exceptions.ForbiddenException
import com.lightningkite.lightningserver.exceptions.UnauthorizedException
import com.lightningkite.lightningserver.http.Request
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class RequestAuth<SUBJECT : HasId<*>>(
    val subject: Authentication.SubjectHandler<SUBJECT, *>,
    val sessionId: UUID?,
    val rawId: Comparable<*>,
    val issuedAt: Instant,
    @Description("The scopes permitted.  Null indicates root access.")
    val scopes: Set<String>? = null,
    val cachedRaw: Map<String, String> = mapOf(),
    val thirdParty: String? = null,
) {
    object Key : Request.CacheKey<RequestAuth<*>?> {
        override suspend fun calculate(request: Request): RequestAuth<*>? {
            for (reader in Authentication.readers) {
                return reader.request(request) ?: continue
            }
            return null
        }
    }

    interface CacheKey<SUBJECT : HasId<ID>, ID : Comparable<ID>, VALUE> {
        val name: String
        suspend fun calculate(auth: RequestAuth<SUBJECT>): VALUE
        fun serialize(value: VALUE): String
        fun deserialize(string: String): VALUE
    }

    private val cache = HashMap<String, Any?>()
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> get(key: CacheKey<SUBJECT, *, T>): T = cache.getOrPut(key.name) {
        if (cachedRaw.containsKey(key.name)) key.deserialize(cachedRaw.getValue(key.name))
        else key.calculate(this)
    } as T

    private suspend fun <T> getSer(key: CacheKey<SUBJECT, *, T>): String = key.serialize(get(key))
    suspend fun withCachedValues(keys: List<CacheKey<SUBJECT, *, *>>): RequestAuth<SUBJECT> {
        return copy(
            cachedRaw = keys.associate { it.name to getSer(it) }
        )
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun get() = (subject as Authentication.SubjectHandler<HasId<Comparable<Any?>>, Comparable<Any?>>).fetch(rawId as Comparable<Any?>)
}

@Suppress("UNCHECKED_CAST")
inline val <SUBJECT : HasId<ID>, ID : Comparable<ID>> RequestAuth<SUBJECT>.id get() = rawId as ID

suspend fun Request.authAny(): RequestAuth<*>? = this.cache(RequestAuth.Key)
suspend fun <SUBJECT : HasId<*>> Request.auth(type: AuthType): RequestAuth<SUBJECT>? {
    val raw = authAny() ?: return null
    @Suppress("UNCHECKED_CAST")
    return if (raw.subject.authType.satisfies(type)) raw as RequestAuth<SUBJECT>
    else null
}

@Suppress("UNCHECKED_CAST")
suspend fun <USER: HasId<*>?> Request.authChecked(authOptions: AuthOptions<USER>): RequestAuth<USER & Any>? {
    val raw = authAny() ?: if(authOptions.options.any { it == null }) return null else throw UnauthorizedException("You must be authorized as a ${authOptions.options.joinToString { it!!.type.authName ?: "???" }}")
    if(authOptions.options.any { it == null || it.accepts(raw) }) return raw as RequestAuth<USER & Any>
    else throw ForbiddenException("You do not match the authorization criteria.")
}

suspend fun AuthOption.accepts(auth: RequestAuth<*>): Boolean = (this.type == auth.subject.authType || this.type == AuthType.any) &&
        (auth.scopes == null || (this.scopes != null && auth.scopes.containsAll(this.scopes))) &&
        (maxAge == null || Duration.between(auth.issuedAt, Instant.now()) < maxAge) &&
        (this.additionalRequirement(auth))

suspend inline fun <reified T> Request.user(): T = authAny()?.get() as T

suspend fun <USER: HasId<*>?> AuthOptions<USER>.accepts(auth: RequestAuth<*>?): Boolean = null in this.options || (auth != null && this.options.any { it?.accepts(auth) ?: false })
