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

    fun parseParameterString(params: String): Map<String, List<String>> = params
        .takeIf { it.isNotBlank() }
        ?.split("&")
        ?.filter { it.isNotBlank() }
        ?.map {
            val split = it.split('=')
            split[0] to split[1]
        }
        ?.groupBy { it.first }
        ?.mapValues { it.value.map { it.second } }
        ?: emptyMap()
}