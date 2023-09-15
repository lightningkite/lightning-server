package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.auth.RequestAuth
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.websocket.WebSocketIdentifier
import com.lightningkite.lightningserver.websocket.WebSockets
import kotlinx.serialization.KSerializer

open class AuthAccessor<USER: HasId<*>?>(
    val authOrNull: RequestAuth<USER & Any>?
) {
    @Suppress("UNCHECKED_CAST")
    suspend fun user() = authOrNull?.get() as USER
}

open class AuthAndPathParts<USER: HasId<*>?, PATH: TypedServerPath>(
    authOrNull: RequestAuth<USER & Any>?,
    val parts: Array<Any?>
): AuthAccessor<USER>(authOrNull) {
}

open class AuthPathPartsAndConnect<USER: HasId<*>?, PATH: TypedServerPath, OUTPUT>(
    authOrNull: RequestAuth<USER & Any>?,
    parts: Array<Any?>,
    val event: WebSockets.ConnectEvent,
    val outputSerializer: KSerializer<OUTPUT>,
): AuthAndPathParts<USER, PATH>(authOrNull, parts) {
    suspend fun send(value: OUTPUT) {
        event.id.send(Serialization.json.encodeToString(outputSerializer, value))
    }
    val socketId: WebSocketIdentifier get() = event.id
}

@Suppress("UNCHECKED_CAST") inline val <USER: HasId<*>> AuthAccessor<USER>.auth: RequestAuth<USER> get() = this.authOrNull!!
@get:JvmName("path1TypedServerPath1") @Suppress("UNCHECKED_CAST") inline val <USER: HasId<*>?, A> AuthAndPathParts<USER, TypedServerPath1<A>>.path1: A get() = this.parts[0] as A
@get:JvmName("path1TypedServerPath2") @Suppress("UNCHECKED_CAST") inline val <USER: HasId<*>?, A, B> AuthAndPathParts<USER, TypedServerPath2<A, B>>.path1: A get() = this.parts[0] as A
@get:JvmName("path2TypedServerPath2") @Suppress("UNCHECKED_CAST") inline val <USER: HasId<*>?, A, B> AuthAndPathParts<USER, TypedServerPath2<A, B>>.path2: B get() = this.parts[1] as B
@get:JvmName("path1TypedServerPath3") @Suppress("UNCHECKED_CAST") inline val <USER: HasId<*>?, A, B, C> AuthAndPathParts<USER, TypedServerPath3<A, B, C>>.path1: A get() = this.parts[0] as A
@get:JvmName("path2TypedServerPath3") @Suppress("UNCHECKED_CAST") inline val <USER: HasId<*>?, A, B, C> AuthAndPathParts<USER, TypedServerPath3<A, B, C>>.path2: B get() = this.parts[1] as B
@get:JvmName("path3TypedServerPath3") @Suppress("UNCHECKED_CAST") inline val <USER: HasId<*>?, A, B, C> AuthAndPathParts<USER, TypedServerPath3<A, B, C>>.path3: C get() = this.parts[2] as C
