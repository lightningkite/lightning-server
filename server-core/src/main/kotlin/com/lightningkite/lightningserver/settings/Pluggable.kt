package com.lightningkite.lightningserver.settings

abstract class Pluggable<S, T> {
    private val handlers = HashMap<String, (S) -> T>()
    val options: Set<String> get() = handlers.keys
    fun register(key: String, handler: (S) -> T) {
        handlers[key] = handler
    }

    fun parse(key: String, setting: S): T {
        val h = handlers[key]
            ?: throw IllegalArgumentException("No handler $key for ${this::class.qualifiedName} - available handlers are ${options.joinToString()}")
        return h(setting)
    }

}