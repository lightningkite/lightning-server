package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.settings.generalSettings

data class HttpRequest(
    /** The endpoint that is being responded to. **/
    val endpoint: HttpEndpoint,
    /** The values of wildcard path segments. **/
    val parts: Map<String, String> = mapOf(),
    /** Any value filling {...}. **/
    val wildcard: String? = null,
    /** Access to the query parameters (?param=value) **/
    val queryParameters: List<Pair<String, String>> = listOf(),
    /** Access to any headers sent with the request **/
    val headers: HttpHeaders = HttpHeaders.EMPTY,
    /** Access to the content of the request **/
    val body: HttpContent? = null,
    /** The domain used in making the request **/
    val domain: String = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
    /** The protocol used in making the request - HTTP or HTTPS **/
    val protocol: String = generalSettings().publicUrl.substringBefore("://"),
    /** The originating public IP of the request, as can best be determined **/
    val sourceIp: String = "0.0.0.0"
) {
    fun queryParameter(key: String): String? = queryParameters.find { it.first == key }?.second
}
