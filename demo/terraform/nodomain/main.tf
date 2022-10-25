terraform {
  required_providers {
    aws = {
      source = "hashicorp/aws"
      version = "~> 4.30"
    }
    local = {
      source = "hashicorp/local"
      version = "~> 2.2"
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

module "Base" {
  source              = "../base"
  # main
  deployment_location = var.deployment_location
  deployment_name = var.deployment_name
  debug = var.debug
  lambda_in_vpc = var.lambda_in_vpc
  ip_prefix = var.ip_prefix
  # general
  cors = var.cors
  display_name = var.display_name
  # database
  database_org_id = var.database_org_id
  # jwt
  jwt_expirationMilliseconds = var.jwt_expirationMilliseconds
  jwt_emailExpirationMilliseconds = var.jwt_emailExpirationMilliseconds
  # oauth_github
  oauth_github = var.oauth_github
  # logging
  logging = var.logging
  # files
  files_expiry = var.files_expiry
  # metrics
  metrics = var.metrics
  # exceptions
  exceptions = var.exceptions
  # email
  email_sender = var.email_sender
  # oauth_google
  oauth_google = var.oauth_google
  # oauth_apple
  oauth_apple = var.oauth_apple
  # Alarms
  emergencyInvocationsPerMinuteThreshold = var.emergencyInvocationsPerMinuteThreshold
  emergencyComputePerMinuteThreshold = var.emergencyComputePerMinuteThreshold
  panicInvocationsPerMinuteThreshold = var.panicInvocationsPerMinuteThreshold
  panicComputePerMinuteThreshold = var.panicComputePerMinuteThreshold
  emergencyContact = var.emergencyContact
}
##########
# main
##########


##########
# email
##########
resource "aws_ses_email_identity" "email" {
  email = var.email_sender
}

