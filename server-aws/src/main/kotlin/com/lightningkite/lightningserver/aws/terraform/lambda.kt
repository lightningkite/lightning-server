@file:Suppress("DEPRECATION")

package com.lightningkite.lightningserver.aws.terraform

import com.lightningkite.lightningserver.schedule.Schedule
import com.lightningkite.lightningserver.schedule.Scheduler
import kotlinx.datetime.*
import kotlinx.datetime.TimeZone


internal fun awsLambdaCloudwatch(projectInfo: TerraformProjectInfo) = with(projectInfo) {
    TerraformSection(
        name = "cloudwatch",
        inputs = listOf(
            TerraformInput(
                name = "emergencyInvocationsPerMinuteThreshold",
                type = "number",
                default = "null",
                nullable = true,
                description = "Number of Invocations Per Minute, Assign null to not create this alarm. (DEPRECATED!! Use emergencyInvocations which allows defined both threshold and period)"
            ),
            TerraformInput(
                name = "emergencyComputePerMinuteThreshold",
                type = "number",
                default = "null",
                nullable = true,
                description = "Milliseconds of Compute Per Minute, Assign null to not create this alarm. (DEPRECATED!! Use emergencyCompute which allows defined both threshold and period)"
            ),
            TerraformInput(
                name = "panicInvocationsPerMinuteThreshold",
                type = "number",
                default = "null",
                nullable = true,
                description = "Number of Invocations Per Minute, Assign null to not create this alarm. (DEPRECATED!! Use panicInvocations which allows defined both threshold and period)"
            ),
            TerraformInput(
                name = "panicComputePerMinuteThreshold",
                type = "number",
                default = "null",
                nullable = true,
                description = "Milliseconds of Compute Per Minute, Assign null to not create this alarm. (DEPRECATED!! Use panicCompute which allows defined both threshold and period)"
            ),

            TerraformInput(
                name = "emergencyInvocations",
                type = "object({ threshold = number, period = number, evaluationPeriods = number, dataPointsToAlarm = number })",
                default = "null",
                nullable = true,
                description = "The configurations for the Emergency Invocation alarm. Threshold is the Number of Invocations, Period is the timeframe in Minutes, and DataPointsToAlarm are how many periods need to breach in the number of EvaluationPeriods before an alarm is triggered. Assign null to not create this alarm.",
                validations = listOf(
                    Validation(
                        condition = "(var.emergencyInvocations == null ? true : var.emergencyInvocations.evaluationPeriods > 0)",
                        errorMessage = """"emergencyInvocations evaluationPeriods must be greater than 0"""",
                    ),
                    Validation(
                        condition = "(var.emergencyInvocations == null ? true : (var.emergencyInvocations.dataPointsToAlarm <= var.emergencyInvocations.evaluationPeriods && var.emergencyInvocations.dataPointsToAlarm > 0))",
                        errorMessage = """"emergencyInvocations dataPointsToAlarm must be greater than 0 and less than or equal to emergencyInvocations evaluationPeriods"""",
                    )
                )
            ),
            TerraformInput(
                name = "emergencyCompute",
                type = "object({ threshold = number, period = number, statistic = string, evaluationPeriods = number, dataPointsToAlarm = number })",
                default = "null",
                nullable = true,
                description = "The configurations for the Emergency Compute alarm. Threshold is the Milliseconds of Compute, Period is the timeframe in Minutes, and DataPointsToAlarm are how many periods need to breach in the number of EvaluationPeriods before an alarm is triggered. Assign null to not create this alarm.",
                validations = listOf(
                    Validation(
                        condition = "(var.emergencyCompute == null ? true : contains([\"Sum\", \"Average\", \"Maximum\"], var.emergencyCompute.statistic))",
                        errorMessage = """"Allowed values for emergencyCompute statistic are: \"Sum\", \"Average\", \"Maximum\".""""
                    ),
                    Validation(
                        condition = "(var.emergencyCompute == null ? true : var.emergencyCompute.evaluationPeriods > 0)",
                        errorMessage = """"emergencyCompute evaluationPeriods must be greater than 0"""",
                    ),
                    Validation(
                        condition = "(var.emergencyCompute == null ? true : (var.emergencyCompute.dataPointsToAlarm <= var.emergencyCompute.evaluationPeriods && var.emergencyCompute.dataPointsToAlarm > 0))",
                        errorMessage = """"emergencyCompute dataPointsToAlarm must be greater than 0 and less than or equal to emergencyCompute evaluationPeriods"""",
                    )
                )
            ),
            TerraformInput(
                name = "panicInvocations",
                type = "object({ threshold = number, period = number, evaluationPeriods = number, dataPointsToAlarm = number })",
                default = "null",
                nullable = true,
                description = "The configurations for the Panic Invocations alarm. Threshold is the Number of Invocations, Period is the timeframe in Minutes, and DataPointsToAlarm are how many periods need to breach in the number of EvaluationPeriods before an alarm is triggered. Assign null to not create this alarm.",
                validations = listOf(
                    Validation(
                        condition = "(var.panicInvocations == null ? true : var.panicInvocations.evaluationPeriods > 0)",
                        errorMessage = """"panicInvocations evaluationPeriods must be greater than 0"""",
                    ),
                    Validation(
                        condition = "(var.panicInvocations == null ? true : (var.panicInvocations.dataPointsToAlarm <= var.panicInvocations.evaluationPeriods && var.panicInvocations.dataPointsToAlarm > 0))",
                        errorMessage = """"panicInvocations dataPointsToAlarm must be greater than 0 and less than or equal to panicInvocations evaluationPeriods"""",
                    )
                )
            ),
            TerraformInput(
                name = "panicCompute",
                type = "object({ threshold = number, period = number, statistic = string, evaluationPeriods = number, dataPointsToAlarm = number })",
                default = "null",
                nullable = true,
                description = "The configurations for the Panic Compute alarm. Threshold is the Milliseconds of Compute, Period is the timeframe in Minutes, and DataPointsToAlarm are how many periods need to breach in the number of EvaluationPeriods before an alarm is triggered. Assign null to not create this alarm.",
                validations = listOf(
                    Validation(
                        condition = "(var.panicCompute == null ? true : contains([\"Sum\", \"Average\", \"Maximum\"], var.panicCompute.statistic))",
                        errorMessage = """"Allowed values for panicCompute statistic are: \"Sum\", \"Average\", \"Maximum\".""""
                    ),
                    Validation(
                        condition = "(var.panicCompute == null ? true : var.panicCompute.evaluationPeriods > 0)",
                        errorMessage = """"panicCompute evaluationPeriods must be greater than 0"""",
                    ),
                    Validation(
                        condition = "(var.panicCompute == null ? true : (var.panicCompute.dataPointsToAlarm <= var.panicCompute.evaluationPeriods && var.panicCompute.dataPointsToAlarm > 0))",
                        errorMessage = """"panicCompute dataPointsToAlarm must be greater than 0 and less than or equal to panicCompute evaluationPeriods"""",
                    )
                )
            ),

            TerraformInput.string(
                "emergencyContact",
                null,
                nullable = true,
                description = "The email address that will receive emails when alarms are triggered."
            )
        ),
        emit = {
            appendLine(
                """
            locals {
              anyNotifications = (var.emergencyContact != null &&
              (var.emergencyInvocationsPerMinuteThreshold != null ||
              var.emergencyComputePerMinuteThreshold != null ||
              var.panicInvocationsPerMinuteThreshold != null ||
              var.panicComputePerMinuteThreshold != null ||
              var.emergencyInvocations != null ||
              var.emergencyCompute != null ||
              var.panicInvocations != null ||
              var.panicCompute != null))
            }
            resource "aws_sns_topic" "emergency" {
              count = local.anyNotifications ? 1 : 0
              name  = "${namePrefix}_emergencies"
            }
            resource "aws_sns_topic_subscription" "emergency_primary" {
              count     = local.anyNotifications ? 1 : 0
              topic_arn = aws_sns_topic.emergency[0].arn
              protocol  = "email"
              endpoint  = var.emergencyContact
            }
            resource "aws_cloudwatch_metric_alarm" "emergency_minute_invocations" {
              count                     = local.anyNotifications && var.emergencyInvocationsPerMinuteThreshold != null ? 1 : 0
              alarm_name                = "${namePrefix}_emergency_invocations"
              comparison_operator       = "GreaterThanOrEqualToThreshold"
              evaluation_periods        = "1"
              metric_name               = "Invocations"
              namespace                 = "AWS/Lambda"
              period                    = "60"
              statistic                 = "Sum"
              threshold                 = var.emergencyInvocationsPerMinuteThreshold
              alarm_description         = ""
              insufficient_data_actions = []
              dimensions = {
                FunctionName = aws_lambda_function.main.function_name
              }
              alarm_actions = [aws_sns_topic.emergency[0].arn]
            }
            resource "aws_cloudwatch_metric_alarm" "emergency_minute_compute" {
              count                     = local.anyNotifications && var.emergencyComputePerMinuteThreshold != null ? 1 : 0
              alarm_name                = "${namePrefix}_emergency_compute"
              comparison_operator       = "GreaterThanOrEqualToThreshold"
              evaluation_periods        = "1"
              metric_name               = "Duration"
              namespace                 = "AWS/Lambda"
              period                    = "60"
              statistic                 = "Sum"
              threshold                 = var.emergencyComputePerMinuteThreshold
              alarm_description         = ""
              insufficient_data_actions = []
              dimensions = {
                FunctionName = aws_lambda_function.main.function_name
              }
              alarm_actions = [aws_sns_topic.emergency[0].arn]
            }
            resource "aws_cloudwatch_metric_alarm" "panic_minute_invocations" {
              count                     = local.anyNotifications && var.panicInvocationsPerMinuteThreshold != null ? 1 : 0
              alarm_name                = "${namePrefix}_panic_invocations"
              comparison_operator       = "GreaterThanOrEqualToThreshold"
              evaluation_periods        = "1"
              metric_name               = "Invocations"
              namespace                 = "AWS/Lambda"
              period                    = "60"
              statistic                 = "Sum"
              threshold                 = var.panicInvocationsPerMinuteThreshold
              alarm_description         = ""
              insufficient_data_actions = []
              dimensions = {
                FunctionName = aws_lambda_function.main.function_name
              }
              alarm_actions = [aws_sns_topic.emergency[0].arn]
            }
            resource "aws_cloudwatch_metric_alarm" "panic_minute_compute" {
              count                     = local.anyNotifications && var.panicComputePerMinuteThreshold != null ? 1 : 0
              alarm_name                = "${namePrefix}_panic_compute"
              comparison_operator       = "GreaterThanOrEqualToThreshold"
              evaluation_periods        = "1"
              metric_name               = "Duration"
              namespace                 = "AWS/Lambda"
              period                    = "60"
              statistic                 = "Sum"
              threshold                 = var.panicComputePerMinuteThreshold
              alarm_description         = ""
              insufficient_data_actions = []
              dimensions = {
                FunctionName = aws_lambda_function.main.function_name
              }
              alarm_actions = [aws_sns_topic.emergency[0].arn]
            }
            
            
            resource "aws_cloudwatch_metric_alarm" "emergency_invocations" {
              count = (local.anyNotifications &&
              var.emergencyInvocations != null ?
                1 : 0)
              alarm_name                = "${namePrefix}_emergency_invocations"
              comparison_operator       = "GreaterThanOrEqualToThreshold"
              evaluation_periods        = var.emergencyInvocations.evaluationPeriods
              datapoints_to_alarm       = var.emergencyInvocations.dataPointsToAlarm
              metric_name               = "Invocations"
              namespace                 = "AWS/Lambda"
              period                    = var.emergencyInvocations.period * 60
              statistic                 = "Sum"
              threshold                 = var.emergencyInvocations.threshold
              alarm_description         = ""
              insufficient_data_actions = []
              dimensions = {
                FunctionName = aws_lambda_function.main.function_name
              }
              alarm_actions = [aws_sns_topic.emergency[0].arn]
            }
            resource "aws_cloudwatch_metric_alarm" "emergency_compute" {
              count = (local.anyNotifications &&
              var.emergencyCompute != null ?
                1 : 0)
              alarm_name                = "${namePrefix}_emergency_compute"
              comparison_operator       = "GreaterThanOrEqualToThreshold"
              evaluation_periods        = var.emergencyCompute.evaluationPeriods
              datapoints_to_alarm       = var.emergencyCompute.dataPointsToAlarm
              metric_name               = "Duration"
              namespace                 = "AWS/Lambda"
              period                    = var.emergencyCompute.period * 60
              statistic                 = var.emergencyCompute.statistic
              threshold                 = var.emergencyCompute.threshold
              alarm_description         = ""
              insufficient_data_actions = []
              dimensions = {
                FunctionName = aws_lambda_function.main.function_name
              }
              alarm_actions = [aws_sns_topic.emergency[0].arn]
            }
            resource "aws_cloudwatch_metric_alarm" "panic_invocations" {
              count = (local.anyNotifications &&
              var.panicInvocations != null ?
                1 : 0)
              alarm_name                = "${namePrefix}_panic_invocations"
              comparison_operator       = "GreaterThanOrEqualToThreshold"
              evaluation_periods        = var.panicInvocations.evaluationPeriods
              datapoints_to_alarm       = var.panicInvocations.dataPointsToAlarm
              metric_name               = "Invocations"
              namespace                 = "AWS/Lambda"
              period                    = var.panicInvocations.period * 60
              statistic                 = "Sum"
              threshold                 = var.panicInvocations.threshold
              alarm_description         = ""
              insufficient_data_actions = []
              dimensions = {
                FunctionName = aws_lambda_function.main.function_name
              }
              alarm_actions = [aws_sns_topic.emergency[0].arn]
            }
            resource "aws_cloudwatch_metric_alarm" "panic_compute" {
              count = (local.anyNotifications &&
              var.panicCompute != null ?
                1 : 0)
              alarm_name                = "${namePrefix}_panic_compute"
              comparison_operator       = "GreaterThanOrEqualToThreshold"
              evaluation_periods        = var.panicCompute.evaluationPeriods
              datapoints_to_alarm       = var.panicCompute.dataPointsToAlarm
              metric_name               = "Duration"
              namespace                 = "AWS/Lambda"
              period                    = var.panicCompute.period * 60
              statistic                 = var.panicCompute.statistic
              threshold                 = var.panicCompute.threshold
              alarm_description         = ""
              insufficient_data_actions = []
              dimensions = {
                FunctionName = aws_lambda_function.main.function_name
              }
              alarm_actions = [aws_sns_topic.emergency[0].arn]
            }
            
        
            resource "aws_api_gateway_account" "main" {
              cloudwatch_role_arn = aws_iam_role.cloudwatch.arn
            }
            
            resource "aws_iam_role" "cloudwatch" {
              name = "${namePrefixSafe}"
            
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
              name = "${namePrefixSafe}_policy"
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
        """.trimIndent()
            )
        }
    )
}

