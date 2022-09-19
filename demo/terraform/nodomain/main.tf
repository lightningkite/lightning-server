module "Base" {
  source              = "../base"
  # main
  deployment_location = var.deployment_location
  deployment_name = var.deployment_name
  debug = var.debug
  lambda_in_vpc = var.lambda_in_vpc
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
  email_sender = var.email_sender
}
##########
# main
##########
provider "aws" {
  region = var.deployment_location
}

##########
# email
##########
resource "aws_ses_email_identity" "email" {
  email = var.email_sender
}

