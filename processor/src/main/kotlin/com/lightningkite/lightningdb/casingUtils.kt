package com.lightningkite.lightningdb

import java.util.*


val `casing separator regex` = Regex("([-_\\s]+([A-Z]*[a-z0-9]+))|([-_\\s]*[A-Z]+)")
inline fun String.caseAlter(crossinline update: (after: String) -> String): String =
    `casing separator regex`.replace(this) {
        if(it.range.start == 0) it.value
        else update(it.value.filter { !(it == '-' || it == '_' || it.isWhitespace()) })
    }

private fun String.capitalize(): String = replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
private fun String.decapitalize(): String = replaceFirstChar { if (it.isUpperCase()) it.lowercase(Locale.getDefault()) else it.toString() }

fun String.titleCase() = caseAlter { " " + it.capitalize() }.capitalize()
fun String.spaceCase() = caseAlter { " " + it }.decapitalize()
fun String.kabobCase() = caseAlter { "-$it" }.lowercase()
fun String.snakeCase() = caseAlter { "_$it" }.lowercase()
fun String.screamingSnakeCase() = caseAlter { "_$it" }.uppercase()
fun String.camelCase() = caseAlter { it.capitalize() }.decapitalize()
fun String.pascalCase() = caseAlter { it.capitalize() }.replaceFirstChar {
    if (it.isLowerCase()) it.titlecase(
        Locale.getDefault()
    ) else it.toString()
}
