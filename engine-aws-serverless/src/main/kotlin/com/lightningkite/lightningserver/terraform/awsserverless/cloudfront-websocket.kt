package com.lightningkite.lightningserver.terraform.awsserverless

import com.lightningkite.services.terraform.TerraformJsonObject

/**
 * CloudFront Function JavaScript code that transforms URI paths into a `path` query parameter.
 *
 * This function runs at CloudFront edge locations and handles incoming WebSocket requests:
 * - Input: `wss://domain.com/voice/call?token=abc`
 * - Output: `wss://domain.com/?path=/voice/call?token=abc`
 *
 * The function preserves any existing query parameters by encoding them into the path value.
 */
public const val CLOUDFRONT_WS_PATH_TRANSFORM_FUNCTION: String = """
function handler(event) {
    var request = event.request;
    var uri = request.uri;

    // Skip if already at root
    if (uri === '/' || uri === '') {
        return request;
    }

    // Build the path query parameter value
    // Include the original path and any existing query string
    var pathValue = uri;
    if (request.querystring && Object.keys(request.querystring).length > 0) {
        var qs = [];
        for (var key in request.querystring) {
            var param = request.querystring[key];
            if (param.multiValue) {
                param.multiValue.forEach(function(v) {
                    qs.push(encodeURIComponent(key) + '=' + encodeURIComponent(v.value));
                });
            } else {
                qs.push(encodeURIComponent(key) + '=' + encodeURIComponent(param.value));
            }
        }
        pathValue += '?' + qs.join('&');
    }

    // Set the path query parameter and reset URI to root
    request.querystring['path'] = { value: pathValue };
    request.uri = '/';

    return request;
}
"""

/**
 * Emits CloudFront distribution resources for WebSocket path-based routing.
 *
 * This replaces the direct API Gateway custom domain with a CloudFront distribution
 * that transforms URI paths into the `path` query parameter.
 *
 * Called from [TerraformAwsServerlessBuilder] when [TerraformAwsServerlessBuilder.useCloudFrontForWebSocket] is true.
 *
 * @param domainName The base domain (e.g., "example.com")
 * @param zone The Route53 zone ID for DNS records
 * @param stageName The API Gateway stage name (e.g., "myproject-gateway-stage")
 */
internal fun TerraformJsonObject.emitCloudFrontWebSocket(
    domainName: String,
    zone: String,
    stageName: String,
) {
    val safeName = domainName.replace(".", "-")

    // CloudFront Function for path-to-query transformation
    "resource.aws_cloudfront_function.ws_path_transform" {
        "name" - "ws-path-transform-$safeName"
        "runtime" - "cloudfront-js-2.0"
        "comment" - "Transforms WebSocket URI paths to path query parameter"
        "publish" - true
        "code" - CLOUDFRONT_WS_PATH_TRANSFORM_FUNCTION
    }

    // Origin request policy for WebSocket - forwards necessary headers and all query strings
    "resource.aws_cloudfront_origin_request_policy.ws" {
        "name" - "ws-origin-$safeName"
        "comment" - "Origin request policy for WebSocket API Gateway"

        "cookies_config" {
            "cookie_behavior" - "none"
        }
        "headers_config" {
            "header_behavior" - "whitelist"
            "headers" {
                "items" - listOf(
                    "Sec-WebSocket-Key",
                    "Sec-WebSocket-Version",
                    "Sec-WebSocket-Protocol",
                    "Sec-WebSocket-Accept",
                    "Sec-WebSocket-Extensions"
                )
            }
        }
        "query_strings_config" {
            "query_string_behavior" - "all"
        }
    }

    // Use AWS managed "CachingDisabled" policy - caching disabled for WebSocket connections
    // Managed policy ID: 4135ea2d-6df8-44a3-9df3-4b5a84be39ad
    "data.aws_cloudfront_cache_policy.caching_disabled" {
        "name" - "Managed-CachingDisabled"
    }

    // CloudFront distribution - fronts the WebSocket API Gateway
    "resource.aws_cloudfront_distribution.ws" {
        "enabled" - true
        "comment" - "WebSocket distribution for ws.$domainName with path routing"
        "price_class" - "PriceClass_100" // North America and Europe edge locations
        "aliases" - listOf("ws.$domainName")

        "viewer_certificate" {
            "acm_certificate_arn" - expression("aws_acm_certificate.ws.arn")
            "ssl_support_method" - "sni-only"
            "minimum_protocol_version" - "TLSv1.2_2021"
        }

        "origin" {
            // Point to the default API Gateway WebSocket URL (not custom domain)
            "domain_name" - expression(
                "replace(aws_apigatewayv2_api.ws.api_endpoint, \"wss://\", \"\")"
            )
            "origin_id" - "wsApiGateway"
            "origin_path" - "/$stageName"

            "custom_origin_config" {
                "http_port" - 80
                "https_port" - 443
                "origin_protocol_policy" - "https-only"
                "origin_ssl_protocols" - listOf("TLSv1.2")
            }
        }

        "default_cache_behavior" {
            "allowed_methods" - listOf("GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE")
            "cached_methods" - listOf("GET", "HEAD")
            "target_origin_id" - "wsApiGateway"
            "viewer_protocol_policy" - "https-only"

            "cache_policy_id" - expression("data.aws_cloudfront_cache_policy.caching_disabled.id")
            "origin_request_policy_id" - expression("aws_cloudfront_origin_request_policy.ws.id")

            "function_association" {
                "event_type" - "viewer-request"
                "function_arn" - expression("aws_cloudfront_function.ws_path_transform.arn")
            }
        }

        "restrictions" {
            "geo_restriction" {
                "restriction_type" - "none"
            }
        }

        "depends_on" - listOf(
            "aws_apigatewayv2_stage.ws",
            "aws_acm_certificate_validation.ws"
        )
    }

    // Route53 A record pointing ws.{domain} to CloudFront
    "resource.aws_route53_record.wsAccess" {
        "type" - "A"
        "name" - "ws.$domainName"
        "zone_id" - zone

        "alias" {
            "name" - expression("aws_cloudfront_distribution.ws.domain_name")
            "zone_id" - expression("aws_cloudfront_distribution.ws.hosted_zone_id")
            "evaluate_target_health" - false
        }
    }
}
