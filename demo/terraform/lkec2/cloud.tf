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
variable "vpc_id" {
    type = string
    nullable = false
    description = "The AWS VPC id that you want your resources to be placed under."
}
variable "vpc_private_subnets" {
    type = list(string)
    nullable = false
}
variable "vpc_nat_gateways" {
    type = list(string)
    nullable = false
}

##########
# Outputs
##########


##########
# Resources
##########

data "aws_vpc" "main" {
  id = var.vpc_id
}
data "aws_subnet" "private" {
  for_each = toset(var.vpc_private_subnets)
  id       = each.value
}
data "aws_nat_gateway" "main" {
  for_each = toset(var.vpc_nat_gateways)
  id       = each.value
}

resource "aws_vpc_endpoint" "s3" {
  vpc_id = data.aws_vpc.main.id
  service_name = "com.amazonaws.${var.deployment_location}.s3"
  route_table_ids = toset([data.aws_vpc.main.main_route_table_id])
}
resource "aws_vpc_endpoint" "executeapi" {
  vpc_id = data.aws_vpc.main.id
  service_name = "com.amazonaws.${var.deployment_location}.execute-api"
  security_group_ids = [aws_security_group.executeapi.id]
  vpc_endpoint_type = "Interface"
}
resource "aws_vpc_endpoint" "lambdainvoke" {
  vpc_id = data.aws_vpc.main.id
  service_name = "com.amazonaws.${var.deployment_location}.lambda"
  security_group_ids = [aws_security_group.lambdainvoke.id]
  vpc_endpoint_type = "Interface"
}

resource "aws_security_group" "internal" {
  name   = "demo-example-single-ec2-private"
  vpc_id = data.aws_vpc.main.id

  ingress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = [for s in data.aws_subnet.private : s.cidr_block]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = [for s in data.aws_subnet.private : s.cidr_block]
  }
}

resource "aws_security_group" "access_outside" {
  name   = "demo-example-single-ec2-access-outside"
  vpc_id = data.aws_vpc.main.id

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks     = ["0.0.0.0/0"]
  }
}

resource "aws_security_group" "executeapi" {
  name   = "demo-example-single-ec2-execute-api"
  vpc_id = data.aws_vpc.main.id

  ingress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = [data.aws_vpc.main.cidr_block]
  }
}

resource "aws_security_group" "lambdainvoke" {
  name   = "demo-example-single-ec2-lambda-invoke"
  vpc_id = data.aws_vpc.main.id

  ingress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = [data.aws_vpc.main.cidr_block]
  }
}
data "aws_route53_zone" "main" {
  name = var.domain_name_zone
}

