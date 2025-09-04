

package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningdb.Description
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.exceptions.ForbiddenException
import com.lightningkite.lightningserver.exceptions.UnauthorizedException
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.Request
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.now
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlin.time.Duration
import kotlinx.datetime.Instant
import com.lightningkite.UUID
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpMethod
import com.lightningkite.lightningserver.serialization.encodeUnwrappingString
import com.lightningkite.lightningserver.typed.TypedHttpEndpoint
import com.lightningkite.lightningserver.typed.TypedServerPath0
import com.lightningkite.lightningserver.typed.TypedServerPath1
import com.lightningkite.lightningserver.typed.TypedServerPath2
import io.ktor.http.encodeURLPathPart
import io.ktor.http.encodeURLQueryComponent
import kotlinx.serialization.Contextual
import kotlin.time.Duration.Companion.days

data class RequestAuth<out SUBJECT : HasId<*>>(
    val subject: Authentication.SubjectHandler<@UnsafeVariance SUBJECT, *>,
    val sessionId: UUID?,
    val rawId: Comparable<*>,
    val issuedAt: Instant,
    @Description("The scopes permitted.  * indicates root access.")
    val scopes: Set<String> = setOf("*"),
    val thirdParty: String? = null,
    val fromMasquerade: RequestAuth<*>? = null,
    val requirements: RequestRequirements? = null,
) {
    override fun toString(): String = buildString {
        fromMasquerade?.let {
            append(it)
            append(" masquerading as ")
        }
        append(subject.name)
        append(' ')
        append(rawId)
        sessionId?.let {
            append(" (")
            append(it)
            append(")")
        }
        thirdParty?.let {
            append(" via ")
            append(it)
        }
        requirements?.let {
            append(" (presigned)")
        }
    }

    object Key : Request.CacheKey<RequestAuth<*>?> {
        override suspend fun calculate(request: Request): RequestAuth<*>? {
            for (reader in Authentication.readers) {
                return reader.request(request)?.let {
                    request.headers[HttpHeader.XMasquerade]?.let { m ->
                        val otherType = m.substringBefore('/')
                        val otherHandler = Authentication.subjects.values.find { it.name == otherType }
                            ?: throw BadRequestException("No subject type ${otherType} known")
                        val otherId = Serialization.fromString(m.substringAfter('/'), otherHandler.idSerializer)
                        @Suppress("UNCHECKED_CAST")
                        if (it.permitMasquerade(
                                otherHandler as Authentication.SubjectHandler<HasId<Comparable<Any?>>, Comparable<Any?>>,
                                otherId
                            )
                        ) {
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
                }?.also {
                    it.requirements?.assert(request)
                } ?: continue
            }
            return null
        }
    }

    private suspend fun permitMasquerade(other: Authentication.SubjectHandler<*, *>, otherId: Comparable<*>): Boolean {
        @Suppress("UNCHECKED_CAST")
        return (subject as Authentication.SubjectHandler<HasId<Comparable<Any?>>, Comparable<Any?>>).permitMasquerade(
            other,
            this as RequestAuth<HasId<Comparable<Any?>>>,
            otherId
        )
    }

    abstract class CacheKey<out SUBJECT : HasId<@UnsafeVariance ID>, out ID : Comparable<@UnsafeVariance ID>, VALUE> {
        abstract val name: String
        abstract suspend fun calculate(auth: RequestAuth<@UnsafeVariance SUBJECT>): VALUE
        abstract val serializer: KSerializer<VALUE>
        abstract val validFor: Duration
        var serializationIndex: Int = -1
            private set

        companion object {
            private val _allCacheKeys = ArrayList<CacheKey<*, *, *>>()
            private var used: Exception? = null
            val allCacheKeys: List<CacheKey<*, *, *>>
                get() {
                    if (used == null) {
                        used = Exception("WARN: Cache key not added!  Cache keys were already used here:")
                        _allCacheKeys.sortBy { it.name }
                        _allCacheKeys.forEachIndexed { index, cacheKey -> cacheKey.serializationIndex = index }
                    }
                    return _allCacheKeys
                }
        }

        init {
            used?.printStackTrace() ?: _allCacheKeys.add(this)
        }

        override fun toString(): String = name
    }

    @Serializable
    data class ExpiringValue<T>(val value: T, val expiresAt: Instant)

    val cache: HashMap<CacheKey<@UnsafeVariance SUBJECT, *, *>, ExpiringValue<*>> =
        HashMap<CacheKey<SUBJECT, *, *>, ExpiringValue<*>>()

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> get(key: CacheKey<@UnsafeVariance SUBJECT, *, T>): T {
        cache.get(key)?.let {
            if (now() > it.expiresAt) cache.remove(key)
            else return it.value as T
        }
        val c = key.calculate(this)
        cache.put(key, ExpiringValue(c, now().plus(key.validFor)))
        return c
    }

    suspend fun precache(keys: List<CacheKey<@UnsafeVariance SUBJECT, *, *>>): RequestAuth<SUBJECT> {
        keys.forEach { get(it) }
        return this
    }

    private lateinit var rawSubject: SUBJECT
    private var rawExpiresAt: Instant = Instant.DISTANT_PAST

    private suspend fun getRawSubject(): SUBJECT {
        if (now() > rawExpiresAt) {
            @Suppress("UNCHECKED_CAST")
            rawSubject =
                (subject as Authentication.SubjectHandler<HasId<Comparable<Any?>>, Comparable<Any?>>).fetch(rawId as Comparable<Any?>) as SUBJECT
            rawExpiresAt = now().plus(subject.subjectCacheExpiration)
        }
        return rawSubject
    }

    suspend fun get() = getRawSubject()

    fun clearCache(): RequestAuth<SUBJECT> {
        cache.clear()
        return this
    }

    companion object

    @Serializable
    data class RequestRequirements(
        val method: HttpMethod,
        val path: ServerPath,
        val parts: Map<String, String>,
        val queryParameters: List<Pair<String, String>>?,
        val forceHeaders: HttpHeaders,
        val limitToHeaders: Set<String>? = null,
        val until: Instant = now() + 7.days
    ) {
        fun assert(request: Request) {
            if (request.path != path) throw UnauthorizedException("Auth path must be '${path}', but request is for ${request.path}")
            if (request.parts != parts) throw UnauthorizedException("Auth parts must be '${parts}', but request is for ${request.parts}")
            if (request.method != method) throw UnauthorizedException("Auth method must be ${method}, but request is for ${request.method}")
            if (queryParameters != null && request.queryParameters.filter { !it.first.equals(HttpHeader.Authorization, true) } != queryParameters) throw UnauthorizedException("Auth query parameters must be ${queryParameters}, but request is for ${request.queryParameters}")
            if (limitToHeaders != null) {
                val fh = forceHeaders.normalizedEntries.keys
                request.headers.normalizedEntries.forEach {
                    if(it.key.equals(HttpHeader.Authorization, true)) return@forEach
                    if (it.key !in limitToHeaders && it.key !in fh) throw UnauthorizedException("Auth disallows header ${it.key}")
                    forceHeaders.get(it.key)?.let { v ->
                        if (it.value.joinToString(";") != v) throw UnauthorizedException(
                            "Auth requires header ${it.key} to be exactly ${v}, but got ${
                                it.value.joinToString(
                                    ";"
                                )
                            }"
                        )
                    }
                }
            }
            if (now() > until) throw UnauthorizedException("This authorization has expired.")
        }

        val pathPlusQueryParameters =
            path.toString(parts) + if (queryParameters.isNullOrEmpty()) "" else "?${queryParameters.joinToString("&") { "${it.first}=${it.second.encodeURLQueryComponent()}" }}"
        val pathPlusQueryParametersAnd =
            path.toString(parts) + if (queryParameters.isNullOrEmpty()) "?" else "?${queryParameters.joinToString("&") { "${it.first}=${it.second.encodeURLQueryComponent()}" }}&"
    }
}

fun HttpEndpoint.toRequestRequirements(
    parts: Map<String, String> = mapOf(),
    queryParameters: List<Pair<String, String>>? = null,
    forceHeaders: HttpHeaders = HttpHeaders.EMPTY,
    limitToHeaders: Set<String>? = null,
    until: Instant = now() + 7.days
) = RequestAuth.RequestRequirements(
    path = path,
    method = method,
    parts = parts,
    queryParameters = queryParameters,
    forceHeaders = forceHeaders,
    limitToHeaders = limitToHeaders,
    until = until,
)

fun TypedHttpEndpoint<TypedServerPath0>.toRequestRequirements(
    queryParameters: List<Pair<String, String>>? = null,
    forceHeaders: HttpHeaders,
    limitToHeaders: Set<String>? = null,
    until: Instant = now() + 7.days
) = RequestAuth.RequestRequirements(
    path = path.path,
    method = endpoint.method,
    parts = mapOf(),
    queryParameters = queryParameters,
    forceHeaders = forceHeaders,
    limitToHeaders = limitToHeaders,
    until = until,
)

fun <A> TypedHttpEndpoint<TypedServerPath1<A>>.toRequestRequirements(
    a: A,
    queryParameters: List<Pair<String, String>>? = null,
    forceHeaders: HttpHeaders,
    limitToHeaders: Set<String>? = null,
    until: Instant = now() + 7.days
) = RequestAuth.RequestRequirements(
    path = path.path,
    method = endpoint.method,
    parts = mapOf(path.a.name to Serialization.toString(a, path.a.serializer).encodeURLPathPart()),
    queryParameters = queryParameters,
    forceHeaders = forceHeaders,
    limitToHeaders = limitToHeaders,
    until = until,
)
fun <A, B> TypedHttpEndpoint<TypedServerPath2<A, B>>.toRequestRequirements(
    a: A,
    b: B,
    queryParameters: List<Pair<String, String>>? = null,
    forceHeaders: HttpHeaders,
    limitToHeaders: Set<String>? = null,
    until: Instant = now() + 7.days
) = RequestAuth.RequestRequirements(
    path = path.path,
    method = endpoint.method,
    parts = mapOf(
        path.a.name to Serialization.toString(a, path.a.serializer).encodeURLPathPart(),
        path.b.name to Serialization.toString(b, path.b.serializer).encodeURLPathPart()
    ),
    queryParameters = queryParameters,
    forceHeaders = forceHeaders,
    limitToHeaders = limitToHeaders,
    until = until,
)

@Suppress("UNCHECKED_CAST")
inline val <SUBJECT : HasId<ID>, ID : Comparable<ID>> RequestAuth<SUBJECT>.id get() = rawId as ID

@Suppress("UNCHECKED_CAST")
val <SUBJECT : HasId<*>> RequestAuth<SUBJECT>.idString: String
    get() = Serialization.json.encodeUnwrappingString(
        subject.idSerializer as KSerializer<Any?>,
        rawId
    )

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

@Suppress("UNCHECKED_CAST")
suspend fun <USER : HasId<*>?> AuthOptions<USER>.assert(auth: RequestAuth<*>?): RequestAuth<USER & Any>? {
    val raw = auth
        ?: if (options.any { it == null }) return null else throw UnauthorizedException("You must be authorized as a ${options.joinToString { it!!.type.authName ?: "???" }}, but you are not authorized at all.")
    if (options.any { it == null || it.accepts(raw) }) return raw as RequestAuth<USER & Any>
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

@Suppress("UNCHECKED_CAST", "UNNECESSARY_NOT_NULL_ASSERTION")
fun <USER : HasId<*>?> RequestAuth.Companion.test(
    item: USER,
    scopes: Set<String> = setOf("*"),
    thirdParty: String? = null
) = if (item == null) null else RequestAuth<USER & Any>(
    subject = Authentication.subjects[AuthType(
        item!!::class,
        listOf()
    )] as? Authentication.SubjectHandler<USER & Any, *>
        ?: throw IllegalStateException("Type ${item!!::class.qualifiedName} has no registered subject handler.  Subject handlers: ${Authentication.subjects.keys.joinToString()}"),
    sessionId = null,
    rawId = item._id,
    issuedAt = now(),
    scopes = scopes,
    thirdParty = thirdParty
)