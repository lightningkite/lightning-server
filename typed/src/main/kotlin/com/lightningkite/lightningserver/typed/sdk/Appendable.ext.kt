package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.services.data.ExperimentalLightningServer
import com.lightningkite.services.kfile.KFile
import kotlinx.io.Sink
import kotlinx.io.writeString
import java.lang.AutoCloseable

public fun Appendable.appendIdt(depth: Int): Appendable {
    repeat(depth) { append('\t') }
    return this
}

public fun Appendable.appendIdtLine(depth: Int, csq: CharSequence): Appendable =
    appendIdt(depth).appendLine(csq)

public fun Appendable.appendIdtLine(depth: Int, c: Char): Appendable =
    appendIdt(depth).appendLine(c)

public interface AppendableResource : Appendable, AutoCloseable

public fun Sink.asAppendable(): AppendableResource =
    object : AppendableResource {
        val sink get() = this@asAppendable

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

        override fun close() {
            sink.close()
        }
    }

public fun KFile.overwrite(action: Appendable.() -> Unit) {
    parent?.createDirectories()
    sink().asAppendable().use(action)
}

@OptIn(ExperimentalLightningServer::class)
public fun Archive.appendableEntry(name: String, write: Appendable.() -> Unit) {
    entry(name) {
        write(it.asAppendable())
    }
}