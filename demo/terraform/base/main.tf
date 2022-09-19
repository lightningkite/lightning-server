##########
# main
##########
terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 4.30"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.1.0"
    }
    archive = {
      source  = "hashicorp/archive"
      version = "~> 2.2.0"
    }
  }
  required_version = "~> 1.0"
}

provider "aws" {
  region = var.deployment_location
}

module "vpc" {
  source = "terraform-aws-modules/vpc/aws"

  name = "demo-${var.deployment_name}"
  cidr = "10.0.0.0/16"

  azs             = ["${var.deployment_location}a", "${var.deployment_location}b", "${var.deployment_location}c"]
  private_subnets = ["10.0.1.0/24", "10.0.2.0/24", "10.0.3.0/24"]
  public_subnets  = ["10.0.101.0/24", "10.0.102.0/24", "10.0.103.0/24"]

  enable_nat_gateway = var.lambda_in_vpc
  single_nat_gateway = true
  enable_vpn_gateway = false
  enable_dns_hostnames = !var.lambda_in_vpc
  enable_dns_support   = !var.lambda_in_vpc
}

resource "aws_vpc_endpoint" "s3" {
  vpc_id = module.vpc.vpc_id
  service_name = "com.amazonaws.${var.deployment_location}.s3"
  route_table_ids = module.vpc.public_route_table_ids
}
resource "aws_vpc_endpoint" "executeapi" {
  vpc_id = module.vpc.vpc_id
  service_name = "com.amazonaws.${var.deployment_location}.execute-api"
  security_group_ids = [aws_security_group.executeapi.id]
  vpc_endpoint_type = "Interface"
}
resource "aws_vpc_endpoint" "lambdainvoke" {
  vpc_id = module.vpc.vpc_id
  service_name = "com.amazonaws.${var.deployment_location}.lambda"
  security_group_ids = [aws_security_group.lambdainvoke.id]
  vpc_endpoint_type = "Interface"
}

resource "aws_api_gateway_account" "main" {
  cloudwatch_role_arn = aws_iam_role.cloudwatch.arn
}

resource "aws_iam_role" "cloudwatch" {
  name = "demo${var.deployment_name}"

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
  name = "demo${var.deployment_name}_policy"
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

resource "aws_security_group" "internal" {
  name   = "demo-${var.deployment_name}-private"
  vpc_id = "${module.vpc.vpc_id}"

  ingress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = concat(module.vpc.private_subnets_cidr_blocks, module.vpc.public_subnets_cidr_blocks, var.lambda_in_vpc ? [] : ["0.0.0.0/0"])
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = concat(module.vpc.private_subnets_cidr_blocks, module.vpc.public_subnets_cidr_blocks, var.lambda_in_vpc ? [] : ["0.0.0.0/0"])
  }
}

resource "aws_security_group" "access_outside" {
  name   = "demo-${var.deployment_name}-access-outside"
  vpc_id = "${module.vpc.vpc_id}"

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks     = ["0.0.0.0/0"]
  }
}

resource "aws_security_group" "executeapi" {
  name   = "demo-${var.deployment_name}-execute-api"
  vpc_id = "${module.vpc.vpc_id}"

  ingress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = [module.vpc.vpc_cidr_block]
  }
}

resource "aws_security_group" "lambdainvoke" {
  name   = "demo-${var.deployment_name}-lambda-invoke"
  vpc_id = "${module.vpc.vpc_id}"

  ingress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = [module.vpc.vpc_cidr_block]
  }
}

##########
# database
##########
resource "random_password" "database" {
  length           = 32
  special          = true
  override_special = "-_"
}
resource "aws_db_subnet_group" "database" {
  name       = "demo-${var.deployment_name}-database2"
  subnet_ids = var.lambda_in_vpc ? module.vpc.private_subnets : module.vpc.public_subnets
}
resource "aws_rds_cluster" "database" {
  cluster_identifier = "demo-${var.deployment_name}-database"
  engine             = "aurora-postgresql"
  engine_mode        = "provisioned"
  engine_version     = "13.6"
  database_name      = "demo${var.deployment_name}database"
  master_username = "master"
  master_password = random_password.database.result
  skip_final_snapshot = var.debug
  final_snapshot_identifier = "demo-${var.deployment_name}-database"
  vpc_security_group_ids = [aws_security_group.internal.id]
  db_subnet_group_name    = "${aws_db_subnet_group.database.name}"

  serverlessv2_scaling_configuration {
    min_capacity = var.database_min_capacity
    max_capacity = var.database_max_capacity
  }
}

