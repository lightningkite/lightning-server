variable "debug" {
  default = true
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

variable "email_sender" {
}
resource "aws_ses_email_identity" "email" {
  email = var.email_sender
}
module "Base" {
  source              = "../base"
  deployment_location = var.deployment_location
  deployment_name     = var.deployment_name
  debug               = var.debug
    email_sender  = var.email_sender

}
