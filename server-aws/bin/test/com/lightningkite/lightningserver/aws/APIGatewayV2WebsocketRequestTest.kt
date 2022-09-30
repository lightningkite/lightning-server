package com.lightningkite.lightningserver.aws

import com.lightningkite.lightningserver.files.TestSettings
import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test

class APIGatewayV2WebsocketRequestTest {
    @Test
    fun parse() {
        TestSettings
        Serialization.json.decodeFromString<APIGatewayV2WebsocketRequest>(
            """{
    "headers": {
        "Host": "e5o5wmco47.execute-api.us-west-2.amazonaws.com",
        "Sec-WebSocket-Key": "WXhGXQZXDxMoNbCgJEgC7A==",
        "Sec-WebSocket-Version": "13",
        "X-Amzn-Trace-Id": "Root=1-62d9c295-34f43f2d331e1ee90d797dc0",
        "X-Forwarded-For": "75.148.99.49",
        "X-Forwarded-Port": "443",
        "X-Forwarded-Proto": "https"
    },
    "multiValueHeaders": {
        "Host": [
            "e5o5wmco47.execute-api.us-west-2.amazonaws.com"
        ],
        "Sec-WebSocket-Key": [
            "WXhGXQZXDxMoNbCgJEgC7A=="
        ],
        "Sec-WebSocket-Version": [
            "13"
        ],
        "X-Amzn-Trace-Id": [
            "Root=1-62d9c295-34f43f2d331e1ee90d797dc0"
        ],
        "X-Forwarded-For": [
            "75.148.99.49"
        ],
        "X-Forwarded-Port": [
            "443"
        ],
        "X-Forwarded-Proto": [
            "https"
        ]
    },
    "queryStringParameters": {
        "param": "asdf"
    },
    "multiValueQueryStringParameters": {
        "param": [
            "asdf"
        ]
    },
    "requestContext": {
        "routeKey": "${'$'}connect",
        "eventType": "CONNECT",
        "extendedRequestId": "VotXXGkWvHcFbSA=",
        "requestTime": "21/Jul/2022:21:18:13 +0000",
        "messageDirection": "IN",
        "stage": "demo-test-gateway-stage",
        "connectedAt": 1658438293383,
        "requestTimeEpoch": 1658438293383,
        "identity": {
            "sourceIp": "75.148.99.49"
        },
        "requestId": "VotXXGkWvHcFbSA=",
        "domainName": "e5o5wmco47.execute-api.us-west-2.amazonaws.com",
        "connectionId": "VotXXcBEPHcCEvQ=",
        "apiId": "e5o5wmco47"
    },
    "isBase64Encoded": false
}"""
        )
    }
}