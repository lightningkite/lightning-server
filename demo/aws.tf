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
  backend "s3" {
    bucket = "demo"
    key    = var.deployment_name
    region = var.deployment_location
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
variable "basis_domain" {
}
variable "subdomain" {
  default = "api"
}
variable "debug" {
  default = false
}

# locals {
#     subdomainPrefix = "${var.deployment_name}.${var.subdomain}" 
#     subdomain = "${var.deployment_name}.${var.subdomain}.${var.basis_domain}"
# }
# 
# data "aws_route53_zone" "main" {
#   name = var.basis_domain
# }

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

resource "aws_vpc_endpoint" "s3" {
  vpc_id = "${module.vpc.vpc_id}"
  service_name = "com.amazonaws.${var.deployment_location}.s3"
  route_table_ids = module.vpc.public_route_table_ids
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
    cidr_blocks = concat(module.vpc.private_subnets_cidr_blocks, module.vpc.public_subnets_cidr_blocks)
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = concat(module.vpc.private_subnets_cidr_blocks, module.vpc.public_subnets_cidr_blocks)
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
# cache: CacheSettings
####

variable "cache_node_type" {
  default = "cache.t2.micro"
}
variable "cache_node_count" {
  default = 1
}

resource "aws_elasticache_cluster" "cache" {
  cluster_id           = "demo-${var.deployment_name}-cache"
  engine               = "memcached"
  node_type            = var.cache_node_type
  num_cache_nodes      = var.cache_node_count
  parameter_group_name = "default.memcached1.6"
  port                 = 11211
  security_group_ids   = [aws_security_group.internal.id]
  subnet_group_name    = aws_elasticache_subnet_group.cache.name
}
resource "aws_elasticache_subnet_group" "cache" {
  name       = "demo-${var.deployment_name}-cache"
  subnet_ids = module.vpc.private_subnets
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
# email: EmailSettings
####

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
variable "email_sender" {
}
resource "aws_ses_email_identity" "email" {
  email = var.email_sender
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

  runtime = "java11"
  handler = "com.lightningkite.lightningserver.demo.AwsHandler"
  
  memory_size = "2048"
  timeout = 30
  # memory_size = "1024"

  source_code_hash = filesha256(local.lambda_source)

  role = aws_iam_role.main_exec.arn
  depends_on = [aws_s3_object.app_storage]
  
  vpc_config {
    subnet_ids = module.vpc.public_subnets
    security_group_ids = [aws_security_group.internal.id, aws_security_group.access_outside.id]
  }
  
  environment {
    variables = {
      LIGHTNING_SERVER_SETTINGS = jsonencode({
        general = {
            projectName = "demo"
            publicUrl = aws_apigatewayv2_stage.http.invoke_url
            wsUrl = aws_apigatewayv2_stage.ws.invoke_url
            debug = var.debug
        },
        database = {
            url = "mongodb://master:${random_password.database.result}@${aws_docdb_cluster_instance.database[0].endpoint}/?retryWrites=false"
            databaseName = "demo-${var.deployment_name}_database"
        },
        cache = {
            url = "memcached://${aws_elasticache_cluster.cache.cluster_address}:11211"
        },
        jwt = {
            expirationMilliseconds = var.jwt_expirationMilliseconds 
            emailExpirationMilliseconds = var.jwt_emailExpirationMilliseconds 
            secret = random_password.jwt.result
        },
        oauth-google = var.oauth-google,
        logging = var.logging,
        files = {
            storageUrl = "s3://${aws_s3_bucket.files.id}.s3-${aws_s3_bucket.files.region}.amazonaws.com"
            signedUrlExpiration = var.files_expiry
        },
        oauth-github = var.oauth-github,
        exceptions = var.exceptions,
        email = {
            option = "Smtp" 
            smtp = {
                hostName = "email-smtp.us-west-2.amazonaws.com"
                port = 587
                username = aws_iam_access_key.email.id
                password = aws_iam_access_key.email.ses_smtp_password_v4
                useSSL = true
                fromEmail = aws_ses_email_identity.email.email
            }
        },
        oauth-apple = var.oauth-apple
      })
    }
  }
}

resource "aws_cloudwatch_log_group" "main" {
  name = "demo-${var.deployment_name}-main-log"

  retention_in_days = 30
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

resource "aws_lambda_permission" "api_gateway_http" {
  statement_id  = "AllowExecutionFromAPIGatewayHTTP"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.main.function_name
  principal     = "apigateway.amazonaws.com"

  source_arn = "${aws_apigatewayv2_api.http.execution_arn}/*/*"
}


#resource "aws_route53_record" "cert_api_validations" {
#  allow_overwrite = true
#  count           = length(aws_acm_certificate.cert_api.domain_validation_options)
#  zone_id = aws_route53_zone.api.zone_id
#  name    = element(aws_acm_certificate.cert_api.domain_validation_options.*.resource_record_name, count.index)
#  type    = element(aws_acm_certificate.cert_api.domain_validation_options.*.resource_record_type, count.index)
#  records = [element(aws_acm_certificate.cert_api.domain_validation_options.*.resource_record_value, count.index)]
#  ttl     = 60
#}

#resource "aws_route53_record" "http" {
#  zone_id = aws_route53_zone.main.zone_id
#  name    = local.subdomain
#  type    = "A"
#  
#  alias {
#    name                   = aws_apigatewayv2_domain_name.api.domain_name_configuration[0].target_domain_name
#    zone_id                = aws_apigatewayv2_domain_name.api.domain_name_configuration[0].hosted_zone_id
#    evaluate_target_health = false
#  }
#}

            

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

resource "aws_lambda_permission" "api_gateway_ws" {
  statement_id  = "AllowExecutionFromAPIGatewayWS"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.main.function_name
  principal     = "apigateway.amazonaws.com"

  source_arn = "${aws_apigatewayv2_api.ws.execution_arn}/*/*"
}