resource "aws_rds_cluster_instance" "database" {
  publicly_accessible = !var.lambda_in_vpc
  cluster_identifier = aws_rds_cluster.database.id
  instance_class     = "db.serverless"
  engine             = aws_rds_cluster.database.engine
  engine_version     = aws_rds_cluster.database.engine_version
  db_subnet_group_name    = "${aws_db_subnet_group.database.name}"
}

##########
# cache
##########
resource "aws_iam_policy" "cache" {
  name        = "demo-${var.deployment_name}-cache"
  path = "/demo/${var.deployment_name}/cache/"
  description = "Access to the demo-${var.deployment_name}_cache tables in DynamoDB"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = [
          "dynamodb:*",
        ]
        Effect   = "Allow"
        Resource = ["*"]
      },
    ]
  })
}
resource "aws_iam_role_policy_attachment" "cache" {
  role       = aws_iam_role.main_exec.name
  policy_arn = aws_iam_policy.cache.arn
}

##########
# jwt
##########
resource "random_password" "jwt" {
  length           = 32
  special          = true
  override_special = "!#$%&*()-_=+[]{}<>:?"
}

##########
# files
##########
####
# files: FilesSettings
####

resource "aws_s3_bucket" "files" {
  bucket_prefix = "demo-${var.deployment_name}-files"
  force_destroy = var.debug
}
resource "aws_s3_bucket_cors_configuration" "files" {
  bucket = aws_s3_bucket.files.bucket

  cors_rule {
    allowed_headers = ["*"]
    allowed_methods = ["PUT", "POST"]
    allowed_origins = ["*"]
    expose_headers  = ["ETag"]
    max_age_seconds = 3000
  }

  cors_rule {
    allowed_methods = ["GET"]
    allowed_origins = ["*"]
  }
}
resource "aws_s3_bucket_acl" "files" {
  bucket = aws_s3_bucket.files.id
  acl    = "private"
}
resource "aws_iam_policy" "files" {
  name        = "demo-${var.deployment_name}-files"
  path = "/demo/${var.deployment_name}/files/"
  description = "Access to the demo-${var.deployment_name}_files bucket"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = [
          "s3:*",
        ]
        Effect   = "Allow"
        Resource = [
            "${aws_s3_bucket.files.arn}",
            "${aws_s3_bucket.files.arn}/*",
        ]
      },
    ]
  })
}
resource "aws_iam_role_policy_attachment" "files" {
  role       = aws_iam_role.main_exec.name
  policy_arn = aws_iam_policy.files.arn
}

##########
# email
##########
resource "aws_iam_user" "email" {
  name = "demo-${var.deployment_name}-email-user"
}

resource "aws_iam_access_key" "email" {
  user = aws_iam_user.email.name
}

data "aws_iam_policy_document" "email" {
  statement {
    actions   = ["ses:SendRawEmail"]
    resources = ["*"]
  }
}

resource "aws_iam_policy" "email" {
  name = "demo-${var.deployment_name}-email-policy"
  description = "Allows sending of e-mails via Simple Email Service"
  policy      = data.aws_iam_policy_document.email.json
}

resource "aws_iam_user_policy_attachment" "email" {
  user       = aws_iam_user.email.name
  policy_arn = aws_iam_policy.email.arn
}

resource "aws_security_group" "email" {
  name   = "demo-${var.deployment_name}-${var.deployment_name}-email"
  vpc_id = module.vpc.vpc_id

  ingress {
    from_port   = 587
    to_port     = 587
    protocol    = "tcp"
    cidr_blocks = [module.vpc.vpc_cidr_block]
  }
}
resource "aws_vpc_endpoint" "email" {
  vpc_id = module.vpc.vpc_id
  service_name = "com.amazonaws.${var.deployment_location}.email-smtp"
  security_group_ids = [aws_security_group.email.id]
  vpc_endpoint_type = "Interface"
}

##########
# HTTP
##########

variable "public_http_url" {
  default = null
}
resource "aws_apigatewayv2_api" "http" {
  name = "demo-${var.deployment_name}-http"
  protocol_type = "HTTP"
}

