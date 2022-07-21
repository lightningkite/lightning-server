####
# General configuration for an AWS Api http project
####

terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 4.0.0"
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

variable "deployment_location" {
  default = "us-west-2"
}
variable "deployment_name" {
  default = "test"
}
variable "debug" {
  default = false
}

module "vpc" {
  source = "terraform-aws-modules/vpc/aws"

  name = "demo-${var.deployment_name}"
  cidr = "10.0.0.0/16"

  azs             = ["${var.deployment_location}a", "${var.deployment_location}b", "${var.deployment_location}c"]
  private_subnets = ["10.0.1.0/24", "10.0.2.0/24", "10.0.3.0/24"]
  public_subnets  = ["10.0.101.0/24", "10.0.102.0/24", "10.0.103.0/24"]

  enable_nat_gateway = false
  enable_vpn_gateway = false
}

resource "aws_api_gateway_account" "main" {
  cloudwatch_role_arn = aws_iam_role.cloudwatch.arn
}

resource "aws_iam_role" "cloudwatch" {
  name = "api_gateway_cloudwatch_global"

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
  name = "default"
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
    cidr_blocks = module.vpc.private_subnets_cidr_blocks
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = module.vpc.private_subnets_cidr_blocks
  }
}

resource "aws_s3_bucket" "lambda_bucket" {
  bucket_prefix = "demo-${var.deployment_name}-lambda-bucket"
  force_destroy = true
}
resource "aws_s3_bucket_acl" "lambda_bucket" {
  bucket = aws_s3_bucket.lambda_bucket.id
  acl    = "private"
}

locals {
  lambda_source = "build/dist/lambda.zip"
}
resource "aws_s3_object" "app_storage" {
  bucket = aws_s3_bucket.lambda_bucket.id

  key    = "lambda-functions.zip"
  source = local.lambda_source

  source_hash = filemd5(local.lambda_source)
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


####
# database: DatabaseSettings
####

variable "database_expiry" {
    default = "P1D"
}
resource "random_password" "database" {
  length           = 32
  special          = true
  override_special = "-_"
}
resource "aws_docdb_subnet_group" "database" {
  name       = "demo-${var.deployment_name}-database"
  subnet_ids = module.vpc.private_subnets
}
resource "aws_docdb_cluster_parameter_group" "database" {
  family = "docdb4.0"
  name = "demo-${var.deployment_name}-database-parameter-group"
  parameter {
    name  = "tls"
    value = "disabled"
  }
}
resource "aws_docdb_cluster" "database" {
  cluster_identifier = "demo-${var.deployment_name}-database"
  engine = "docdb"
  master_username = "master"
  master_password = random_password.database.result
  backup_retention_period = 5
  preferred_backup_window = "07:00-09:00"
  skip_final_snapshot = true

  db_cluster_parameter_group_name = "${aws_docdb_cluster_parameter_group.database.name}"
  vpc_security_group_ids = [aws_security_group.internal.id]
  db_subnet_group_name    = "${aws_docdb_subnet_group.database.name}"
}
resource "aws_docdb_cluster_instance" "database" {
  count              = 1
  identifier         = "demo-${var.deployment_name}-database-${count.index}"
  cluster_identifier = "${aws_docdb_cluster.database.id}"
  instance_class     = "db.t4g.medium"
}

####
# jwt: JwtSigner
####

variable "jwt_expirationMilliseconds" {
  default = 31540000000
}
variable "jwt_emailExpirationMilliseconds" {
  default = 1800000
}
resource "random_password" "jwt" {
  length           = 32
  special          = true
  override_special = "!#$%&*()-_=+[]{}<>:?"
}

####
# oauth-google
####

variable "oauth-google" {
  default = null
}

####
# logging
####

variable "logging" {
  default = {}
}

####
# files: FilesSettings
####

variable "files_expiry" {
    default = "P1D"
}

resource "aws_s3_bucket" "files" {
  bucket_prefix = "demo-${var.deployment_name}-files"
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

####
# oauth-github
####

variable "oauth-github" {
  default = null
}

####
# exceptions
####

variable "exceptions" {
  default = {}
}

####
# email
####

variable "email" {
  default = {}
}

####
# oauth-apple
####

variable "oauth-apple" {
  default = null
}
####
# App Declaration
####

resource "aws_lambda_function" "main" {
  function_name = "demo-${var.deployment_name}-main"

  s3_bucket = aws_s3_bucket.lambda_bucket.id
  s3_key    = aws_s3_object.app_storage.key

  memory_size = "2096"

  runtime = "java11"
  handler = "com.lightningkite.lightningserver.demo.AwsHandler"

  source_code_hash = filesha256(local.lambda_source)

  role = aws_iam_role.main_exec.arn
  depends_on = []
  
  vpc_config {
    subnet_ids = module.vpc.public_subnets
    security_group_ids = [aws_security_group.internal.id]
  }
  
  environment {
    variables = {
      LIGHTNING_SERVER_SETTINGS = jsonencode({
        general = {
            projectName = "demo"
            publicUrl = aws_apigatewayv2_api.http.api_endpoint
            debug = var.debug
        },
        database = {
            url = "mongodb://master:${random_password.database.result}@${aws_docdb_cluster_instance.database[0].endpoint}"
            databaseName = "demo-${var.deployment_name}_database"
        },
        jwt = {
            expirationMilliseconds = var.jwt_expirationMilliseconds 
            emailExpirationMilliseconds = var.jwt_emailExpirationMilliseconds 
            secret = random_password.jwt.result
        },
        oauth-google = var.oauth-google,
        logging = var.logging,
        files = {
            storageUrl = "s3://${aws_s3_bucket.files.bucket_regional_domain_name}"
            signedUrlExpiration = var.files_expiry
        },
        oauth-github = var.oauth-github,
        exceptions = var.exceptions,
        email = var.email,
        oauth-apple = var.oauth-apple
      })
    }
  }
}

resource "aws_cloudwatch_log_group" "main" {
  name = "demo-${var.deployment_name}-main-log"

  retention_in_days = 30
}

resource "aws_lambda_permission" "api_gateway" {
  statement_id  = "AllowExecutionFromAPIGateway"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.main.function_name
  principal     = "apigateway.amazonaws.com"

  source_arn = "${aws_apigatewayv2_api.http.execution_arn}/*/*"
}
resource "aws_lambda_permission" "api_gatewayb" {
  statement_id  = "AllowExecutionFromAPIGatewayWS"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.main.function_name
  principal     = "apigateway.amazonaws.com"

  source_arn = "${aws_apigatewayv2_api.ws.execution_arn}/*/*"
}

####
# ApiGateway for Http
####
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

            

####
# ApiGateway for Websockets
####
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
