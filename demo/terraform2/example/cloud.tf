# Generated via Lightning Server.  This file will be overwritten or deleted when regenerating.
##########
# Inputs
##########

variable "deployment_location" {
    type = string
    default = "us-west-2"
    nullable = false
}
variable "debug" {
    type = bool
    default = false
    nullable = false
}
variable "ip_prefix" {
    type = string
    default = "10.0"
    nullable = false
}
variable "domain_name_zone" {
    type = string
    nullable = false
}
variable "domain_name" {
    type = string
    nullable = false
}

##########
# Outputs
##########


##########
# Resources
##########


resource "aws_api_gateway_account" "main" {
  cloudwatch_role_arn = aws_iam_role.cloudwatch.arn
}

resource "aws_iam_role" "cloudwatch" {
  name = "demoexample"

  assume_role_policy = <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "",
      "Effect": "Allow",
      "Principal": {
        "Service": ["apigateway.amazonaws.com", "lambda.amazonaws.com"]
      },
      "Action": "sts:AssumeRole"
    }
  ]
}
EOF
}
resource "aws_iam_role_policy" "cloudwatch" {
  name = "demoexample_policy"
  role = aws_iam_role.cloudwatch.id

  policy = <<EOF
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Action": [
                "logs:CreateLogGroup",
                "logs:CreateLogStream",
                "logs:DescribeLogGroups",
                "logs:DescribeLogStreams",
                "logs:PutLogEvents",
                "logs:GetLogEvents",
                "logs:FilterLogEvents"
            ],
            "Resource": "*"
        }
    ]
}
EOF
}
data "aws_route53_zone" "main" {
  name = var.domain_name_zone
}

