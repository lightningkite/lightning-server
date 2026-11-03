package com.lightningkite.lightningserver.typed

/**
 * Name/value pairs sent with every request: as headers over HTTP, and as query parameters over
 * WebSockets, since browsers cannot set WebSocket headers.
 *
 * Names are compared ignoring case, like HTTP headers, and sent as written. A name can have several
 * values, but most metadata has one, so prefer [set] over [append].
 */
public class RequestMetadata(entries: List<Pair<String, String>> = emptyList()) {
    private val entries = entries.toMutableList()

    /** Replaces every value of [name] with [value]. */
    public fun set(name: String, value: String) {
        remove(name)
        entries.add(name to value)
    }

    /** Adds [value] to [name], keeping any values it already has. */
    public fun append(name: String, value: String) {
        entries.add(name to value)
    }

    public fun remove(name: String) {
        entries.removeAll { it.isNamed(name) }
    }

    /** Returns the first value of [name], or `null` if it has none. */
    public operator fun get(name: String): String? = entries.firstOrNull { it.isNamed(name) }?.second

    public fun getAll(name: String): List<String> = entries.filter { it.isNamed(name) }.map { it.second }

    public fun has(name: String): Boolean = entries.any { it.isNamed(name) }

    public fun clear() {
        entries.clear()
    }

    /** Returns every name/value pair, in the order they were added. */
    public fun toList(): List<Pair<String, String>> = entries.toList()

    private fun Pair<String, String>.isNamed(name: String) = first.equals(name, ignoreCase = true)
}
