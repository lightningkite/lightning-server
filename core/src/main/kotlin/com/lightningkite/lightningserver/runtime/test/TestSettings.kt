package com.lightningkite.lightningserver.runtime.test

import com.lightningkite.lightningserver.definition.ServerSetting
import kotlin.collections.set

public class TestSettings() {
    public val serializable: MutableMap<ServerSetting<*, *>, Any?> = HashMap()
    public val goal: MutableMap<ServerSetting<*, *>, Any?> = HashMap()
    public infix fun <SERIALIZABLE> ServerSetting<SERIALIZABLE, *>.set(value: SERIALIZABLE) {
        serializable[this] = value as Any?
    }
    public infix fun <RESULT> ServerSetting<*, RESULT>.setStatic(value: RESULT) {
        goal[this] = value as Any?
    }
}

context(builder: TestSettings)
public infix fun <SERIALIZABLE> ServerSetting<SERIALIZABLE, *>.set(value: SERIALIZABLE) {
    with(builder) { this@set set value }
}

context(builder: TestSettings)
public infix fun <RESULT> ServerSetting<*, RESULT>.setStatic(value: RESULT) {
    with(builder) { this@setStatic setStatic value }
}