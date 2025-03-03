# Generated via Lightning Server.  This file will be overwritten or deleted when regenerating.
##########
# Inputs
##########


##########
# Outputs
##########

output "http_url" {
    value = aws_apigatewayv2_stage.http.invoke_url
}
output "http" {
    value = {
    id = aws_apigatewayv2_stage.http.id
    api_id = aws_apigatewayv2_stage.http.api_id
    invoke_url = aws_apigatewayv2_stage.http.invoke_url
    arn = aws_apigatewayv2_stage.http.arn
    name = aws_apigatewayv2_stage.http.name
}
}

##########
# Resources
##########

resource "aws_apigatewayv2_api" "http" {
  name = "demo-http"
  protocol_type = "HTTP"
}

resource "aws_apigatewayv2_stage" "http" {
  api_id = aws_apigatewayv2_api.http.id

  name = "demo-gateway-stage"
  auto_deploy = true

  access_log_settings {
    destination_arn = aws_cloudwatch_log_group.http_api.arn

    format = jsonencode({
      requestId               = "$context.requestId"
      sourceIp                = "$context.identity.sourceIp"
      requestTime             = "$context.requestTime"
      protocol                = "$context.protocol"
      httpMethod              = "$context.httpMethod"
      resourcePath            = "$context.resourcePath"
      routeKey                = "$context.routeKey"
      status                  = "$context.status"
      responseLength          = "$context.responseLength"
      integrationErrorMessage = "$context.integrationErrorMessage"
      }
    )
  }
}

resource "aws_apigatewayv2_integration" "http" {
  api_id = aws_apigatewayv2_api.http.id

  integration_uri    = aws_lambda_alias.main.invoke_arn
  integration_type   = "AWS_PROXY"
  integration_method = "POST"
}

resource "aws_cloudwatch_log_group" "http_api" {
  name = "demo-http-gateway-log"

  retention_in_days = 30
}

resource "aws_apigatewayv2_route" "http" {
    api_id = aws_apigatewayv2_api.http.id
    route_key = "$default"
    target    = "integrations/${aws_apigatewayv2_integration.http.id}"
}

resource "aws_lambda_permission" "api_gateway_http" {
  action        = "lambda:InvokeFunction"
  function_name = "${aws_lambda_alias.main.function_name}:${aws_lambda_alias.main.name}"
  principal     = "apigateway.amazonaws.com"

  source_arn = "${aws_apigatewayv2_api.http.execution_arn}/*/*"
  lifecycle {
    create_before_destroy = false
  }
}
resource "aws_acm_certificate" "http" {
  domain_name   = var.domain_name
  validation_method = "DNS"
}
resource "aws_route53_record" "http" {
  zone_id = data.aws_route53_zone.main.zone_id
  name = tolist(aws_acm_certificate.http.domain_validation_options)[0].resource_record_name
  type = tolist(aws_acm_certificate.http.domain_validation_options)[0].resource_record_type
  records = [tolist(aws_acm_certificate.http.domain_validation_options)[0].resource_record_value]
  ttl = "300"
}
resource "aws_acm_certificate_validation" "http" {
  certificate_arn = aws_acm_certificate.http.arn
  validation_record_fqdns = [aws_route53_record.http.fqdn]
}
resource aws_apigatewayv2_domain_name http {
  domain_name = var.domain_name
  domain_name_configuration {
    certificate_arn = aws_acm_certificate.http.arn
    endpoint_type   = "REGIONAL"
    security_policy = "TLS_1_2"
  }
  depends_on = [aws_acm_certificate_validation.http]
}
resource aws_apigatewayv2_api_mapping http {
  stage       = aws_apigatewayv2_stage.http.id
  api_id      = aws_apigatewayv2_stage.http.api_id
  domain_name = aws_apigatewayv2_domain_name.http.domain_name
}
resource aws_route53_record httpAccess {
  type    = "A"
  name    = aws_apigatewayv2_domain_name.http.domain_name
  zone_id = data.aws_route53_zone.main.id
    alias {
      evaluate_target_health = false
      name                   = aws_apigatewayv2_domain_name.http.domain_name_configuration[0].target_domain_name
      zone_id                = aws_apigatewayv2_domain_name.http.domain_name_configuration[0].hosted_zone_id
    }
}

