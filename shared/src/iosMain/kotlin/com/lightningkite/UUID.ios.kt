package com.lightningkite

import platform.Foundation.NSUUID

actual class UUIDRaw(val ns: NSUUID): Comparable<UUIDRaw> {
    actual override fun compareTo(other: UUIDRaw): Int = ns.compare(other.ns).toInt()
    override fun hashCode(): Int = ns.hashCode()
    override fun equals(other: Any?): Boolean = other is UUID && ns.equals(other.ns)
    override fun toString(): String = ns.UUIDString.lowercase()
}
actual fun uuid(): UUID = UUIDRaw(NSUUID())
actual fun uuid(string: String): UUID = UUIDRaw(NSUUID(string))