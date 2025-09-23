package com.lightningkite.lightningserver.typed.sdk

import kotlinx.io.Sink
import kotlinx.io.writeString

public fun Sink.useAsAppendable(action: Appendable.() -> Unit) {
    this.use { sink ->
        val appendable = object : Appendable {
            override fun append(c: Char): Appendable {
                sink.writeString(c.toString())
                return this
            }
            override fun append(csq: CharSequence?): Appendable {
                csq?.let(sink::writeString)
                return this
            }
            override fun append(csq: CharSequence?, start: Int, end: Int): Appendable {
                csq?.let {
                    sink.writeString(it, start, end)
                }
                return this
            }
        }
        action(appendable)
    }
}