package com.lightningkite

import kotlinx.serialization.Serializable
import platform.Foundation.NSUUID

@Serializable(DeferToContextualUuidSerializer::class)
actual class UUID(val ns: NSUUID): Comparable<UUID> {
    actual override fun compareTo(other: UUID): Int = ns.compare(other.ns).toInt()
    override fun hashCode(): Int = ns.hashCode()
    override fun equals(other: Any?): Boolean = other is UUID && ns.equals(other.ns)
    override fun toString(): String = ns.UUIDString.lowercase()
    actual companion object {
        actual fun random(): UUID = UUID(NSUUID())
        actual fun parse(uuidString: String): UUID = UUID(NSUUID(uuidString))
    }
}

/**
 * Converts this [NSUUID] value to the corresponding [kotlin.uuid.Uuid] value.
 */
fun NSUUID.toKotlinUuid(): UUID = UUID(this)

/**
 * Converts this [kotlin.uuid.Uuid] value to the corresponding [NSUUID] value.
 */
fun UUID.toNSUUID(): NSUUID = ns
