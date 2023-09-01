package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.exceptions.UnauthorizedException
import com.lightningkite.lightningserver.http.Request
import java.util.HashMap

data class RequestAuth<SUBJECT : HasId<*>>(
    val subject: Authentication.SubjectHandler<SUBJECT, *>,
    val rawId: Any,
    val recentlyProven: Boolean,
    val scopes: Set<String>? = null,
    val cachedRaw: Map<String, String> = mapOf()
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
    suspend fun <T> get(key: CacheKey<SUBJECT, *, T>): T = cache.getOrPut(key.name) {
        if (cachedRaw.containsKey(key.name)) key.deserialize(cachedRaw.getValue(key.name))
        else key.calculate(this)
    } as T

    private suspend fun <T> getSer(key: CacheKey<SUBJECT, *, T>): String = key.serialize(get(key))
    suspend fun withCachedValues(vararg keys: CacheKey<SUBJECT, *, *>): RequestAuth<SUBJECT> {
        return copy(
            cachedRaw = keys.associate { it.name to getSer(it) }
        )
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun get() = (subject as Authentication.SubjectHandler<SUBJECT, Any>).fetch(rawId)
}

@Suppress("UNCHECKED_CAST")
inline val <SUBJECT : HasId<ID>, ID : Comparable<ID>> RequestAuth<SUBJECT>.id get() = rawId as ID

suspend fun Request.authAny(): RequestAuth<*>? = this.cache(RequestAuth.Key)
suspend fun <SUBJECT : HasId<*>> Request.auth(type: AuthType): RequestAuth<SUBJECT>? {
    val raw = authAny() ?: return null
    @Suppress("UNCHECKED_CAST")
    return if (raw.subject.authType.satisfies(type)) this as RequestAuth<SUBJECT>
    else null
}

//@Suppress("UNCHECKED_CAST")
//suspend fun <T : HasId<*>> Request.auth(authRequirement: AuthRequirement<T>): RequestAuth<T>  = authStar(authRequirement) as RequestAuth<T>
//@Suppress("UNCHECKED_CAST")
//suspend fun <T : HasId<*>> Request.auth(authRequirement: AuthRequirement<T?>): RequestAuth<T>? = authStar(authRequirement) as? RequestAuth<T>

@Suppress("UNCHECKED_CAST")
inline suspend fun <reified T : HasId<*>> Request.auth(): RequestAuth<T> = authStar(AuthRequirement<T>()) as RequestAuth<T>

@Suppress("UNCHECKED_CAST")
suspend fun <T> Request.authStar(authRequirement: AuthRequirement<T>): RequestAuth<*>? {
    val raw = authAny() ?: if (authRequirement.required)
        throw UnauthorizedException("You must be authorized as a ${authRequirement.type}")
    else
        return null
    if (raw.subject.authType.satisfies(authRequirement.type)) {
        return raw
    }
    if (authRequirement.required)
        throw UnauthorizedException("You must be authorized as a ${authRequirement.type}")
    else
        return null
}

@Suppress("UNCHECKED_CAST")
suspend fun <T> Request.user(authRequirement: AuthRequirement<T>): T = authStar(authRequirement)?.get() as T

@Suppress("UNCHECKED_CAST")
suspend inline fun <reified T> Request.user(): T = user(AuthRequirement<T>())
