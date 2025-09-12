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

public fun String.titleCase(): String = caseAlter { " " + it.capitalize() }.capitalize()
public fun String.spaceCase(): String = caseAlter { " $it" }.decapitalize()
public fun String.kabobCase(): String = caseAlter { "-$it" }.lowercase()
public fun String.snakeCase(): String = caseAlter { "_$it" }.lowercase()
public fun String.screamingSnakeCase(): String = caseAlter { "_$it" }.uppercase()
public fun String.camelCase(): String = caseAlter { it.capitalize() }.decapitalize()
public fun String.pascalCase(): String = caseAlter { it.capitalize() }.capitalize()

public fun String.functionCase(): String = this
    .replace(Regex("""[^0-9a-zA-Z]+(?<following>.)?""")) { match ->
        match.groups["following"]?.value?.uppercase() ?: ""
    }
    .decapitalize()