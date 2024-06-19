package com.lightningkite.lightningserver.serialization

import java.io.Reader

data class CsvConfig(
    val fieldSeparator: Char = ',',
    val recordSeparator: Char = '\n',
    val optionalRecordSeparatorPrefix: Char = '\r',
    val quoteCharacter: Char = '"',
    val defaultValue: String = "",
) {
    val quoteCharacterString = "$quoteCharacter"
    val quoteCharacterString2 = "$quoteCharacter$quoteCharacter"
    companion object {
        val default = CsvConfig()
    }
}

fun Reader.iterator(): CharIterator = object: CharIterator() {
    var buffered = this@iterator.read()
    override fun hasNext(): Boolean = buffered != -1
    override fun nextChar(): Char {
        val result = buffered.toChar()
        buffered = this@iterator.read()
        return result
    }

}

fun Appendable.appendCsv(values: Sequence<Map<String, String>>, config: CsvConfig = CsvConfig.default) {
    appendCsvRows(sequence {
        val keys = values.flatMap { it.keys }.distinct().toList()
        yield(keys)
        values.forEach { yield(keys.map { k -> it[k] ?: config.defaultValue }) }
    }, config)
}
fun Appendable.appendCsv(keys: List<String>, values: Sequence<Map<String, String>>, config: CsvConfig = CsvConfig.default) {
    appendCsvRows(sequence {
        yield(keys)
        values.forEach { yield(keys.map { k -> it[k] ?: config.defaultValue }) }
    }, config)
}
fun Appendable.startCsv(keys: List<String>, config: CsvConfig = CsvConfig.default): (Map<String, String>)->Unit {
    appendCsvRow(keys, config)
    return {
        appendCsvRow(keys.map { k -> it[k] ?: config.defaultValue })
    }
}

fun Appendable.appendCsvRows(sequence: Sequence<List<String>>, config: CsvConfig = CsvConfig.default) {
    sequence.forEach {
        appendCsvRow(it, config)
    }
}
fun Appendable.appendCsvRow(row: List<String>, config: CsvConfig = CsvConfig.default) {
    var first = true
    for (value in row) {
        if (first) first = false
        else this.append(config.fieldSeparator)
        appendCsvEscaped(value, config)
    }
    this.append(config.recordSeparator)
}

fun Appendable.appendCsvEscaped(value: String, config: CsvConfig = CsvConfig.default) {
    if (value.contains(config.fieldSeparator) || value.contains(config.recordSeparator) || value.contains(config.quoteCharacter)) {
        this.append(config.quoteCharacter)
        this.append(value.replace(config.quoteCharacterString, config.quoteCharacterString2))
        this.append(config.quoteCharacter)
    } else {
        this.append(value)
    }
}

fun Sequence<List<String>>.asMaps(config: CsvConfig = CsvConfig.default): Sequence<Map<String, String>> {
    return sequence {
        val iter = this@asMaps.iterator()
        if (!iter.hasNext()) return@sequence
        val keys = iter.next()
        while (iter.hasNext()) {
            val values = iter.next()
            yield((0 until keys.size).asSequence().filter { values[it] != config.defaultValue }.associate {
                keys[it] to values[it]
            })
        }
    }
}

fun CharIterator.csvLines(config: CsvConfig = CsvConfig.default): Sequence<List<String>> = sequence {
    val builder = StringBuilder("")
    var inQuotes = false
    var lastWasQuote = false
    var listBuilder = ArrayList<String>()
    var lastWasNewline = true
    var lastWasPrefix = false
    this@csvLines.forEach {
        if (lastWasPrefix && it != config.recordSeparator) builder.append(config.optionalRecordSeparatorPrefix)
        lastWasPrefix = false
        if (lastWasQuote && it == config.quoteCharacter) {
            if (!inQuotes) {
                builder.append(config.quoteCharacter)
            }
            inQuotes = !inQuotes
            return@forEach
        }
        lastWasQuote = it == config.quoteCharacter
        if (inQuotes) {
            if (it == config.quoteCharacter) {
                inQuotes = false
            } else {
                builder.append(it)
            }
        } else {
            if (it == config.quoteCharacter) {
                inQuotes = true
            } else if (it == config.fieldSeparator) {
                lastWasNewline = false
                listBuilder.add(builder.toString())
                builder.clear()
            } else if (it == config.optionalRecordSeparatorPrefix) {
                // ignore
                lastWasPrefix = true
            } else if (it == config.recordSeparator) {
                if (!lastWasNewline) {
                    listBuilder.add(builder.toString())
                    builder.clear()
                    yield(listBuilder)
                    listBuilder = ArrayList()
                }
                lastWasNewline = true
            } else {
                lastWasNewline = false
                builder.append(it)
            }
        }
    }
    if (!lastWasNewline) {
        listBuilder.add(builder.toString())
        builder.clear()
        yield(listBuilder)
        listBuilder = ArrayList()
    }
}
