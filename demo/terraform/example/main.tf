terraform {
  backend "s3" {
    bucket = "ivieleague-deployment-states"
    key    = "demo/example"
    region = "us-west-2"
  }
}
provider "aws" {
  region = "us-west-2"
}
module "domain" {
  source              = "../domain"
  deployment_location = "us-west-2"
  deployment_name     = "example"
  domain_name         = "example.demo.ivieleague.com"
  domain_name_zone    = "ivieleague.com"
  debug               = true
  cors = {
    allowedDomains = ["*"]
    allowedHeaders = ["*"]
  }
}
