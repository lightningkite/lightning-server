@file:UseContextualSerialization(Instant::class)
package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningdb.Description
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.exceptions.ForbiddenException
import com.lightningkite.lightningserver.exceptions.UnauthorizedException
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.Request
import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.datetime.Clock
import com.lightningkite.now
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlin.time.Duration
import kotlinx.datetime.Instant
import com.lightningkite.UUID
import com.lightningkite.uuid

data class RequestAuth<SUBJECT : HasId<*>>(
    val subject: Authentication.SubjectHandler<SUBJECT, *>,
    val sessionId: UUID?,
    val rawId: Comparable<*>,
    val issuedAt: Instant,
    @Description("The scopes permitted.  * indicates root access.")
    val scopes: Set<String> = setOf("*"),
    val thirdParty: String? = null,
    val fromMasquerade: RequestAuth<*>? = null,
) {
    object Key : Request.CacheKey<RequestAuth<*>?> {
        override suspend fun calculate(request: Request): RequestAuth<*>? {
            for (reader in Authentication.readers) {
                println("Check read $reader")
                return reader.request(request)?.let {
                    println("REQ RES: $it")
                    request.headers[HttpHeader.XMasquerade]?.let { m ->
                        println("CHECK THE MASQ")
                        val otherType = m.substringBefore('/')
                        val otherHandler = Authentication.subjects.values.find { it.name == otherType } ?: throw BadRequestException("No subject type ${otherType} known")
                        val otherId = Serialization.fromString(m.substringAfter('/'), otherHandler.idSerializer)
                        @Suppress("UNCHECKED_CAST")
                        if(it.permitMasquerade(
                            otherHandler as Authentication.SubjectHandler<HasId<Comparable<Any?>>, Comparable<Any?>>,
                            otherId
                        )) {
                            println("DO THE MASQ")
                            RequestAuth(
                                subject = otherHandler,
                                sessionId = it.sessionId,
                                rawId = otherId,
                                issuedAt = it.issuedAt,
                                scopes = it.scopes,
                                thirdParty = "${it.subject.name} ${it.rawId} masquerading",
                                fromMasquerade = it
                            )
                        } else {
                            throw ForbiddenException()
                        }
                    } ?: it
                } ?: continue
            }
            return null
        }
    }

    private suspend fun permitMasquerade(other: Authentication.SubjectHandler<*, *>, otherId: Comparable<*>): Boolean {
        @Suppress("UNCHECKED_CAST")
        return (subject as Authentication.SubjectHandler<HasId<Comparable<Any?>>, Comparable<Any?>>).permitMasquerade(other, rawId as Comparable<Any?>, otherId)
    }

    abstract class CacheKey<SUBJECT : HasId<ID>, ID : Comparable<ID>, VALUE> {
        abstract val name: String
        abstract suspend fun calculate(auth: RequestAuth<SUBJECT>): VALUE
        abstract val serializer: KSerializer<VALUE>
        abstract val validFor: Duration
        companion object {
            private val _allCacheKeys = ArrayList<CacheKey<*, *, *>>()
            private var used: Boolean = false
            val allCacheKeys: List<CacheKey<*, *, *>> get() {
                if(!used) {
                    used = true
                    _allCacheKeys.sortBy { it.name }
                }
                return _allCacheKeys
            }
        }
        init {
            if(used) println("WARN: Cache key not added!")
            else _allCacheKeys.add(this)
        }

        override fun toString(): String = name
    }

    @Serializable
    data class ExpiringValue<T>(val value: T, val expiresAt: Instant)
    val cache = HashMap<CacheKey<SUBJECT, *, *>, ExpiringValue<*>>()

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> get(key: CacheKey<SUBJECT, *, T>): T {
        cache.get(key)?.let {
            if(now() > it.expiresAt) cache.remove(key)
            else return it.value as T
        }
        val c = key.calculate(this)
        cache.put(key, ExpiringValue(c, now().plus(key.validFor)))
        return c
    }

    suspend fun precache(keys: List<CacheKey<SUBJECT, *, *>>): RequestAuth<SUBJECT> {
        keys.forEach { get(it) }
        return this
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun get() =
        (subject as Authentication.SubjectHandler<HasId<Comparable<Any?>>, Comparable<Any?>>).fetch(rawId as Comparable<Any?>) as SUBJECT

    fun clearCache(): RequestAuth<SUBJECT> {
        cache.clear()
        return this
    }

    companion object
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
suspend fun <USER : HasId<*>?> Request.authChecked(authOptions: AuthOptions<USER>): RequestAuth<USER & Any>? {
    val raw = authAny()
        ?: if (authOptions.options.any { it == null }) return null else throw UnauthorizedException("You must be authorized as a ${authOptions.options.joinToString { it!!.type.authName ?: "???" }}, but you are not authorized at all.")
    if (authOptions.options.any { it == null || it.accepts(raw) }) return raw as RequestAuth<USER & Any>
    else throw ForbiddenException("You do not match the authorization criteria.")
}

suspend fun AuthOption.accepts(auth: RequestAuth<*>): Boolean =
    (this.type == auth.subject.authType || this.type == AuthType.any) &&
            (this.scopes == null || "*" in auth.scopes || auth.scopes.containsAll(this.scopes)) &&
            (maxAge == null || now() - auth.issuedAt < maxAge) &&
            (this.additionalRequirement(auth))

suspend inline fun <reified T> Request.user(): T = authAny()?.get() as T

suspend fun <USER : HasId<*>?> AuthOptions<USER>.accepts(auth: RequestAuth<*>?): Boolean =
    null in this.options || (auth != null && this.options.any { it?.accepts(auth) ?: false })

@Suppress("UNCHECKED_CAST")
fun <USER: HasId<*>?> RequestAuth.Companion.test(item: USER, scopes: Set<String> = setOf("*"), thirdParty: String? = null) = if(item == null) null else RequestAuth<USER & Any>(
    subject = Authentication.subjects[AuthType(item!!::class, listOf())] as? Authentication.SubjectHandler<USER & Any, *> ?: throw IllegalStateException("Type ${item!!::class.qualifiedName} has no registered subject handler.  Subject handlers: ${Authentication.subjects.keys.joinToString()}"),
    sessionId = null,
    rawId = item._id,
    issuedAt = now(),
    scopes = scopes,
    thirdParty = thirdParty
)