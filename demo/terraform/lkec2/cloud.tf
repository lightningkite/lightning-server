# Generated via Lightning Server.  This file will be overwritten or deleted when regenerating.
##########
# Inputs
##########

variable "deployment_location" {
    type = string
    default = "us-west-2"
    nullable = false
    description = "The AWS region key to deploy all resources in."
}
variable "debug" {
    type = bool
    default = false
    nullable = false
    description = "The GeneralSettings debug. Debug true will turn on various things during run time for easier development and bug tracking. Should be false for production environments."
}
variable "ip_prefix" {
    type = string
    default = "10.0"
    nullable = false
}
variable "domain_name_zone" {
    type = string
    nullable = false
    description = "The AWS Hosted zone the domain will be placed under."
}
variable "domain_name" {
    type = string
    nullable = false
    description = "The domain the server will be hosted at."
}

##########
# Outputs
##########


##########
# Resources
##########

data "aws_route53_zone" "main" {
  name = var.domain_name_zone
}

