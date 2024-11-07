package com.lightningkite

import kotlinx.browser.window
import kotlinx.serialization.Serializable
import org.khronos.webgl.Uint8Array

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
        private val charmap = "0123456789abcdef"
        actual fun parse(bytes: ByteArray): UUID {
            var out = ""
            var i = 0
            repeat(4) {
                val byte = bytes[i++].toUByte().toInt()
                out += charmap[byte.shr(4).and(0xF)]
                out += charmap[byte.and(0xF)]
            }
            out += "-"
            repeat(2) {
                val byte = bytes[i++].toUByte().toInt()
                out += charmap[byte.shr(4).and(0xF)]
                out += charmap[byte.and(0xF)]
            }
            out += "-"
            repeat(2) {
                val byte = bytes[i++].toUByte().toInt()
                out += charmap[byte.shr(4).and(0xF)]
                out += charmap[byte.and(0xF)]
            }
            out += "-"
            repeat(2) {
                val byte = bytes[i++].toUByte().toInt()
                out += charmap[byte.shr(4).and(0xF)]
                out += charmap[byte.and(0xF)]
            }
            out += "-"
            repeat(6) {
                val byte = bytes[i++].toUByte().toInt()
                out += charmap[byte.shr(4).and(0xF)]
                out += charmap[byte.and(0xF)]
            }
            return UUID(out)
        }
    }

    actual fun toBytes(): ByteArray {
        var i = 0
        //ce73b77c-f085-47aa-b255-08b33cf7ca98
        return ByteArray(16) { it ->
            val out = string.substring(i, i + 2).toInt(16).toByte()
            i += 2
            if(i == 8) i++
            if(i == 13) i++
            if(i == 18) i++
            if(i == 23) i++
            out
        }
    }
}