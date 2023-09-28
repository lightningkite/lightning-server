package com.lightningkite

import kotlinx.browser.window

actual data class UUID(val string: String): Comparable<UUID> {
    override fun compareTo(other: UUID): Int = this.string.compareTo(other.string)
    override fun toString(): String = string
}
actual fun uuid(): UUID = UUID(window.asDynamic().crypto.randomUUID() as String)
actual fun uuid(string: String): UUID = UUID(string)