internal fun scheduleAwsHandlers(projectInfo: TerraformProjectInfo) = with(projectInfo) {
    Scheduler.schedules.values.map {
        val safeName = it.name.filter { it.isLetterOrDigit() || it == '_' }
        when (val s = it.schedule) {
            is Schedule.Daily -> {
                val utcTime =
                    LocalDateTime(LocalDate(2001, 1, 1), s.time).toInstant(s.zone).toLocalDateTime(TimeZone.UTC)
                TerraformSection(
                    name = "schedule_${it.name}",
                    emit = {
                        appendLine(
                            """
                    resource "aws_cloudwatch_event_rule" "scheduled_task_${safeName}" {
                      name                = "${namePrefix}_${safeName}"
                      schedule_expression = "cron(${utcTime.minute} ${utcTime.hour} * * ? *)"
                    }
                    resource "aws_cloudwatch_event_target" "scheduled_task_${safeName}" {
                      rule      = aws_cloudwatch_event_rule.scheduled_task_${safeName}.name
                      target_id = "lambda"
                      arn       = aws_lambda_alias.main.arn
                      input     = "{\"scheduled\": \"${it.name}\"}"
                    }
                """.trimIndent()
                        )
                    }
                )
            }

            is Schedule.Frequency -> {
                TerraformSection(
                    name = "schedule_${it.name}",
                    emit = {
                        appendLine(
                            """
                    resource "aws_cloudwatch_event_rule" "scheduled_task_${safeName}" {
                      name                = "${namePrefix}_${safeName}"
                      schedule_expression = "rate(${s.gap.inWholeMinutes} minute${if (s.gap.inWholeMinutes > 1) "s" else ""})"
                    }
                    resource "aws_cloudwatch_event_target" "scheduled_task_${safeName}" {
                      rule      = aws_cloudwatch_event_rule.scheduled_task_${safeName}.name
                      target_id = "lambda"
                      arn       = aws_lambda_alias.main.arn
                      input     = "{\"scheduled\": \"${it.name}\"}"
                    }
                """.trimIndent()
                        )
                    }
                )
            }

            is Schedule.Cron -> {
                TerraformSection(
                    name = "schedule_${it.name}",
                    emit = {
                        appendLine(
                            """
                    resource "aws_cloudwatch_event_rule" "scheduled_task_${safeName}" {
                      name                = "${namePrefix}_${safeName}"
                      schedule_expression = "cron(${s.cron})"
                    }
                    resource "aws_cloudwatch_event_target" "scheduled_task_${safeName}" {
                      rule      = aws_cloudwatch_event_rule.scheduled_task_${safeName}.name
                      target_id = "lambda"
                      arn       = aws_lambda_alias.main.arn
                      input     = "{\"scheduled\": \"${it.name}\"}"
                    }
                """.trimIndent()
                        )
                    }
                )
            }
        }
    }
}

