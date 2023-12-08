package com.lightningkite

import kotlinx.browser.window

actual data class UUID(val string: String): Comparable<UUID> {
    init {
        if(!uuidRegex.matches(string)) throw IllegalArgumentException("Invalid UUID string: $string")
    }
    override fun compareTo(other: UUID): Int = this.string.compareTo(other.string)
    override fun toString(): String = string
}
private val uuidRegex = Regex("^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}\$")
actual fun uuid(): UUID = UUID(window.asDynamic().crypto.randomUUID() as String)
actual fun uuid(string: String): UUID = UUID(string)
