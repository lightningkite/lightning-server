package com.lightningkite.lightningserver.utils


fun parseParameterString(params: String): Map<String, List<String>> = params
    .takeIf { it.isNotBlank() }
    ?.split("&")
    ?.filter { it.isNotBlank() }
    ?.map {
        it.substringBefore('=') to it.substringAfter('=', "")
    }
    ?.groupBy { it.first }
    ?.mapValues { it.value.map { it.second } }
    ?: emptyMap()