package com.lightningkite

import kotlinx.browser.window
import kotlinx.serialization.Serializable

@Serializable(DeferToContextualUuidSerializer::class)
actual data class UUID(val string: String): Comparable<UUID> {
    init {
        if(!uuidRegex.matches(string)) throw IllegalArgumentException("Invalid UUID string: $string")
    }
    actual override fun compareTo(other: UUID): Int = this.string.compareTo(other.string)
    override fun toString(): String = string
    actual companion object {
        private val uuidRegex = Regex("^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}\$")
        actual fun random(): UUID = UUID(window.asDynamic().crypto.randomUUID() as String)
        actual fun parse(uuidString: String): UUID = UUID(uuidString)
    }
}