internal fun awsLambdaHandler(
    project: TerraformProjectInfo,
    handlerFqn: String,
    otherSections: List<TerraformSection>,
) = TerraformSection(
    name = "lambda",
    inputs = listOf(
        TerraformInput.number(
            "lambda_memory_size",
            1024,
            description = "The amount of ram available (in Megabytes) to the virtual machine running in Lambda."
        ),
        TerraformInput.number(
            "lambda_timeout",
            30,
            description = "How long an individual lambda invocation can run before forcefully being shut down."
        ),
        TerraformInput.boolean(
            "lambda_snapstart",
            false,
            description = "Whether or not lambda will deploy with SnapStart which compromises deploy time for shorter cold start time."
        ),
    ),
    emit = {
        appendLine("""
        resource "aws_s3_bucket" "lambda_bucket" {
          bucket_prefix = "${project.namePrefixPathSegment}-lambda-bucket"
          force_destroy = true
        }
        
        resource "aws_iam_role" "main_exec" {
          name = "${project.namePrefix}-main-exec"

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
        
        resource "aws_iam_policy" "bucketDynamoAndInvoke" {
          name        = "${project.namePrefix}-bucketDynamoAndInvoke"
          path = "/${project.namePrefixPath}/bucketDynamoAndInvoke/"
          description = "Access to the ${project.namePrefix} bucket, dynamo, and invoke"
          policy = jsonencode({
            Version = "2012-10-17"
            Statement = [
              {
                Action = [
                  "s3:GetObject",
                ]
                Effect   = "Allow"
                Resource = [
                    "${'$'}{aws_s3_bucket.lambda_bucket.arn}",
                    "${'$'}{aws_s3_bucket.lambda_bucket.arn}/*",
                ]
              },
              {
                Action = [
                  "dynamodb:*",
                ]
                Effect   = "Allow"
                Resource = ["*"]
              },
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
        
        resource "aws_iam_role_policy_attachment" "bucketDynamoAndInvoke" {
          role       = aws_iam_role.main_exec.name
          policy_arn = aws_iam_policy.bucketDynamoAndInvoke.arn
        }
        resource "aws_iam_role_policy_attachment" "main_policy_exec" {
          role       = aws_iam_role.main_exec.name
          policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
        }
        resource "aws_iam_role_policy_attachment" "main_policy_vpc" {
          role       = aws_iam_role.main_exec.name
          policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaVPCAccessExecutionRole"
        }
        resource "aws_iam_role_policy_attachment" "insights_policy" {
          role       = aws_iam_role.main_exec.id
          policy_arn = "arn:aws:iam::aws:policy/CloudWatchLambdaInsightsExecutionRolePolicy"
        }
        ${otherSections.flatMap { it.policies }.joinToString("\n") {
        """
        resource "aws_iam_role_policy_attachment" "$it" {
          role       = aws_iam_role.main_exec.id
          policy_arn = aws_iam_policy.$it.arn
        }
        """
        }}
        
        resource "aws_s3_object" "app_storage" {
          bucket = aws_s3_bucket.lambda_bucket.id

          key    = "lambda-functions.zip"
          source = data.archive_file.lambda.output_path
        
          source_hash = data.archive_file.lambda.output_md5
          depends_on = [data.archive_file.lambda]
        }
        
        resource "aws_lambda_function" "main" {
          function_name = "${project.namePrefix}-main"
          publish = var.lambda_snapstart

          s3_bucket = aws_s3_bucket.lambda_bucket.id
          s3_key    = aws_s3_object.app_storage.key

          runtime = "java17"
          handler = "$handlerFqn"
          
          memory_size = "${'$'}{var.lambda_memory_size}"
          timeout = var.lambda_timeout
          # memory_size = "1024"
        
          source_code_hash = data.archive_file.lambda.output_base64sha256
        
          role = aws_iam_role.main_exec.arn
          
          snap_start {
            apply_on = "PublishedVersions"
          }
  
          ${
            if (project.vpc)
                """
              |  vpc_config {
              |    subnet_ids = ${project.privateSubnets}
              |    security_group_ids = [aws_security_group.internal.id, aws_security_group.access_outside.id]
              |  }
              """.trimMargin()
            else
                ""
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
          function_version = var.lambda_snapstart ? aws_lambda_function.main.version : "${'$'}LATEST"
        }
        
        resource "aws_cloudwatch_log_group" "main" {
          name = "${project.namePrefix}-main-log"
          retention_in_days = 30
        }
        
        resource "local_sensitive_file" "settings_raw" {
          content = jsonencode({
            ${
            otherSections.mapNotNull { it.toLightningServer }.flatMap { it.entries }.map { "${it.key} = ${it.value}" }
                .map { it.replace("\n", "\n            ") }.joinToString("\n            ")
        }})
          filename = "${'$'}{path.module}/build/raw-settings.json"
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
            command = (local.is_windows ? "if(test-path \"${'$'}{path.module}/build/lambda/\") { rd -Recurse \"${'$'}{path.module}/build/lambda/\" }" : "rm -rf \"${'$'}{path.module}/build/lambda/\"")
            interpreter = local.is_windows ? ["PowerShell", "-Command"] : []
          }
          provisioner "local-exec" {
            command = (local.is_windows ? "cp -r -force \"${'$'}{path.module}/../../build/dist/lambda/.\" \"${'$'}{path.module}/build/lambda/\"" : "cp -rf \"${'$'}{path.module}/../../build/dist/lambda/.\" \"${'$'}{path.module}/build/lambda/\"")
            interpreter = local.is_windows ? ["PowerShell", "-Command"] : []
          }
          provisioner "local-exec" {
            command = "openssl enc -aes-256-cbc -md sha256 -in \"${'$'}{local_sensitive_file.settings_raw.filename}\" -out \"${'$'}{path.module}/build/lambda/settings.enc\" -pass pass:${'$'}{random_password.settings.result}"
            interpreter = local.is_windows ? ["PowerShell", "-Command"] : []
          }
        }
        resource "null_resource" "settings_reread" {
          triggers = {
            settingsRawHash = local_sensitive_file.settings_raw.content
          }
          depends_on = [null_resource.lambda_jar_source]
          provisioner "local-exec" {
            command     = "openssl enc -d -aes-256-cbc -md sha256 -out \"${'$'}{local_sensitive_file.settings_raw.filename}.decrypted.json\" -in \"${'$'}{path.module}/build/lambda/settings.enc\" -pass pass:${'$'}{random_password.settings.result}"
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
          source_dir = "${'$'}{path.module}/build/lambda"
          output_path = "${'$'}{path.module}/build/lambda.jar"
        }
        
        data "aws_caller_identity" "current" {}
        resource "aws_lambda_permission" "scheduled_tasks" {
          action        = "lambda:InvokeFunction"
          function_name = "${'$'}{aws_lambda_alias.main.function_name}:${'$'}{aws_lambda_alias.main.name}"
          principal     = "events.amazonaws.com"
          source_arn    = "arn:aws:events:${'$'}{var.deployment_location}:${'$'}{data.aws_caller_identity.current.account_id}:rule/${project.namePrefix}_*"
          lifecycle {
            create_before_destroy = ${project.createBeforeDestroy}
          }
        }
        
    """.trimIndent()
        )
    }
)

