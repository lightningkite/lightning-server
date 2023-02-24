package com.lightningkite.lightningserver



private val camelRegex = "[a-z][A-Z]".toRegex()
private val snakeRegex = "_[a-zA-Z]".toRegex()
private val kabobRegex = "-[a-zA-Z]".toRegex()
internal fun String.humanize(): String = camelRegex.replace(this) {
    "${it.value[0]} ${it.value[1].uppercase()}"
}.let {
    snakeRegex.replace(it) {
        " " + it.value[1].uppercase()
    }
}.replaceFirstChar { it.uppercaseChar() }.trim()
internal fun String.kabobCase(): String = camelRegex.replace(this) {
    "${it.value[0]}-${it.value[1]}"
}.let {
    snakeRegex.replace(it) {
        "-" + it.value[1]
    }
}.lowercase().trim()
internal fun String.camelCase(): String = snakeRegex.replace(this) {
    "${it.value[0]}${it.value[1].uppercase()}"
}.let {
    kabobRegex.replace(it) {
        it.value[1].uppercase()
    }
}.lowercase().trim()