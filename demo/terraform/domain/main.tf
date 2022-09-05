variable "debug" {
  default = true
}
variable "domain_name_zone" {
}
variable "domain_name" {
}
variable "deployment_name" {
  default = "example"
}
variable "deployment_location" {
  default = "us-west-2"
}
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
variable "cors" {
    default = null
}
variable "logging" {
  default = {}
}
variable "exceptions" {
  default = {}
}

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
      module "Base" {
        source              = "../base"
        deployment_location = var.deployment_location
        deployment_name     = var.deployment_name
        debug               = var.debug
        public_http_url     = "https://${var.domain_name}"
        public_ws_url       = "wss://ws.${var.domain_name}"
          cors  = var.cors
logging  = var.logging
exceptions  = var.exceptions
email_sender  = "noreply@${var.domain_name}"

      }
