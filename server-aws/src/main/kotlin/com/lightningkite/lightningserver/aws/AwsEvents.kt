package com.lightningkite.lightningserver.aws

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.*
import com.lightningkite.UUID

@Serializable
data class APIGatewayV2HTTPEvent(
    val version: String,
    val requestContext: RequestContext,
    val resource: String,
    val body: String? = null,
    val multiValueHeaders: Map<String, List<String>>,
    val httpMethod: String,
    val isBase64Encoded: Boolean,
    val path: String,
    val multiValueQueryStringParameters: Map<String, List<String>>? = null,
) {
    @Serializable
    data class RequestContext(
        val accountId: String,
        val apiId: String,
        val domainName: String,
        val domainPrefix: String,
        val extendedRequestId: String,
        val httpMethod: String,
        val identity: Identity,
        val path: String,
        val protocol: String,
        val requestId: String,
        val requestTime: String,
        val requestTimeEpoch: Long,
        val resourceId: String,
        val resourcePath: String,
        val stage: String,
    ) {
        @Serializable
        data class Identity(
            val sourceIp: String,
        )
    }
}

@Serializable
data class APIGatewayV2HTTPResponse(
    val statusCode: Int? = null,
    val body: String? = null,
    val isBase64Encoded: Boolean = false,
//    val multiValueHeaders: Map<String, List<String>> = mapOf(),
    val headers: Map<String, String> = mapOf()
)


@Serializable
data class APIGatewayV2WebsocketRequest(
    val multiValueHeaders: Map<String, List<String>>? = null,
    val multiValueQueryStringParameters: Map<String, List<String>>? = null,
    val requestContext: RequestContext,
    val isBase64Encoded: Boolean,
    val body: String? = null,
) {
    @Serializable
    data class RequestContext(
        val routeKey: String,
        val eventType: String,
        val extendedRequestId: String,
        val requestTime: String,
        val messageDirection: String,
        val stage: String,
        val connectedAt: Long,
        val requestTimeEpoch: Long,
        val identity: Identity,
        val requestId: String,
        val domainName: String,
        val connectionId: String,
        val apiId: String,
    ) {
        @Serializable
        data class Identity(
            val userAgent: String? = null,
            val sourceIp: String? = null,
        ) {
        }
    }
}















private fun JsonElement.jankType(key: String): String = when(this) {
    JsonNull -> "Any?"
    is JsonObject -> key.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    is JsonArray -> "List<${this.firstOrNull()?.jankType(key)}>"
    is JsonPrimitive -> when(this.content) {
        "true", "false" -> "Boolean"
        else -> if(this.isString) "String" else "Int"
    }
}

internal fun JsonObject.jankMeADataClass(name: String) {
    println("@Serializable")
    println("data class $name(")
    for(entry in this) {
        println("val ${entry.key}: ${entry.value.jankType(entry.key)},")
    }
    println(") {")
    entries.filter { it.value is JsonObject }.forEach {
        (it.value as JsonObject).jankMeADataClass(it.key.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() })
    }
    println("}")
}

@Serializable
data class EventHubEvent(
    val version: String,
    val id: String,
    @SerialName("detail-type") val detailType: String,
    val source: String,
    val account: String,
    val time: String,
    val region: String,
    val resources: List<String>,
)
