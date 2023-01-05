##########
# Inputs
##########


##########
# Outputs
##########

output "ws_url" {
    value = aws_apigatewayv2_stage.ws.invoke_url
}
output "ws" {
    value = {
    id = aws_apigatewayv2_stage.ws.id
    api_id = aws_apigatewayv2_stage.ws.api_id
    invoke_url = aws_apigatewayv2_stage.ws.invoke_url
    arn = aws_apigatewayv2_stage.ws.arn
    name = aws_apigatewayv2_stage.ws.name
}
}

##########
# Resources
##########

resource "aws_apigatewayv2_api" "ws" {
  name = "demo-example-gateway"
  protocol_type = "WEBSOCKET"
  route_selection_expression = "constant"
}

resource "aws_apigatewayv2_stage" "ws" {
  api_id = aws_apigatewayv2_api.ws.id

  name = "demo-example-gateway-stage"
  auto_deploy = true

  access_log_settings {
    destination_arn = aws_cloudwatch_log_group.ws_api.arn

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

resource "aws_apigatewayv2_integration" "ws" {
  api_id = aws_apigatewayv2_api.ws.id

  integration_uri    = aws_lambda_function.main.invoke_arn
  integration_type   = "AWS_PROXY"
  integration_method = "POST"
}

resource "aws_cloudwatch_log_group" "ws_api" {
  name = "demo-example-ws-gateway-log"

  retention_in_days = 30
}

resource "aws_apigatewayv2_route" "ws_connect" {
    api_id = aws_apigatewayv2_api.ws.id

    route_key = "$connect"
    target    = "integrations/${aws_apigatewayv2_integration.ws.id}"
}
resource "aws_apigatewayv2_route" "ws_default" {
    api_id = aws_apigatewayv2_api.ws.id

    route_key = "$default"
    target    = "integrations/${aws_apigatewayv2_integration.ws.id}"
}
resource "aws_apigatewayv2_route" "ws_disconnect" {
    api_id = aws_apigatewayv2_api.ws.id

    route_key = "$disconnect"
    target    = "integrations/${aws_apigatewayv2_integration.ws.id}"
}

resource "aws_lambda_permission" "api_gateway_ws" {
  statement_id  = "AllowExecutionFromAPIGatewayWS"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.main.function_name
  principal     = "apigateway.amazonaws.com"

  source_arn = "${aws_apigatewayv2_api.ws.execution_arn}/*/*"
}

resource "aws_iam_policy" "api_gateway_ws" {
  name        = "demo-example-api_gateway_ws"
  path = "/demo/example/api_gateway_ws/"
  description = "Access to the demo-example_api_gateway_ws management"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = [
          "execute-api:ManageConnections"
        ]
        Effect   = "Allow"
        Resource = "*"
      },
    ]
  })
}
resource "aws_iam_role_policy_attachment" "api_gateway_ws" {
  role       = aws_iam_role.main_exec.name
  policy_arn = aws_iam_policy.api_gateway_ws.arn
}
resource "aws_acm_certificate" "ws" {
  domain_name   = "ws.${var.domain_name}"
  validation_method = "DNS"
}
resource "aws_route53_record" "ws" {
  zone_id = data.aws_route53_zone.main.zone_id
  name = tolist(aws_acm_certificate.ws.domain_validation_options)[0].resource_record_name
  type = tolist(aws_acm_certificate.ws.domain_validation_options)[0].resource_record_type
  records = [tolist(aws_acm_certificate.ws.domain_validation_options)[0].resource_record_value]
  ttl = "300"
}
resource "aws_acm_certificate_validation" "ws" {
  certificate_arn = aws_acm_certificate.ws.arn
  validation_record_fqdns = [aws_route53_record.ws.fqdn]
}
resource aws_apigatewayv2_domain_name ws {
  domain_name = "ws.${var.domain_name}"
  domain_name_configuration {
    certificate_arn = aws_acm_certificate.ws.arn
    endpoint_type   = "REGIONAL"
    security_policy = "TLS_1_2"
  }
  depends_on = [aws_acm_certificate_validation.ws]
}
resource aws_apigatewayv2_api_mapping ws {
  stage       = aws_apigatewayv2_stage.ws.id
  api_id      = aws_apigatewayv2_stage.ws.api_id
  domain_name = aws_apigatewayv2_domain_name.ws.domain_name
}
resource aws_route53_record wsAccess {
  type    = "A"
  name    = aws_apigatewayv2_domain_name.ws.domain_name
  zone_id = data.aws_route53_zone.main.id
    alias {
      evaluate_target_health = false
      name                   = aws_apigatewayv2_domain_name.ws.domain_name_configuration[0].target_domain_name
      zone_id                = aws_apigatewayv2_domain_name.ws.domain_name_configuration[0].hosted_zone_id
    }
}

