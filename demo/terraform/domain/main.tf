module "Base" {
  source              = "../base"
  # main
  deployment_location = var.deployment_location
  deployment_name = var.deployment_name
  debug = var.debug
  secure_over_cheap = var.secure_over_cheap
  # general
  cors = var.cors
  # database
  database_min_capacity = var.database_min_capacity
  database_max_capacity = var.database_max_capacity
  database_auto_pause = var.database_auto_pause
  # jwt
  jwt_expirationMilliseconds = var.jwt_expirationMilliseconds
  jwt_emailExpirationMilliseconds = var.jwt_emailExpirationMilliseconds
  # logging
  logging = var.logging
  # files
  files_expiry = var.files_expiry
  # exceptions
  exceptions = var.exceptions
  # email
  email_sender = "noreply@${var.domain_name}"
  # HTTP
  public_http_url = "https://${var.domain_name}"
  # WebSockets
  public_ws_url = "wss://ws.${var.domain_name}"
}
##########
# main
##########
provider "aws" {
  region = var.deployment_location
}

data "aws_route53_zone" "main" {
  name = var.domain_name_zone
}
provider "aws" {
  alias = "acm"
  region = "us-east-1"
}

##########
# email
##########
resource "aws_ses_domain_identity" "email" {
  domain = var.domain_name
}
resource "aws_route53_record" "email" {
  zone_id = data.aws_route53_zone.main.zone_id
  name    = "_amazonses.${var.domain_name}"
  type    = "TXT"
  ttl     = "600"
  records = [aws_ses_domain_identity.email.verification_token]
}

##########
# HTTP
##########
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
  api_id      = module.Base.http.api_id
  domain_name = aws_apigatewayv2_domain_name.http.domain_name
  stage       = module.Base.http.id
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

##########
# WebSockets
##########
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
  api_id      = module.Base.ws.api_id
  domain_name = aws_apigatewayv2_domain_name.ws.domain_name
  stage       = module.Base.ws.id
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

