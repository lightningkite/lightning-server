package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.services.data.KFile
import kotlinx.io.Sink
import kotlinx.io.writeString
import kotlin.use

public fun Appendable.appendIdt(depth: Int): Appendable {
    repeat(depth) { append('\t') }
    return this
}

public fun Appendable.appendIdtLine(depth: Int, csq: CharSequence): Appendable =
    appendIdt(depth).appendLine(csq)

public fun Appendable.appendIdtLine(depth: Int, c: Char): Appendable =
    appendIdt(depth).appendLine(c)

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

public fun KFile.overwrite(action: Appendable.() -> Unit) {
    parent?.createDirectories()
    sink().useAsAppendable(action)
}