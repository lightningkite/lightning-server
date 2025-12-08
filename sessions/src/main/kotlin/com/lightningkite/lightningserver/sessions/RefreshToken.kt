package com.lightningkite.lightningserver.sessions

import kotlin.uuid.Uuid

@JvmInline
public value class RefreshToken(public val string: String) {
    public companion object {
        public const val PREFIX: String = "refresh/"
    }

    public constructor(type: String, _id: Uuid, secret: String) : this("$PREFIX$type/$_id:$secret")

    public val valid: Boolean get() = string.startsWith(PREFIX)
    public val type: String get() = string.drop(PREFIX.length).substringBefore('/')
    public val _id: Uuid
        get() = Uuid.parse(
            string.drop(PREFIX.length).substringAfter('/').substringBefore(':', "")
        )

    public val plainTextSecret: String get() = string.drop(PREFIX.length).substringAfter('/').substringAfter(':', "")

    override fun toString(): String = string
}