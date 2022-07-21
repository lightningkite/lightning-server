package com.lightningkite.lightningserver.aws

import com.lightningkite.lightningserver.files.TestSettings
import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test

class APIGatewayV2HTTPEventTest {
    @Test
    fun parse() {
        TestSettings
        Serialization.json.decodeFromString<APIGatewayV2HTTPEvent>("""
            {
                "version": "1.0",
                "resource": "${'$'}default",
                "path": "/demo-test-gateway-stage",
                "httpMethod": "POST",
                "headers": {
                    "Content-Length": "19",
                    "Content-Type": "application/json",
                    "Host": "kyob2thob4.execute-api.us-west-2.amazonaws.com",
                    "User-Agent": "insomnia/2022.4.2",
                    "X-Amzn-Trace-Id": "Root=1-62d9bbc5-043e2229314717fb6ce1cfa8",
                    "X-Forwarded-For": "75.148.99.49",
                    "X-Forwarded-Port": "443",
                    "X-Forwarded-Proto": "https",
                    "accept": "*/*"
                },
                "multiValueHeaders": {
                    "Content-Length": [
                        "19"
                    ],
                    "Content-Type": [
                        "application/json"
                    ],
                    "Host": [
                        "kyob2thob4.execute-api.us-west-2.amazonaws.com"
                    ],
                    "User-Agent": [
                        "insomnia/2022.4.2"
                    ],
                    "X-Amzn-Trace-Id": [
                        "Root=1-62d9bbc5-043e2229314717fb6ce1cfa8"
                    ],
                    "X-Forwarded-For": [
                        "75.148.99.49"
                    ],
                    "X-Forwarded-Port": [
                        "443"
                    ],
                    "X-Forwarded-Proto": [
                        "https"
                    ],
                    "accept": [
                        "*/*"
                    ]
                },
                "queryStringParameters": null,
                "multiValueQueryStringParameters": null,
                "requestContext": {
                    "accountId": "907811386443",
                    "apiId": "kyob2thob4",
                    "domainName": "kyob2thob4.execute-api.us-west-2.amazonaws.com",
                    "domainPrefix": "kyob2thob4",
                    "extendedRequestId": "VopG7iWZvHcEMdQ=",
                    "httpMethod": "POST",
                    "identity": {
                        "accessKey": null,
                        "accountId": null,
                        "caller": null,
                        "cognitoAmr": null,
                        "cognitoAuthenticationProvider": null,
                        "cognitoAuthenticationType": null,
                        "cognitoIdentityId": null,
                        "cognitoIdentityPoolId": null,
                        "principalOrgId": null,
                        "sourceIp": "75.148.99.49",
                        "user": null,
                        "userAgent": "insomnia/2022.4.2",
                        "userArn": null
                    },
                    "path": "/demo-test-gateway-stage",
                    "protocol": "HTTP/1.1",
                    "requestId": "VopG7iWZvHcEMdQ=",
                    "requestTime": "21/Jul/2022:20:49:09 +0000",
                    "requestTimeEpoch": 1658436549791,
                    "resourceId": "${'$'}default",
                    "resourcePath": "${'$'}default",
                    "stage": "demo-test-gateway-stage"
                },
                "pathParameters": null,
                "stageVariables": null,
                "body": "{\"content\": \"true\"}",
                "isBase64Encoded": false
            }
        """.trimIndent())
    }
}