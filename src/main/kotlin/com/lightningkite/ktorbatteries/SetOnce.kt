package com.lightningkite.ktorbatteries

import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

public class SetOnce<T>(
    private val makeDefault: () -> T = { throw IllegalAccessError("This property has not been set yet.") }
) : ReadWriteProperty<Any?, T> {
    private var value: T? = null
    public var set = false
        private set

    override fun getValue(thisRef: Any?, property: KProperty<*>): T {
        if (!set) {
            value = makeDefault()
            set = true
        }
        @Suppress("UNCHECKED_CAST")
        return this.value as T
    }

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        if (set) throw IllegalAccessError("This property (${property.name}) can only be set once.")
        set = true
        this.value = value
    }
}

abstract class SettingSingleton<T> {
    private val instanceSetOnce: SetOnce<T> = SetOnce()
    val isConfigured: Boolean get() = instanceSetOnce.set
    var instance: T by instanceSetOnce
        internal set
}