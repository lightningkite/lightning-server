package com.lightningkite.lightningserver.typed

import kotlin.reflect.KClass

/**
 * Annotation linking an interface to its "live" client implementation class.
 *
 * Used during SDK generation to associate abstract API interfaces with their concrete
 * implementations that perform actual HTTP/WebSocket communication.
 *
 * Example:
 * ```kotlin
 * @LiveVersion(LiveUserApi::class)
 * interface UserApi {
 *     suspend fun getUser(id: String): User
 * }
 *
 * class LiveUserApi(val fetcher: Fetcher) : UserApi {
 *     override suspend fun getUser(id: String): User = fetcher(...)
 * }
 * ```
 *
 * @property live The KClass of the concrete "live" implementation
 */
@Target(AnnotationTarget.CLASS)
public annotation class LiveVersion(val live: KClass<*>)