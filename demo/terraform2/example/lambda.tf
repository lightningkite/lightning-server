# Generated via Lightning Server.  This file will be overwritten or deleted when regenerating.
##########
# Inputs
##########

variable "lambda_memory_size" {
    type = number
    default = 1024
    nullable = false
}
variable "lambda_timeout" {
    type = number
    default = 30
    nullable = false
}
variable "lambda_snapstart" {
    type = bool
    default = false
    nullable = false
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
  layers = []
  
  environment {
    variables = {
      LIGHTNING_SERVER_SETTINGS_DECRYPTION = random_password.settings.result
    }
  }
  
  depends_on = [aws_s3_object.app_storage]
}
resource "aws_iam_role_policy_attachment" "insights_policy" {
  role       = aws_iam_role.main_exec.id
  policy_arn = "arn:aws:iam::aws:policy/CloudWatchLambdaInsightsExecutionRolePolicy"
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
    cache = {
        url = "dynamodb://${var.deployment_location}/demo_example"
    }
    jwt = {
        expiration = var.jwt_expiration 
        emailExpiration = var.jwt_emailExpiration 
        secret = random_password.jwt.result
    }
    oauth_github = var.oauth_github
    exceptions = var.exceptions
    oauth_apple = var.oauth_apple
    general = {
        projectName = var.display_name
        publicUrl = "https://${var.domain_name}"
        wsUrl = "wss://ws.${var.domain_name}?path="
        debug = var.debug
        cors = var.cors
    }
    database = {
      url = "mongodb+srv://demoexampledatabase-main:${random_password.database.result}@${replace(mongodbatlas_serverless_instance.database.connection_strings_standard_srv, "mongodb+srv://", "")}/default?retryWrites=true&w=majority"
    }
    logging = var.logging
    files = {
        storageUrl = "s3://${aws_s3_bucket.files.id}.s3-${aws_s3_bucket.files.region}.amazonaws.com"
        signedUrlExpiration = var.files_expiry
    }
    metrics = {
        url = "cloudwatch://${var.deployment_location}/${var.metrics_namespace}"
        trackingByEntryPoint = var.metrics_tracked
    }
    email = {
        url = "smtp://${aws_iam_access_key.email.id}:${aws_iam_access_key.email.ses_smtp_password_v4}@email-smtp.${var.deployment_location}.amazonaws.com:587" 
        fromEmail = "noreply@${var.domain_name}"
    }
    oauth_google = var.oauth_google
    oauth_microsoft = var.oauth_microsoft})
  filename = "${path.module}/build/raw-settings.json"
}

locals {
  # Directories start with "C:..." on Windows; All other OSs use "/" for root.
  is_windows = substr(pathexpand("~"), 0, 1) == "/" ? false : true
}
resource "null_resource" "lambda_jar_source" {
  triggers = {
    always = timestamp()
  }
  provisioner "local-exec" {
    command = local.is_windows ? "if(test-path \"${path.module}/build/lambda/\") { rd -Recurse \"${path.module}/build/lambda/\" }" : "rm -rf \"${path.module}/build/lambda/\""
    interpreter = local.is_windows ? ["PowerShell", "-Command"] : []
  }
  provisioner "local-exec" {
    command = local.is_windows ? "cp -r -force \"${path.module}/../../build/dist/lambda/.\" \"${path.module}/build/lambda/\"" : "cp -rf \"${path.module}/../../build/dist/lambda/.\" \"${path.module}/build/lambda/\""
    interpreter = local.is_windows ? ["PowerShell", "-Command"] : []
  }
  provisioner "local-exec" {
    command = "openssl enc -aes-256-cbc -md sha256 -in \"${local_sensitive_file.settings_raw.filename}\" -out \"${path.module}/build/lambda/settings.enc\" -pass pass:${random_password.settings.result}"
    interpreter = local.is_windows ? ["PowerShell", "-Command"] : []
  }
}
resource "null_resource" "settings_reread" {
  triggers = {
    settingsRawHash = local_sensitive_file.settings_raw.content
  }
  depends_on = [null_resource.lambda_jar_source]
  provisioner "local-exec" {
    command     = "openssl enc -d -aes-256-cbc -md sha256 -out \"${local_sensitive_file.settings_raw.filename}.decrypted.json\" -in \"${path.module}/build/lambda/settings.enc\" -pass pass:${random_password.settings.result}"
    interpreter = local.is_windows ? ["PowerShell", "-Command"] : []
  }
}

resource "random_password" "settings" {
  length           = 32
  special          = true
  override_special = "_"
}

data "archive_file" "lambda" {
  depends_on  = [null_resource.lambda_jar_source, null_resource.settings_reread]
  type        = "zip"
  source_dir = "${path.module}/build/lambda"
  output_path = "${path.module}/build/lambda.jar"
}



