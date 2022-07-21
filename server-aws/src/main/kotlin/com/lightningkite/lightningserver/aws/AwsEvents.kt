package com.lightningkite.lightningserver.aws

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

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
    val multiValueHeaders: Map<String, List<String>>,
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
            val userAgent: String,
            val sourceIp: String,
        ) {
        }
    }
}















private fun JsonElement.jankType(key: String): String = when(this) {
    JsonNull -> "Any?"
    is JsonObject -> key.capitalize()
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
        (it.value as JsonObject).jankMeADataClass((it.key as String).capitalize())
    }
    println("}")
}

//{version=1.0, resource=$default, path=/demo-test-gateway-stage, httpMethod=POST, headers={Content-Length=19, Content-Type=application/json, Host=kyob2thob4.execute-api.us-west-2.amazonaws.com, User-Agent=insomnia/2022.4.2, X-Amzn-Trace-Id=Root=1-62d9ad26-449f338c7cbc14df1091fa79, X-Forwarded-For=75.148.99.49, X-Forwarded-Port=443, X-Forwarded-Proto=https, accept=*/*}, multiValueHeaders={Content-Length=[19], Content-Type=[application/json], Host=[kyob2thob4.execute-api.us-west-2.amazonaws.com], User-Agent=[insomnia/2022.4.2], X-Amzn-Trace-Id=[Root=1-62d9ad26-449f338c7cbc14df1091fa79], X-Forwarded-For=[75.148.99.49], X-Forwarded-Port=[443], X-Forwarded-Proto=[https], accept=[*/*]}, queryStringParameters=null, multiValueQueryStringParameters=null,
// requestContext={accountId=907811386443, apiId=kyob2thob4, domainName=kyob2thob4.execute-api.us-west-2.amazonaws.com, domainPrefix=kyob2thob4, extendedRequestId=Vof-AimwPHcEMbg=, httpMethod=POST, identity={sourceIp=75.148.99.49}, path=/demo-test-gateway-stage, protocol=HTTP/1.1, requestId=Vof-AimwPHcEMbg=, requestTime=21/Jul/2022:19:46:46 +0000, requestTimeEpoch=1658432806236, resourceId=$default, resourcePath=$default, stage=demo-test-gateway-stage}, pathParameters=null, stageVariables=null, body={"content": "true"}, isBase64Encoded=false}