resource "aws_apigatewayv2_stage" "http" {
  api_id = aws_apigatewayv2_api.http.id

  name = "demo-${var.deployment_name}-gateway-stage"
  auto_deploy = true

  access_log_settings {
    destination_arn = aws_cloudwatch_log_group.http_api.arn

    format = jsonencode({
      requestId               = "$context.requestId"
      sourceIp                = "$context.identity.sourceIp"
      requestTime             = "$context.requestTime"
      protocol                = "$context.protocol"
      httpMethod              = "$context.httpMethod"
      resourcePath            = "$context.resourcePath"
      routeKey                = "$context.routeKey"
      status                  = "$context.status"
      responseLength          = "$context.responseLength"
      integrationErrorMessage = "$context.integrationErrorMessage"
      }
    )
  }
}

resource "aws_apigatewayv2_integration" "http" {
  api_id = aws_apigatewayv2_api.http.id

  integration_uri    = aws_lambda_function.main.invoke_arn
  integration_type   = "AWS_PROXY"
  integration_method = "POST"
}

resource "aws_cloudwatch_log_group" "http_api" {
  name = "demo-${var.deployment_name}-http-gateway-log"

  retention_in_days = 30
}

resource "aws_apigatewayv2_route" "http" {
    api_id = aws_apigatewayv2_api.http.id
    route_key = "$default"
    target    = "integrations/${aws_apigatewayv2_integration.http.id}"
}

resource "aws_lambda_permission" "api_gateway_http" {
  statement_id  = "AllowExecutionFromAPIGatewayHTTP"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.main.function_name
  principal     = "apigateway.amazonaws.com"

  source_arn = "${aws_apigatewayv2_api.http.execution_arn}/*/*"
}

##########
# WebSockets
##########
variable "public_ws_url" {
  default = null
}

resource "aws_apigatewayv2_api" "ws" {
  name = "demo-${var.deployment_name}-gateway"
  protocol_type = "WEBSOCKET"
  route_selection_expression = "constant"
}

resource "aws_apigatewayv2_stage" "ws" {
  api_id = aws_apigatewayv2_api.ws.id

  name = "demo-${var.deployment_name}-gateway-stage"
  auto_deploy = true

  access_log_settings {
    destination_arn = aws_cloudwatch_log_group.ws_api.arn

    format = jsonencode({
      requestId               = "$context.requestId"
      sourceIp                = "$context.identity.sourceIp"
      requestTime             = "$context.requestTime"
      protocol                = "$context.protocol"
      httpMethod              = "$context.httpMethod"
      resourcePath            = "$context.resourcePath"
      routeKey                = "$context.routeKey"
      status                  = "$context.status"
      responseLength          = "$context.responseLength"
      integrationErrorMessage = "$context.integrationErrorMessage"
      }
    )
  }
}

resource "aws_apigatewayv2_integration" "ws" {
  api_id = aws_apigatewayv2_api.ws.id

  integration_uri    = aws_lambda_function.main.invoke_arn
  integration_type   = "AWS_PROXY"
  integration_method = "POST"
}

resource "aws_cloudwatch_log_group" "ws_api" {
  name = "demo-${var.deployment_name}-ws-gateway-log"

  retention_in_days = 30
}

resource "aws_apigatewayv2_route" "ws_connect" {
    api_id = aws_apigatewayv2_api.ws.id

    route_key = "$connect"
    target    = "integrations/${aws_apigatewayv2_integration.ws.id}"
}
resource "aws_apigatewayv2_route" "ws_default" {
    api_id = aws_apigatewayv2_api.ws.id

    route_key = "$default"
    target    = "integrations/${aws_apigatewayv2_integration.ws.id}"
}
resource "aws_apigatewayv2_route" "ws_disconnect" {
    api_id = aws_apigatewayv2_api.ws.id

    route_key = "$disconnect"
    target    = "integrations/${aws_apigatewayv2_integration.ws.id}"
}

resource "aws_lambda_permission" "api_gateway_ws" {
  statement_id  = "AllowExecutionFromAPIGatewayWS"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.main.function_name
  principal     = "apigateway.amazonaws.com"

  source_arn = "${aws_apigatewayv2_api.ws.execution_arn}/*/*"
}

resource "aws_iam_policy" "api_gateway_ws" {
  name        = "demo-${var.deployment_name}-api_gateway_ws"
  path = "/demo/${var.deployment_name}/api_gateway_ws/"
  description = "Access to the demo-${var.deployment_name}_api_gateway_ws management"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = [
          "execute-api:ManageConnections"
        ]
        Effect   = "Allow"
        Resource = "*"
      },
    ]
  })
}
resource "aws_iam_role_policy_attachment" "api_gateway_ws" {
  role       = aws_iam_role.main_exec.name
  policy_arn = aws_iam_policy.api_gateway_ws.arn
}

