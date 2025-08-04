package com.lightningkite.lightningserver.serialization.bson

internal enum class STATE {
    NAME, VALUE
}

internal class StateMap: Iterator<STATE> {
    var toggleState = false
    override fun hasNext(): Boolean = false

    override fun next(): STATE {
        toggleState = !toggleState
        return when(toggleState) {
            true -> STATE.NAME
            false -> STATE.VALUE
        }
    }
}