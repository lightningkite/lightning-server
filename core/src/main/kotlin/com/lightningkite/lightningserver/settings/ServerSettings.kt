package com.lightningkite.lightningserver.settings

import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.lightningserver.definition.builder.MapRegistry
import com.lightningkite.lightningserver.definition.builder.getOrRegister
import com.lightningkite.lightningserver.runtime.ServerRuntime

public class ServerSettings(public val settings: Set<ServerSetting<*, *>>) {
    public val serializable: MapRegistry<ServerSetting<*, *>, Any?> = MapRegistry()
    public val goal: MapRegistry<ServerSetting<*, *>, Any?> = MapRegistry()

    public infix fun <SERIALIZABLE> ServerSetting<SERIALIZABLE, *>.set(value: SERIALIZABLE) {
        serializable.register(this, value as Any?)
    }

    public infix fun <RESULT> ServerSetting<*, RESULT>.setStatic(value: RESULT) {
        goal.register(this, value as Any?)
    }

    @Suppress("UNCHECKED_CAST")
    context(_: ServerRuntime)
    public fun <SERIALIZABLE, RESULT> get(key: ServerSetting<SERIALIZABLE, RESULT>): RESULT {
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
