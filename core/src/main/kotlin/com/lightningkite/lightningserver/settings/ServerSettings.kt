package com.lightningkite.lightningserver.settings

import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.lightningserver.definition.builder.MapRegistry
import com.lightningkite.lightningserver.definition.builder.getOrRegister
import com.lightningkite.lightningserver.definition.builder.include
import com.lightningkite.lightningserver.runtime.ServerRuntime

public class ServerSettings(public val settings: Set<ServerSetting<*, *>>) {
    public var ready: Boolean = false
        private set
    private val serializable: MapRegistry<ServerSetting<*, *>, Any?> = MapRegistry()
    private val goal: MapRegistry<ServerSetting<*, *>, Any?> = MapRegistry()


    public infix fun <SERIALIZABLE> ServerSetting<SERIALIZABLE, *>.set(value: SERIALIZABLE) {
        if (ready) throw IllegalStateException("Settings are marked as ready.")
        serializable.register(this, value as Any?)
    }
    public fun <SERIALIZABLE> ServerSetting<SERIALIZABLE, *>.useDefault() {
        if (ready) throw IllegalStateException("Settings are marked as ready.")
        serializable.register(this, default)
    }

    public infix fun <RESULT> ServerSetting<*, RESULT>.setStatic(value: RESULT) {
        if (ready) throw IllegalStateException("Settings are marked as ready.")
        goal.register(this, value as Any?)
    }

    public fun include(map: Map<ServerSetting<*, *>, Any?>) {
        if (ready) throw IllegalStateException("Settings are marked as ready.")
        serializable.include(map)
    }

    public fun readyUsingDefaults() {
        ready = true
    }
    public fun ready() {
        val missing = settings.minus(serializable.keys + goal.keys)
        if(missing.isNotEmpty()) throw IllegalStateException("Settings ${missing.joinToString { it.name }} are missing.")
        ready = true
    }

    @Suppress("UNCHECKED_CAST")
    context(_: ServerRuntime)
    public fun <SERIALIZABLE, RESULT> get(key: ServerSetting<SERIALIZABLE, RESULT>): RESULT {
        if (!ready) throw IllegalStateException("Settings not ready yet.")
        return goal.getOrRegister(key) {
            key.get(
                serializable.getOrElse(key) { key.default } as SERIALIZABLE
            )
        } as RESULT
    }

    public fun allSerializable(): Map<ServerSetting<*, *>, Any?> = settings.associateWith { serializable[it] ?: it.default }

    context(_: ServerRuntime)
    public fun allGoals(): Map<ServerSetting<*, *>, Any?> = settings.associateWith { get(it) }
}
