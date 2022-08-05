terraform {
  backend "s3" {
    bucket = "ivieleague-deployment-states"
    key    = "demo/example"
    region = "us-west-2"
  }
}

variable "deployment_name" {
  default = "example"
}
module "Base" {
  source              = "../base"
  deployment_location = "us-west-2"
  deployment_name     = var.deployment_name
  debug               = true
  email_sender        = "joseph@lightningkite.com"
}
output "http_url" {
  value = module.Base.http_url
}
output "ws_url" {
  value = module.Base.ws_url
}

#locals {
#  basis_domain    = "ivieleague.com"
#  subdomain       = "demo"
#  subdomainPrefix = "${var.deployment_name}.${local.subdomain}"
#  subdomain       = "${var.deployment_name}.${local.subdomain}.${local.basis_domain}"
#}
#
#data "aws_route53_zone" "main" {
#  name = local.basis_domain
#}
#
#resource "aws_route53_record" "cert_api_validations" {
#  allow_overwrite = true
#  count           = length(aws_acm_certificate.cert_api.domain_validation_options)
#  zone_id         = aws_route53_zone.api.zone_id
#  name            = element(aws_acm_certificate.cert_api.domain_validation_options.*.resource_record_name, count.index)
#  type            = element(aws_acm_certificate.cert_api.domain_validation_options.*.resource_record_type, count.index)
#  records         = [
#    element(aws_acm_certificate.cert_api.domain_validation_options.*.resource_record_value, count.index)
#  ]
#  ttl             = 60
#}
#
#resource "aws_route53_record" "http" {
#  zone_id = aws_route53_zone.main.zone_id
#  name    = local.subdomain
#  type    = "A"
#
#  alias {
#    name                   = aws_apigatewayv2_domain_name.api.domain_name_configuration[0].target_domain_name
#    zone_id                = aws_apigatewayv2_domain_name.api.domain_name_configuration[0].hosted_zone_id
#    evaluate_target_health = false
#  }
#}