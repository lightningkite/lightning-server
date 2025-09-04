package com.lightningkite.lightningserver.typed.sdk

import java.util.*


private val casingSeparatorRegex: Regex = Regex("([-_\\s]+([A-Z]*[a-z0-9]+))|([-_\\s]*[A-Z]+)")

private inline fun String.caseAlter(crossinline update: (after: String) -> String): String =
    casingSeparatorRegex.replace(this) {
        if(it.range.start == 0) it.value
        else update(it.value.filter { !(it == '-' || it == '_' || it.isWhitespace()) })
    }

private fun String.capitalize(): String = replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
private fun String.decapitalize(): String = replaceFirstChar { if (it.isUpperCase()) it.lowercase(Locale.getDefault()) else it.toString() }

internal fun String.titleCase(): String = caseAlter { " " + it.capitalize() }.capitalize()
internal fun String.spaceCase(): String = caseAlter { " $it" }.decapitalize()
internal fun String.kabobCase(): String = caseAlter { "-$it" }.lowercase()
internal fun String.snakeCase(): String = caseAlter { "_$it" }.lowercase()
internal fun String.screamingSnakeCase(): String = caseAlter { "_$it" }.uppercase()
internal fun String.camelCase(): String = caseAlter { it.capitalize() }.decapitalize()
internal fun String.pascalCase(): String = caseAlter { it.capitalize() }.capitalize()
