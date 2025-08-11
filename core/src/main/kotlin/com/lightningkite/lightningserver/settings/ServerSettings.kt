package com.lightningkite.lightningserver.settings

import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.lightningserver.runtime.ServerRuntime

public class ServerSettings(public val keys: Set<ServerSetting<*, *>>) {
    public val serializable: MutableMap<ServerSetting<*, *>, Any?> = HashMap()
    public val goal: MutableMap<ServerSetting<*, *>, Any?> = HashMap()
    public infix fun <SERIALIZABLE> ServerSetting<SERIALIZABLE, *>.set(value: SERIALIZABLE) {
        serializable[this] = value as Any?
    }

    public infix fun <RESULT> ServerSetting<*, RESULT>.setStatic(value: RESULT) {
        goal[this] = value as Any?
    }

    @Suppress("UNCHECKED_CAST")
    public fun <SERIALIZABLE, GOAL> get(key: ServerSetting<SERIALIZABLE, GOAL>, runtime: ServerRuntime): GOAL {
        return goal.getOrPut(key) {
            val value = if(serializable.containsKey(key)) {
                serializable.getValue(key) as SERIALIZABLE
            } else key.default
            val result: GOAL = context(runtime) { key.get(value) }
            result
        } as GOAL
    }

    public fun allSerializable(): Map<ServerSetting<*, *>, Any?> = keys.associateWith { serializable[it] ?: it.default }
    public fun allGoals(runtime: ServerRuntime): Map<ServerSetting<*, *>, Any?> = keys.associateWith { get(it, runtime) }
}
