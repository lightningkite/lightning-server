package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.websocket.WebSockets
import java.time.Duration

class JwtTypedAuthorizationHandler(val jwt: ()-> JwtSigner): Authorization.Handler<Any> {

    companion object {
        fun current(jwt: ()-> JwtSigner): JwtTypedAuthorizationHandler {
            val existing = (Authorization.handler as? JwtTypedAuthorizationHandler)
            if(existing != null) return existing
            val created = JwtTypedAuthorizationHandler(jwt)
            Authorization.handler = created
            return created
        }
    }

    override suspend fun http(request: HttpRequest): Any? = request.jwt()
        ?.let { jwt().verify(it) }
        ?.let { retrieve(it) }

    override suspend fun ws(request: WebSockets.ConnectEvent): Any? = request.jwt()
        ?.let { jwt().verify(it) }
        ?.let { retrieve(it) }

    override suspend fun idStringToUser(id: String): Any = retrieve(id)
    override fun userToIdString(user: Any): String = serializeReference(user)

    fun serializeReference(item: Any): String = types.mapNotNull { t -> t.trySerializeReference(item)?.let { t.name + "|" + it } }.firstOrNull() ?: throw IllegalStateException("Type of $item not recognized.  Supported types: ${types.joinToString { it.name }}")
    suspend fun retrieve(reference: String): Any {
        return if(reference.contains('|')) {
            val type = reference.substringBefore('|')
            val content = reference.substringAfter('|')
            types.find { it.name == type }!!.retrieve(content)
        } else {
            defaultType!!.retrieve(reference)
        }
    }

    var defaultType: AuthType<*>? = null
    val types: MutableList<AuthType<*>> = ArrayList()

    interface AuthType<T: Any> {
        val name: String
        fun tryCast(item: Any): T?
        fun serializeReference(item: T): String
        suspend fun retrieve(reference: String): T
    }
    fun <T: Any> AuthType<T>.trySerializeReference(any: Any) = tryCast(any)?.let { serializeReference(it) }

    fun token(user: Any, expireDuration: Duration = jwt().expiration): String = jwt().token(serializeReference(user), expireDuration)
}