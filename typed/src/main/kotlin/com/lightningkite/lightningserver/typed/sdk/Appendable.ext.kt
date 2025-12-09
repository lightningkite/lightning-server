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

public fun Sink.useAsIndentable(
    indent: CharSequence = "\t",
    action: Indentable.() -> Unit
): Unit = useAsAppendable {
    action(this.indentable(indent))
}

public fun KFile.overwrite(
    indent: CharSequence = "\t",
    action: Indentable.() -> Unit
) {
    parent?.createDirectories()
    sink().useAsIndentable(indent, action)
}

public class Indentable(
    private val out: Appendable,
    public val indentCsq: CharSequence = "\t",
    public var indentCount: Int = 0,
) : Appendable {
    private var shouldIndent = true

    private fun appendIndent() {
         if (shouldIndent && indentCount > 0) repeat(indentCount) { out.append(indentCsq) }
    }

    override fun append(c: Char): Indentable {
        appendIndent()
        out.append(c)
        shouldIndent = (c == '\n')
        return this
    }

    override fun append(csq: CharSequence?): Indentable {
        appendIndent()
        out.append(csq)
        if (csq != null) shouldIndent = csq.endsWith('\n')
        return this
    }

    override fun append(csq: CharSequence?, start: Int, end: Int): Indentable {
        appendIndent()
        out.append(csq, start, end)
        if (csq != null && end > start) shouldIndent = csq.getOrNull(end - 1) == '\n'
        return this
    }

    public inline fun <T> indented(action: Indentable.() -> T): T {
        indentCount++
        val r = action(this)
        indentCount--
        return r
    }

    public fun appendIndented(c: Char): Indentable = indented { append(c) }
    public fun appendIndented(csq: CharSequence?): Indentable = indented { append(csq) }
    public fun appendIndented(csq: CharSequence?, start: Int, end: Int): Indentable = indented { append(csq, start, end) }

    private fun appendLine(): Indentable {
        out.append('\n')
        shouldIndent = true
        return this
    }

    public fun appendIndentedLine(c: Char): Indentable = appendIndented(c).appendLine()
    public fun appendIndentedLine(csq: CharSequence?): Indentable = appendIndented(csq).appendLine()
    public fun appendIndentedLine(csq: CharSequence?, start: Int, end: Int): Indentable = appendIndented(csq, start, end).appendLine()

    public fun copy(indent: CharSequence = this.indentCsq): Indentable =
        Indentable(out, indent, indentCount).also { it.shouldIndent = shouldIndent }
}

public fun Appendable.indentable(indent: CharSequence = "\t"): Indentable =
    if (this is Indentable) {
        if (this.indentCsq != indent) copy(indent = indent)
        else this
    }
    else Indentable(this, indent)