package com.lightningkite

import kotlinx.browser.window

actual data class UUIDRaw(val string: String): Comparable<UUIDRaw> {
    init {
        if(!uuidRegex.matches(string)) throw IllegalArgumentException("Invalid UUID string: $string")
    }
    actual override fun compareTo(other: UUIDRaw): Int = this.string.compareTo(other.string)
    override fun toString(): String = string
}
private val uuidRegex = Regex("^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}\$")
actual fun uuid(): UUIDRaw = UUIDRaw(window.asDynamic().crypto.randomUUID() as String)
actual fun uuid(string: String): UUIDRaw = UUIDRaw(string)
