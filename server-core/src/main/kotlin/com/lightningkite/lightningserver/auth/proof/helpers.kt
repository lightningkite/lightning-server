package com.lightningkite.lightningserver.auth.proof

import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.cache.Cache
import com.lightningkite.lightningserver.cache.get
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serialization.encodeUnwrappingString
import com.lightningkite.now
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

suspend inline fun <R> Cache.constrainAttemptRate(
    cacheKey: String,
    count: Int = 5,
    expires: Duration = 5.minutes,
    action: ()->R
): R {
    val ct = (this.get<Int>(cacheKey) ?: 0)
    if (ct > count) throw BadRequestException("Too many attempts; please wait 5 minutes.")
    this.add(cacheKey, 1, expires)
    val result = action()
    remove(cacheKey)
    return result
}

fun <ID: Comparable<ID>> Authentication.SubjectHandler<*, ID>.idString(id: ID): String {
    return Serialization.json.encodeUnwrappingString(idSerializer, id)
}
suspend fun <T: HasId<ID>, ID: Comparable<ID>> Authentication.SubjectHandler<T, ID>.findUserIdString(property: String, value: String): String? {
    return findUser(property, value)?.let { idString(it._id) }
}