internal fun httpAwsHandler(projectInfo: TerraformProjectInfo) = TerraformSection(
    name = "http",
    emit = {
        appendLine(
            """
                resource "aws_apigatewayv2_api" "http" {
                  name = "${projectInfo.namePrefix}-http"
                  protocol_type = "HTTP"
                }
        
                resource "aws_apigatewayv2_stage" "http" {
                  api_id = aws_apigatewayv2_api.http.id
        
                  name = "${projectInfo.namePrefix}-gateway-stage"
                  auto_deploy = true
        
                  access_log_settings {
                    destination_arn = aws_cloudwatch_log_group.http_api.arn
        
                    format = jsonencode({
                      requestId               = "${'$'}context.requestId"
                      sourceIp                = "${'$'}context.identity.sourceIp"
                      requestTime             = "${'$'}context.requestTime"
                      protocol                = "${'$'}context.protocol"
                      httpMethod              = "${'$'}context.httpMethod"
                      resourcePath            = "${'$'}context.resourcePath"
                      routeKey                = "${'$'}context.routeKey"
                      status                  = "${'$'}context.status"
                      responseLength          = "${'$'}context.responseLength"
                      integrationErrorMessage = "${'$'}context.integrationErrorMessage"
                      }
                    )
                  }
                }
        
                resource "aws_apigatewayv2_integration" "http" {
                  api_id = aws_apigatewayv2_api.http.id
        
                  integration_uri    = aws_lambda_alias.main.invoke_arn
                  integration_type   = "AWS_PROXY"
                  integration_method = "POST"
                }
        
                resource "aws_cloudwatch_log_group" "http_api" {
                  name = "${projectInfo.namePrefix}-http-gateway-log"
        
                  retention_in_days = 30
                }
                
                resource "aws_apigatewayv2_route" "http" {
                    api_id = aws_apigatewayv2_api.http.id
                    route_key = "${'$'}default"
                    target    = "integrations/${'$'}{aws_apigatewayv2_integration.http.id}"
                }
        
                resource "aws_lambda_permission" "api_gateway_http" {
                  action        = "lambda:InvokeFunction"
                  function_name = "${'$'}{aws_lambda_alias.main.function_name}:${'$'}{aws_lambda_alias.main.name}"
                  principal     = "apigateway.amazonaws.com"
        
                  source_arn = "${'$'}{aws_apigatewayv2_api.http.execution_arn}/*/*"
                  lifecycle {
                    create_before_destroy = ${projectInfo.createBeforeDestroy}
                  }
                }
            """.trimIndent()
        )
        if (projectInfo.domain) {
            appendLine(
                """
                resource "aws_acm_certificate" "http" {
                  domain_name   = var.domain_name
                  validation_method = "DNS"
                }
                resource "aws_route53_record" "http" {
                  zone_id = data.aws_route53_zone.main.zone_id
                  name = tolist(aws_acm_certificate.http.domain_validation_options)[0].resource_record_name
                  type = tolist(aws_acm_certificate.http.domain_validation_options)[0].resource_record_type
                  records = [tolist(aws_acm_certificate.http.domain_validation_options)[0].resource_record_value]
                  ttl = "300"
                }
                resource "aws_acm_certificate_validation" "http" {
                  certificate_arn = aws_acm_certificate.http.arn
                  validation_record_fqdns = [aws_route53_record.http.fqdn]
                }
                resource aws_apigatewayv2_domain_name http {
                  domain_name = var.domain_name
                  domain_name_configuration {
                    certificate_arn = aws_acm_certificate.http.arn
                    endpoint_type   = "REGIONAL"
                    security_policy = "TLS_1_2"
                  }
                  depends_on = [aws_acm_certificate_validation.http]
                }
                resource aws_apigatewayv2_api_mapping http {
                  stage       = aws_apigatewayv2_stage.http.id
                  api_id      = aws_apigatewayv2_stage.http.api_id
                  domain_name = aws_apigatewayv2_domain_name.http.domain_name
                }
                resource aws_route53_record httpAccess {
                  type    = "A"
                  name    = aws_apigatewayv2_domain_name.http.domain_name
                  zone_id = data.aws_route53_zone.main.id
                    alias {
                      evaluate_target_health = false
                      name                   = aws_apigatewayv2_domain_name.http.domain_name_configuration[0].target_domain_name
                      zone_id                = aws_apigatewayv2_domain_name.http.domain_name_configuration[0].hosted_zone_id
                    }
                }
            """.trimIndent()
            )
        }
    },
    outputs = listOf(
        TerraformOutput("http_url", "aws_apigatewayv2_stage.http.invoke_url"),
        TerraformOutput(
            "http", """
            {
                id = aws_apigatewayv2_stage.http.id
                api_id = aws_apigatewayv2_stage.http.api_id
                invoke_url = aws_apigatewayv2_stage.http.invoke_url
                arn = aws_apigatewayv2_stage.http.arn
                name = aws_apigatewayv2_stage.http.name
            }
        """.trimIndent()
        ),
    )
)

