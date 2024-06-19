package com.lightningkite

import platform.Foundation.NSUUID

actual class UUID(val ns: NSUUID): Comparable<UUID> {
    override fun compareTo(other: UUID): Int = ns.compare(other.ns).toInt()
    override fun hashCode(): Int = ns.hashCode()
    override fun equals(other: Any?): Boolean = other is UUID && ns.equals(other.ns)
    override fun toString(): String = ns.UUIDString.lowercase()
}
actual fun uuid(): UUID = UUID(NSUUID())
actual fun uuid(string: String): UUID = UUID(NSUUID(string))