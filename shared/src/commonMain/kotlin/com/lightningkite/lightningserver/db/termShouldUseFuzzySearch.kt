package com.lightningkite.lightningserver.db

fun String.termShouldUseFuzzySearch(): Boolean {
    return all { it.isLetter() || it == '-' } && length > 3
}