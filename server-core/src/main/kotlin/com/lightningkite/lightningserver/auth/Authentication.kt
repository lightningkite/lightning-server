package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.core.LightningServerDsl
import com.lightningkite.lightningserver.exceptions.UnauthorizedException
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.Request

/**
 * Rules for authenticating requests are defined in this object.
 */
object Authentication {

    interface Method<T> : Comparable<Method<*>> {
        val priority: Int
        override fun compareTo(other: Method<*>): Int = this.priority.compareTo(other.priority)
        val type: AuthType
        val subjectType: SubjectType?
        val defersTo: AuthType? get() = null
        val fromStringInRequest: FromStringInRequest? get() = null
        suspend fun tryGet(on: Request): Auth<T>?
    }

    sealed interface FromStringInRequest {
        fun getString(request: Request): String?
        data class AuthorizationHeader(val prefix: String? = "Bearer") : FromStringInRequest {
            override fun getString(request: Request): String? = request.headers[HttpHeader.Authorization]?.let {
                if (prefix == null) it
                else if (it.startsWith(prefix)) it.removePrefix(prefix).trim()
                else null
            }
        }

        data class AuthorizationCookie(val prefix: String? = null) : FromStringInRequest {
            override fun getString(request: Request): String? = request.headers.cookies[HttpHeader.Authorization]?.let {
                if (prefix == null) it
                else if (it.startsWith(prefix)) it.removePrefix(prefix).trim()
                else null
            }
        }

        data class QueryParameter(val name: String) : FromStringInRequest {
            override fun getString(request: Request): String? =
                request.queryParameters.find { it.first.equals(name, true) }?.second
        }

        data class CustomHeader(val header: String) : FromStringInRequest {
            override fun getString(request: Request): String? = request.headers[header]
        }
    }

    data class Auth<T>(
        val value: T,
        val recentlyProven: Boolean,
        val scopes: Set<String>? = null
    ) {
        suspend fun <D> map(mapper: suspend (T) -> D) = Auth(
            value = mapper(value),
            recentlyProven = recentlyProven,
            scopes = scopes
        )

        suspend fun <D> mapMaybe(mapper: suspend (T) -> D?) = mapper(value)?.let {
            Auth(
                value = it,
                recentlyProven = recentlyProven,
                scopes = scopes
            )
        }
    }

    suspend fun any(request: Request): Auth<out Any?>? {
        methodsByType.entries.flatMap { it.value }
            .sortedBy { -it.priority }
            .forEach {
                return it.tryGet(request) ?: return@forEach
            }
        return null
    }

    private val knownMethods = HashSet<Method<*>>()
    private val methodsByType = HashMap<AuthType, MutableList<Method<*>>>()
    fun <T> register(method: Method<T>): Method<T> {
        if (!knownMethods.add(method)) return method
        println("Registering method for ${method.type} based on ${method.defersTo}")
        methodsByType.getOrPut(method.type) { ArrayList() }.let {
            it.add(method)
            it.sortDescending()
        }
        return method
    }

    fun methods(type: AuthType): List<Method<*>> = (methodsByType[type]?.toList() ?: listOf())

    @Suppress("UNCHECKED_CAST")
    data class Cache<T>(val type: AuthType) : Request.CacheKey<Auth<T>?> {
        override suspend fun calculate(request: Request): Auth<T>? {
            if(type == AuthType.none) return null
            methodsByType[type]?.let {
                for (method in it) {
                    println("Trying $method for $type")
                    return (method as Method<Any>).tryGet(request) as? Auth<T> ?: continue
                }
                println("Found no auth success for $type")
                return null
            } ?: throw Error("No authentication methods for ${type} are defined.")
        }
    }

    data class SubjectType(val name: String)
}

typealias RequestAuth<T> = Authentication.Auth<T>

@LightningServerDsl
fun <T> authentication(method: Authentication.Method<T>): Authentication.Method<T> = Authentication.register(method)

@LightningServerDsl
inline fun <reified A : Any, reified B : Any> authenticationMapper(
    priority: Int = (Authentication.methods(AuthType<A>()).maxOfOrNull { it.priority } ?: 0) - 1,
    subjectType: Authentication.SubjectType? = Authentication.methods(AuthType<A>()).mapNotNull { it.subjectType }
        .firstOrNull(),
    noinline map: suspend (A) -> B?
): Authentication.Method<B> = authenticationMapper(
    sourceType = AuthType<A>(),
    destType = AuthType<B>(),
    priority = priority,
    subjectType = subjectType,
    map = map
)

@LightningServerDsl
fun <A : Any, B : Any> authenticationMapper(
    sourceType: AuthType,
    destType: AuthType,
    priority: Int = (Authentication.methods(sourceType).maxOfOrNull { it.priority } ?: 0) - 1,
    subjectType: Authentication.SubjectType? = Authentication.methods(sourceType).mapNotNull { it.subjectType }
        .firstOrNull(),
    map: suspend (A) -> B?
): Authentication.Method<B> = Authentication.register(object : Authentication.Method<B> {
    override val priority: Int = priority
    override val type: AuthType = destType
    override val defersTo: AuthType = sourceType
    val defersToMethods get() = Authentication.methods(defersTo)
    override val subjectType: Authentication.SubjectType? = subjectType
    override suspend fun tryGet(on: Request): Authentication.Auth<B>? = on.auth<A>(defersTo)?.mapMaybe { map(it) }
})

suspend fun <T> Request.auth(type: AuthType): Authentication.Auth<T>? =
    this.cache(Authentication.Cache(type))

@Suppress("UNCHECKED_CAST")
suspend fun <T> Request.user(authRequirement: AuthRequirement<T>): T =
    auth<T>(authRequirement.type)?.value
        ?: if (authRequirement.required) throw UnauthorizedException("You must be authorized as a ${authRequirement.type}") else null as T

@Suppress("UNCHECKED_CAST")
suspend inline fun <reified T> Request.user(): T = user(AuthRequirement<T>())
