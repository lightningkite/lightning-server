terraform {
  required_providers {
    aws = {
      source = "hashicorp/aws"
      version = "~> 4.30"
    }
    random = {
      source = "hashicorp/random"
      version = "~> 3.1.0"
    }
    archive = {
      source = "hashicorp/archive"
      version = "~> 2.2.0"
    }
    mongodbatlas = {
      source = "mongodb/mongodbatlas"
      version = "~> 1.4"
    }
  }
  required_version = "~> 1.0"
}
terraform {
  backend "s3" {
    bucket = "ivieleague-deployment-states"
    key    = "demo/example"
    region = "us-west-2"
  }
}
provider "aws" {
  region = "us-west-2"
  access_key = local.awsKey
  secret_key = local.awsPrivateKey
}
provider "aws" {
  alias = "acm"
  region = "us-east-1"
  access_key = local.awsKey
  secret_key = local.awsPrivateKey
}
provider "mongodbatlas" {
  public_key = local.mongodbKey
  private_key = local.mongodbPrivateKey
}
module "domain" {
  source              = "../domain"
  deployment_location = "us-west-2"
  deployment_name     = "example"
  domain_name         = "example.demo.ivieleague.com"
  domain_name_zone    = "ivieleague.com"
  debug               = true
  lambda_in_vpc   = false
  database_org_id = "6323a65c43d66b56a2ea5aea"
  cors = {
    allowedDomains = ["*"]
    allowedHeaders = ["*"]
  }
  oauth_google = local.oauth_google
  oauth_apple = local.oauth_apple
  reporting_email = "joseph@lightningkite.com"
  metrics = {
    url = "db://database"
    trackingByEntryPoint = [
      "executionTime"
    ]
    trackingTotalsOnly = []
  }
  emergencyContact = "joseph@lightningkite.com"
#  files_expiry = null
}
