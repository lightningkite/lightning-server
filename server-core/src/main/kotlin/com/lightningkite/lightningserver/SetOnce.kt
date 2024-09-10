package com.lightningkite.lightningserver

import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * A property delegate that will allow a lazy set, but will prevent the value from being set more than once.
 * If a second set is attempted it will throw an exception.
 * By default, if you attempt to access the value before it is set ith will throw an exception. You can override this behavior and provide a default value to be set using [makeDefault].
 * If used as a type and not a delegate it also has a way of checking if the value has been set.
 *
 * @param makeDefault A lambda to return a default [T] if value is accessed before set. The default lambda will throw an exception.
 */
open class SetOnce<T>(
    private val makeDefault: () -> T = { throw IllegalAccessError("This property has not been set yet.") }
) : ReadWriteProperty<Any?, T> {
    companion object {
        private var allowOverwrite = false
        fun allowOverwrite(block: () -> Unit) {
            allowOverwrite = true
            block()
            allowOverwrite = false
        }
    }

    private var value: T? = null
    var set = false
        private set
    private var setStackTrace: Exception? = null

    override fun getValue(thisRef: Any?, property: KProperty<*>): T {
        if (!set) {
            value = makeDefault()
            setStackTrace = Exception("$this was set via value request here")
            set = true
        }
        @Suppress("UNCHECKED_CAST")
        return this.value as T
    }

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        if (set && !allowOverwrite) throw IllegalStateException("This property (${property.name}) can only be set once.", setStackTrace)
        setStackTrace = Exception("$this was set via setting here")
        set = true
        this.value = value
    }
}