##########
# Main
##########
        resource "aws_s3_bucket" "lambda_bucket" {
          bucket_prefix = "demo-${var.deployment_name}-lambda-bucket"
          force_destroy = true
        }
        resource "aws_s3_bucket_acl" "lambda_bucket" {
          bucket = aws_s3_bucket.lambda_bucket.id
          acl    = "private"
        }

        resource "aws_iam_role" "main_exec" {
          name = "demo-${var.deployment_name}-main-exec"

          assume_role_policy = jsonencode({
            Version = "2012-10-17"
            Statement = [{
              Action = "sts:AssumeRole"
              Effect = "Allow"
              Sid    = ""
              Principal = {
                Service = "lambda.amazonaws.com"
              }
              }
            ]
          })
        }

        resource "aws_iam_role_policy_attachment" "main_policy_exec" {
          role       = aws_iam_role.main_exec.name
          policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
        }
        resource "aws_iam_role_policy_attachment" "main_policy_vpc" {
          role       = aws_iam_role.main_exec.name
          policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaVPCAccessExecutionRole"
        }
        
        resource "aws_iam_policy" "lambdainvoke" {
          name        = "demo-${var.deployment_name}-lambdainvoke"
          path = "/demo/${var.deployment_name}/lambdainvoke/"
          description = "Access to the demo-${var.deployment_name}_lambdainvoke bucket"
          policy = jsonencode({
            Version = "2012-10-17"
            Statement = [
              {
                Action = [
                  "lambda:InvokeFunction",
                ]
                Effect   = "Allow"
                Resource = "*"
              },
            ]
          })
        }
        resource "aws_iam_role_policy_attachment" "lambdainvoke" {
          role       = aws_iam_role.main_exec.name
          policy_arn = aws_iam_policy.lambdainvoke.arn
        }
        locals {
          lambda_source = "../../build/dist/lambda.zip"
        }
        resource "aws_s3_object" "app_storage" {
          bucket = aws_s3_bucket.lambda_bucket.id

          key    = "lambda-functions.zip"
          source = local.lambda_source

          source_hash = filemd5(local.lambda_source)
        }
        resource "aws_lambda_function" "main" {
          function_name = "demo-${var.deployment_name}-main"

          s3_bucket = aws_s3_bucket.lambda_bucket.id
          s3_key    = aws_s3_object.app_storage.key

          runtime = "java11"
          handler = "com.lightningkite.lightningserver.demo.AwsHandler"
          
          memory_size = "2048"
          timeout = 30
          # memory_size = "1024"

          source_code_hash = filebase64sha256(local.lambda_source)

          role = aws_iam_role.main_exec.arn
          
          dynamic "vpc_config" {
            for_each = var.lambda_in_vpc ? [1] : []
            content {
            subnet_ids = module.vpc.private_subnets
            security_group_ids = [aws_security_group.internal.id, aws_security_group.access_outside.id]
            }
          }
          
          environment {
            variables = {
              LIGHTNING_SERVER_SETTINGS = jsonencode({
                general = {
    projectName = "demo"
    publicUrl = var.public_http_url == null ? aws_apigatewayv2_stage.http.invoke_url : var.public_http_url
    wsUrl = var.public_ws_url == null ? aws_apigatewayv2_stage.ws.invoke_url : var.public_ws_url
    debug = var.debug
    cors = var.cors
},
database = {
    url = "postgresql://master:${random_password.database.result}@${aws_rds_cluster.database.endpoint}/demo${var.deployment_name}database"
},
cache = {
    url = "dynamodb://${var.deployment_location}/demo-${var.deployment_name}_${var.deployment_name}"
},
jwt = {
    expirationMilliseconds = var.jwt_expirationMilliseconds 
    emailExpirationMilliseconds = var.jwt_emailExpirationMilliseconds 
    secret = random_password.jwt.result
},
logging = var.logging,
files = {
    storageUrl = "s3://${aws_s3_bucket.files.id}.s3-${aws_s3_bucket.files.region}.amazonaws.com"
    signedUrlExpiration = var.files_expiry
},
exceptions = var.exceptions,
email = {
    url = "smtp://${aws_iam_access_key.email.id}:${aws_iam_access_key.email.ses_smtp_password_v4}@email-smtp.us-west-2.amazonaws.com:587" 
    fromEmail = var.email_sender
}
              })
            }
          }
          
          depends_on = [aws_s3_object.app_storage]
        }

        resource "aws_cloudwatch_log_group" "main" {
          name = "demo-${var.deployment_name}-main-log"
          retention_in_days = 30
        }

