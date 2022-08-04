module "Base" {
  source      = "../base"
  deployment_location = "us-west-2"
  deployment_name = "example"
  debug = true
  email_sender = "joseph@lightningkite.com"
}
terraform {
  backend "s3" {
    bucket = "ivieleague-deployment-states"
    key    = "demo/example"
    region = "us-west-2"
  }
}
output "http_url" {
  value = module.Base.http_url
}
output "ws_url" {
  value = module.Base.ws_url
}