internal fun wsAwsHandler(projectInfo: TerraformProjectInfo) = TerraformSection(
    name = "websockets",
    emit = {
        appendLine(
            """
            resource "aws_apigatewayv2_api" "ws" {
              name = "${projectInfo.namePrefix}-gateway"
              protocol_type = "WEBSOCKET"
              route_selection_expression = "constant"
            }
    
            resource "aws_apigatewayv2_stage" "ws" {
              api_id = aws_apigatewayv2_api.ws.id
    
              name = "${projectInfo.namePrefix}-gateway-stage"
              auto_deploy = true
    
              access_log_settings {
                destination_arn = aws_cloudwatch_log_group.ws_api.arn
    
                format = jsonencode({
                  requestId               = "${'$'}context.requestId"
                  sourceIp                = "${'$'}context.identity.sourceIp"
                  requestTime             = "${'$'}context.requestTime"
                  protocol                = "${'$'}context.protocol"
                  httpMethod              = "${'$'}context.httpMethod"
                  resourcePath            = "${'$'}context.resourcePath"
                  routeKey                = "${'$'}context.routeKey"
                  status                  = "${'$'}context.status"
                  responseLength          = "${'$'}context.responseLength"
                  integrationErrorMessage = "${'$'}context.integrationErrorMessage"
                  }
                )
              }
            }
    
            resource "aws_apigatewayv2_integration" "ws" {
              api_id = aws_apigatewayv2_api.ws.id
    
              integration_uri    = aws_lambda_alias.main.invoke_arn
              integration_type   = "AWS_PROXY"
              integration_method = "POST"
            }
    
            resource "aws_cloudwatch_log_group" "ws_api" {
              name = "${projectInfo.namePrefix}-ws-gateway-log"
    
              retention_in_days = 30
            }
            
            resource "aws_apigatewayv2_route" "ws_connect" {
                api_id = aws_apigatewayv2_api.ws.id
    
                route_key = "${'$'}connect"
                target    = "integrations/${'$'}{aws_apigatewayv2_integration.ws.id}"
            }
            resource "aws_apigatewayv2_route" "ws_default" {
                api_id = aws_apigatewayv2_api.ws.id
    
                route_key = "${'$'}default"
                target    = "integrations/${'$'}{aws_apigatewayv2_integration.ws.id}"
            }
            resource "aws_apigatewayv2_route" "ws_disconnect" {
                api_id = aws_apigatewayv2_api.ws.id
    
                route_key = "${'$'}disconnect"
                target    = "integrations/${'$'}{aws_apigatewayv2_integration.ws.id}"
            }
    
            resource "aws_lambda_permission" "api_gateway_ws" {
              action        = "lambda:InvokeFunction"
              function_name = "${'$'}{aws_lambda_alias.main.function_name}:${'$'}{aws_lambda_alias.main.name}"
              principal     = "apigateway.amazonaws.com"
    
              source_arn = "${'$'}{aws_apigatewayv2_api.ws.execution_arn}/*/*"
              lifecycle {
                create_before_destroy = ${projectInfo.createBeforeDestroy}
              }
            }
            
            resource "aws_iam_policy" "api_gateway_ws" {
              name        = "${projectInfo.namePrefix}-api_gateway_ws"
              path = "/${projectInfo.namePrefixPath}/api_gateway_ws/"
              description = "Access to the ${projectInfo.namePrefix}_api_gateway_ws management"
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
        """.trimIndent()
        )
        if (projectInfo.domain) {
            appendLine(
                """
                resource "aws_acm_certificate" "ws" {
                  domain_name   = "ws.${'$'}{var.domain_name}"
                  validation_method = "DNS"
                }
                resource "aws_route53_record" "ws" {
                  zone_id = data.aws_route53_zone.main.zone_id
                  name = tolist(aws_acm_certificate.ws.domain_validation_options)[0].resource_record_name
                  type = tolist(aws_acm_certificate.ws.domain_validation_options)[0].resource_record_type
                  records = [tolist(aws_acm_certificate.ws.domain_validation_options)[0].resource_record_value]
                  ttl = "300"
                }
                resource "aws_acm_certificate_validation" "ws" {
                  certificate_arn = aws_acm_certificate.ws.arn
                  validation_record_fqdns = [aws_route53_record.ws.fqdn]
                }
                resource aws_apigatewayv2_domain_name ws {
                  domain_name = "ws.${'$'}{var.domain_name}"
                  domain_name_configuration {
                    certificate_arn = aws_acm_certificate.ws.arn
                    endpoint_type   = "REGIONAL"
                    security_policy = "TLS_1_2"
                  }
                  depends_on = [aws_acm_certificate_validation.ws]
                }
                resource aws_apigatewayv2_api_mapping ws {
                  stage       = aws_apigatewayv2_stage.ws.id
                  api_id      = aws_apigatewayv2_stage.ws.api_id
                  domain_name = aws_apigatewayv2_domain_name.ws.domain_name
                }
                resource aws_route53_record wsAccess {
                  type    = "A"
                  name    = aws_apigatewayv2_domain_name.ws.domain_name
                  zone_id = data.aws_route53_zone.main.id
                    alias {
                      evaluate_target_health = false
                      name                   = aws_apigatewayv2_domain_name.ws.domain_name_configuration[0].target_domain_name
                      zone_id                = aws_apigatewayv2_domain_name.ws.domain_name_configuration[0].hosted_zone_id
                    }
                }
            """.trimIndent()
            )
        }
    },
    outputs = listOf(
        TerraformOutput("ws_url", "aws_apigatewayv2_stage.ws.invoke_url"),
        TerraformOutput(
            "ws", """
            {
                id = aws_apigatewayv2_stage.ws.id
                api_id = aws_apigatewayv2_stage.ws.api_id
                invoke_url = aws_apigatewayv2_stage.ws.invoke_url
                arn = aws_apigatewayv2_stage.ws.arn
                name = aws_apigatewayv2_stage.ws.name
            }
        """.trimIndent()
        ),
    )
)
