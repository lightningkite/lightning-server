package com.lightningkite.lightningserver.auth.subject

import com.lightningkite.UUID

@JvmInline
value class RefreshToken(val string: String) {
    companion object {
        val prefix = "refresh/"
    }
    constructor(type: String, _id: UUID, secret: String) : this("$prefix$type/$_id:$secret")

    val valid: Boolean get() = string.startsWith(prefix)
    val type: String get() = string.drop(prefix.length).substringBefore('/')
    val _id: UUID
        get() = java.util.UUID.fromString(
            string.drop(prefix.length).substringAfter('/').substringBefore(':', "")
        )
    val plainTextSecret: String get() = string.drop(prefix.length).substringAfter('/').substringAfter(':', "")
    override fun toString(): String = string
}