##########
# Inputs
##########

variable "lambda_memory_size" {
    type = number
    default = 1024
}
variable "lambda_timeout" {
    type = number
    default = 30
}
variable "lambda_snapstart" {
    type = bool
    default = false
}

##########
# Outputs
##########


##########
# Resources
##########

resource "aws_s3_bucket" "lambda_bucket" {
  bucket_prefix = "demo-example-lambda-bucket"
  force_destroy = true
}
resource "aws_s3_bucket_acl" "lambda_bucket" {
  bucket = aws_s3_bucket.lambda_bucket.id
  acl    = "private"
}
resource "aws_iam_policy" "lambda_bucket" {
  name        = "demo-example-lambda_bucket"
  path = "/demo/example/lambda_bucket/"
  description = "Access to the demo-example_lambda_bucket bucket"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = [
          "s3:GetObject",
        ]
        Effect   = "Allow"
        Resource = [
            "${aws_s3_bucket.lambda_bucket.arn}",
            "${aws_s3_bucket.lambda_bucket.arn}/*",
        ]
      },
    ]
  })
}
resource "aws_iam_role_policy_attachment" "lambda_bucket" {
  role       = aws_iam_role.main_exec.name
  policy_arn = aws_iam_policy.lambda_bucket.arn
}

resource "aws_iam_role" "main_exec" {
  name = "demo-example-main-exec"

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

resource "aws_iam_role_policy_attachment" "dynamo" {
  role       = aws_iam_role.main_exec.name
  policy_arn = aws_iam_policy.dynamo.arn
}
resource "aws_iam_role_policy_attachment" "main_policy_exec" {
  role       = aws_iam_role.main_exec.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}
resource "aws_iam_role_policy_attachment" "main_policy_vpc" {
  role       = aws_iam_role.main_exec.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaVPCAccessExecutionRole"
}

resource "aws_iam_policy" "dynamo" {
  name        = "demo-example-dynamo"
  path = "/demo/example/dynamo/"
  description = "Access to the demo-example_dynamo tables in DynamoDB"
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
resource "aws_iam_policy" "lambdainvoke" {
  name        = "demo-example-lambdainvoke"
  path = "/demo/example/lambdainvoke/"
  description = "Access to the demo-example_lambdainvoke bucket"
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

resource "aws_s3_object" "app_storage" {
  bucket = aws_s3_bucket.lambda_bucket.id

  key    = "lambda-functions.zip"
  source = data.archive_file.lambda.output_path

  source_hash = data.archive_file.lambda.output_md5
  depends_on = [data.archive_file.lambda]
}

resource "aws_lambda_function" "main" {
  function_name = "demo-example-main"
  publish = var.lambda_snapstart

  s3_bucket = aws_s3_bucket.lambda_bucket.id
  s3_key    = aws_s3_object.app_storage.key

  runtime = "java11"
  handler = "com.lightningkite.lightningserver.demo.AwsHandler"
  
  memory_size = "${var.lambda_memory_size}"
  timeout = var.lambda_timeout
  # memory_size = "1024"

  source_code_hash = data.archive_file.lambda.output_base64sha256

  role = aws_iam_role.main_exec.arn
  
  snap_start {
    apply_on = "PublishedVersions"
  }
  
  
  
  environment {
    variables = {
      LIGHTNING_SERVER_SETTINGS_DECRYPTION = random_password.settings.result
    }
  }
  
  depends_on = [aws_s3_object.app_storage]
}

resource "aws_lambda_alias" "main" {
  name             = "prod"
  description      = "The current production version of the lambda."
  function_name    = aws_lambda_function.main.arn
  function_version = var.lambda_snapstart ? aws_lambda_function.main.version : "$LATEST"
}

resource "aws_cloudwatch_log_group" "main" {
  name = "demo-example-main-log"
  retention_in_days = 30
}

resource "local_sensitive_file" "settings_raw" {
  content = jsonencode({
    general = {
        projectName = var.display_name
        publicUrl = "https://${var.domain_name}"
        wsUrl = "wss://ws.${var.domain_name}"
        debug = var.debug
        cors = var.cors
    }
    database = {
      url = "mongodb+srv://demoexampledatabase-main:${random_password.database.result}@${replace(mongodbatlas_serverless_instance.database.connection_strings_standard_srv, "mongodb+srv://", "")}/default?retryWrites=true&w=majority"
    }
    cache = {
        url = "dynamodb://${var.deployment_location}/demo_example"
    }
    jwt = {
        expirationMilliseconds = var.jwt_expirationMilliseconds 
        emailExpirationMilliseconds = var.jwt_emailExpirationMilliseconds 
        secret = random_password.jwt.result
    }
    oauth_github = var.oauth_github
    logging = var.logging
    files = {
        storageUrl = "s3://${aws_s3_bucket.files.id}.s3-${aws_s3_bucket.files.region}.amazonaws.com"
        signedUrlExpiration = var.files_expiry
    }
    metrics = var.metrics
    exceptions = var.exceptions
    email = {
        url = "smtp://${aws_iam_access_key.email.id}:${aws_iam_access_key.email.ses_smtp_password_v4}@email-smtp.us-west-2.amazonaws.com:587" 
        fromEmail = "noreply@${var.domain_name}"
    }
    oauth_google = var.oauth_google
    oauth_apple = var.oauth_apple})
  filename = "${path.module}/build/raw-settings.json"
}

resource "null_resource" "settings_encrypted" {
  triggers = {
    settingsRawHash = local_sensitive_file.settings_raw.content
  }
  provisioner "local-exec" {
    command = "openssl enc -aes-256-cbc -in \"${local_sensitive_file.settings_raw.filename}\" -out \"${path.module}/../../build/dist/lambda/settings.enc\" -pass pass:${random_password.settings.result}"
  }
}

resource "random_password" "settings" {
  length           = 32
  special          = true
  override_special = "_"
}

data "archive_file" "lambda" {
  depends_on = [null_resource.settings_encrypted]
  type        = "zip"
  source_dir = "${path.module}/../../build/dist/lambda"
  output_path = "${path.module}/build/lambda.jar"
}


