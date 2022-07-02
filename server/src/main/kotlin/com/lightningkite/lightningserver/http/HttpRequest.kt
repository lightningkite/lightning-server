package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.settings.GeneralServerSettings

data class HttpRequest(
    val route: HttpRoute,
    val parts: Map<String, String> = mapOf(),
    val wildcard: String? = null,
    val queryParameters: List<Pair<String, String>> = listOf(),
    val headers: HttpHeaders = HttpHeaders.EMPTY,
    val body: HttpContent? = null,
    val domain: String = GeneralServerSettings.instance.publicUrl.substringAfter("://").substringBefore("/"),
    val protocol: String = GeneralServerSettings.instance.publicUrl.substringBefore("://"),
    val sourceIp: String = "0.0.0.0"
) {
    fun queryParameter(key: String): String? = queryParameters.find { it.first == key }?.second
}
