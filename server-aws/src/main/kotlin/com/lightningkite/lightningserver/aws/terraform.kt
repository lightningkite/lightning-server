package com.lightningkite.lightningserver.aws

import com.lightningkite.lightningserver.auth.JwtSigner
import com.lightningkite.lightningserver.cache.CacheSettings
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.db.DatabaseSettings
import com.lightningkite.lightningserver.email.EmailSettings
import com.lightningkite.lightningserver.files.FilesSettings
import com.lightningkite.lightningserver.http.Http
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.notifications.NotificationSettings
import com.lightningkite.lightningserver.schedule.Schedule
import com.lightningkite.lightningserver.schedule.Scheduler
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.GeneralServerSettings
import com.lightningkite.lightningserver.settings.Settings
import com.lightningkite.lightningserver.websocket.WebSockets
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import java.io.File
import java.time.LocalDate
import java.time.ZonedDateTime

fun terraformAws(handler: String, projectName: String = "project", root: File) {
    AwsAdapter.cache
    val namePrefix = "${projectName}-\${var.deployment_name}"
    val namePrefixSafe = "${projectName.filter { it.isLetterOrDigit() }}\${var.deployment_name}"
    val namePrefixPath = "${projectName}/\${var.deployment_name}"
    val dependencies = ArrayList<String>()
    val appSettings = ArrayList<String>()
//    root.resolve("base/main.tf").apply { parentFile!!.mkdirs() }
    val variables = StringBuilder()
    val main = StringBuilder()
    val outputs = StringBuilder()
    val domain = StringBuilder()
    val domainInputs = StringBuilder()
    val noDomain = StringBuilder()
    val noDomainInputs = StringBuilder()
    variables.appendLine("""
        variable "deployment_location" {
          default = "us-west-2"
        }
        variable "deployment_name" {
          default = "no-deployment-name"
        }
        variable "debug" {
          default = false
        }
    """.trimIndent())
    main.appendLine("""
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

        module "vpc" {
          source = "terraform-aws-modules/vpc/aws"
        
          name = "$namePrefix"
          cidr = "10.0.0.0/16"
        
          azs             = ["${'$'}{var.deployment_location}a", "${'$'}{var.deployment_location}b", "${'$'}{var.deployment_location}c"]
          private_subnets = ["10.0.1.0/24", "10.0.2.0/24", "10.0.3.0/24"]
          public_subnets  = ["10.0.101.0/24", "10.0.102.0/24", "10.0.103.0/24"]
        
          enable_nat_gateway = true
          enable_vpn_gateway = false
        }
        
        resource "aws_vpc_endpoint" "s3" {
          vpc_id = module.vpc.vpc_id
          service_name = "com.amazonaws.${'$'}{var.deployment_location}.s3"
          route_table_ids = module.vpc.public_route_table_ids
        }
        resource "aws_vpc_endpoint" "executeapi" {
          vpc_id = module.vpc.vpc_id
          service_name = "com.amazonaws.${'$'}{var.deployment_location}.execute-api"
          security_group_ids = [aws_security_group.executeapi.id]
          vpc_endpoint_type = "Interface"
        }
        resource "aws_vpc_endpoint" "lambdainvoke" {
          vpc_id = module.vpc.vpc_id
          service_name = "com.amazonaws.${'$'}{var.deployment_location}.lambda"
          security_group_ids = [aws_security_group.lambdainvoke.id]
          vpc_endpoint_type = "Interface"
        }

        resource "aws_api_gateway_account" "main" {
          cloudwatch_role_arn = aws_iam_role.cloudwatch.arn
        }
        
        resource "aws_iam_role" "cloudwatch" {
          name = "api_gateway_cloudwatch_global_${'$'}{var.deployment_location}"
        
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
          name   = "$namePrefix-private"
          vpc_id = "${'$'}{module.vpc.vpc_id}"
        
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
          name   = "$namePrefix-access-outside"
          vpc_id = "${'$'}{module.vpc.vpc_id}"
        
          egress {
            from_port   = 0
            to_port     = 0
            protocol    = "-1"
            cidr_blocks     = ["0.0.0.0/0"]
          }
        }

        resource "aws_security_group" "executeapi" {
          name   = "$namePrefix-execute-api"
          vpc_id = "${'$'}{module.vpc.vpc_id}"
        
          ingress {
            from_port   = 443
            to_port     = 443
            protocol    = "tcp"
            cidr_blocks = [module.vpc.vpc_cidr_block]
          }
        }

        resource "aws_security_group" "lambdainvoke" {
          name   = "$namePrefix-lambda-invoke"
          vpc_id = "${'$'}{module.vpc.vpc_id}"
        
          ingress {
            from_port   = 443
            to_port     = 443
            protocol    = "tcp"
            cidr_blocks = [module.vpc.vpc_cidr_block]
          }
        }
        
        resource "aws_s3_bucket" "lambda_bucket" {
          bucket_prefix = "${namePrefix}-lambda-bucket"
          force_destroy = true
        }
        resource "aws_s3_bucket_acl" "lambda_bucket" {
          bucket = aws_s3_bucket.lambda_bucket.id
          acl    = "private"
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

        resource "aws_iam_role" "main_exec" {
          name = "${namePrefix}-main-exec"

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
          name        = "${namePrefix}-lambdainvoke"
          path = "/${namePrefixPath}/lambdainvoke/"
          description = "Access to the ${namePrefix}_lambdainvoke bucket"
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

    """.trimIndent())
    domain.appendLine("""
        variable "debug" {
          default = true
        }
        variable "domain_name_zone" {
        }
        variable "domain_name" {
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

        data "aws_route53_zone" "main" {
          name = var.domain_name_zone
        }
        provider "aws" {
          alias = "acm"
          region = "us-east-1"
        }
    """.trimIndent())
    noDomain.appendLine("""
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

    """.trimIndent())
    dependencies.add("aws_s3_object.app_storage")
    for(setting in Settings.requirements) {
        when(setting.value.serializer) {
            serializer<GeneralServerSettings>() -> {
                variables.appendLine("""
                    variable "cors" {
                        default = null
                    }
                """.trimIndent())
                noDomain.appendLine("""
                    variable "cors" {
                        default = null
                    }
                """.trimIndent())
                domain.appendLine("""
                    variable "cors" {
                        default = null
                    }
                """.trimIndent())
                noDomainInputs.appendLine("""  cors  = var.cors""")
                domainInputs.appendLine("""  cors  = var.cors""")
                appSettings.add("""${setting.key} = {
                    projectName = "$projectName"
                    publicUrl = var.public_http_url == null ? aws_apigatewayv2_stage.http.invoke_url : var.public_http_url
                    wsUrl = var.public_ws_url == null ? aws_apigatewayv2_stage.ws.invoke_url : var.public_ws_url
                    debug = var.debug
                    cors = var.cors
                }""".trimIndent())
            }
            serializer<FilesSettings>() -> {
                variables.appendLine("""
                    variable "${setting.key}_expiry" {
                        default = "P1D"
                    }
                """.trimIndent())
                main.appendLine("""
                    
                    ####
                    # ${setting.key}: FilesSettings
                    ####
        
                    resource "aws_s3_bucket" "${setting.key}" {
                      bucket_prefix = "${namePrefix}-${setting.key}"
                    }
                    resource "aws_s3_bucket_cors_configuration" "${setting.key}" {
                      bucket = aws_s3_bucket.${setting.key}.bucket
                    
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
                    resource "aws_s3_bucket_acl" "${setting.key}" {
                      bucket = aws_s3_bucket.${setting.key}.id
                      acl    = "private"
                    }
                    resource "aws_iam_policy" "${setting.key}" {
                      name        = "${namePrefix}-${setting.key}"
                      path = "/${namePrefixPath}/${setting.key}/"
                      description = "Access to the ${namePrefix}_${setting.key} bucket"
                      policy = jsonencode({
                        Version = "2012-10-17"
                        Statement = [
                          {
                            Action = [
                              "s3:*",
                            ]
                            Effect   = "Allow"
                            Resource = [
                                "${'$'}{aws_s3_bucket.${setting.key}.arn}",
                                "${'$'}{aws_s3_bucket.${setting.key}.arn}/*",
                            ]
                          },
                        ]
                      })
                    }
                    resource "aws_iam_role_policy_attachment" "${setting.key}" {
                      role       = aws_iam_role.main_exec.name
                      policy_arn = aws_iam_policy.${setting.key}.arn
                    }
                """.trimIndent())
                appSettings.add("""${setting.key} = {
                    storageUrl = "s3://${'$'}{aws_s3_bucket.${setting.key}.id}.s3-${'$'}{aws_s3_bucket.${setting.key}.region}.amazonaws.com"
                    signedUrlExpiration = var.${setting.key}_expiry
                }""".trimIndent())
            }
            serializer<DatabaseSettings>() -> {
                variables.appendLine("""
                    variable "${setting.key}_expiry" {
                        default = "P1D"
                    }
                """.trimIndent())
                main.appendLine("""
                    
                    ####
                    # ${setting.key}: DatabaseSettings
                    ####
                    resource "random_password" "${setting.key}" {
                      length           = 32
                      special          = true
                      override_special = "-_"
                    }
                    resource "aws_docdb_subnet_group" "${setting.key}" {
                      name       = "$namePrefix-${setting.key}"
                      subnet_ids = module.vpc.private_subnets
                    }
                    resource "aws_docdb_cluster_parameter_group" "${setting.key}" {
                      family = "docdb4.0"
                      name = "$namePrefix-${setting.key}-parameter-group"
                      parameter {
                        name  = "tls"
                        value = "disabled"
                      }
                    }
                    resource "aws_docdb_cluster" "${setting.key}" {
                      cluster_identifier = "${namePrefix}-${setting.key}"
                      engine = "docdb"
                      master_username = "master"
                      master_password = random_password.${setting.key}.result
                      backup_retention_period = 5
                      preferred_backup_window = "07:00-09:00"
                      skip_final_snapshot = true

                      db_cluster_parameter_group_name = "${'$'}{aws_docdb_cluster_parameter_group.${setting.key}.name}"
                      vpc_security_group_ids = [aws_security_group.internal.id]
                      db_subnet_group_name    = "${'$'}{aws_docdb_subnet_group.${setting.key}.name}"
                    }
                    resource "aws_docdb_cluster_instance" "${setting.key}" {
                      count              = 1
                      identifier         = "$namePrefix-${setting.key}-${'$'}{count.index}"
                      cluster_identifier = "${'$'}{aws_docdb_cluster.${setting.key}.id}"
                      instance_class     = "db.t4g.medium"
                    }
                """.trimIndent())
                //TODO: use config endpoint
                appSettings.add("""${setting.key} = {
                    url = "mongodb://master:${'$'}{random_password.${setting.key}.result}@${'$'}{aws_docdb_cluster_instance.${setting.key}[0].endpoint}/?retryWrites=false"
                    databaseName = "${namePrefix}_${setting.key}"
                }""".trimIndent())
            }
            serializer<CacheSettings>() -> {
                variables.appendLine("""
                    variable "${setting.key}_node_type" {
                      default = "cache.t2.micro"
                    }
                    variable "${setting.key}_node_count" {
                      default = 1
                    }
                """.trimIndent())
                main.appendLine("""

                    ####
                    # ${setting.key}: CacheSettings
                    ####

                    resource "aws_elasticache_cluster" "${setting.key}" {
                      cluster_id           = "${namePrefix}-${setting.key}"
                      engine               = "memcached"
                      node_type            = var.${setting.key}_node_type
                      num_cache_nodes      = var.${setting.key}_node_count
                      parameter_group_name = "default.memcached1.6"
                      port                 = 11211
                      security_group_ids   = [aws_security_group.internal.id]
                      subnet_group_name    = aws_elasticache_subnet_group.${setting.key}.name
                    }
                    resource "aws_elasticache_subnet_group" "${setting.key}" {
                      name       = "$namePrefix-${setting.key}"
                      subnet_ids = module.vpc.private_subnets
                    }
                """.trimIndent())
                appSettings.add("""${setting.key} = {
                    url = "memcached-aws://${'$'}{aws_elasticache_cluster.${setting.key}.cluster_address}:11211"
                }""".trimIndent())
            }
            serializer<JwtSigner>() -> {
                variables.appendLine("""
                    variable "${setting.key}_expirationMilliseconds" {
                      default = 31540000000
                    }
                    variable "${setting.key}_emailExpirationMilliseconds" {
                      default = 1800000
                    }
                """.trimIndent())
                main.appendLine("""
                    
                    ####
                    # ${setting.key}: JwtSigner
                    ####
                    resource "random_password" "${setting.key}" {
                      length           = 32
                      special          = true
                      override_special = "!#${'$'}%&*()-_=+[]{}<>:?"
                    }
                """.trimIndent())
                appSettings.add("""${setting.key} = {
                    expirationMilliseconds = var.${setting.key}_expirationMilliseconds 
                    emailExpirationMilliseconds = var.${setting.key}_emailExpirationMilliseconds 
                    secret = random_password.${setting.key}.result
                }""".trimIndent())
            }
            serializer<EmailSettings>() -> {
                variables.appendLine("""
                    variable "${setting.key}_sender" {
                    }
                """.trimIndent())
                main.appendLine("""
                    
                    ####
                    # ${setting.key}: EmailSettings
                    ####
                    
                    resource "aws_iam_user" "${setting.key}" {
                      name = "${namePrefix}-${setting.key}-user"
                    }

                    resource "aws_iam_access_key" "${setting.key}" {
                      user = aws_iam_user.${setting.key}.name
                    }

                    data "aws_iam_policy_document" "${setting.key}" {
                      statement {
                        actions   = ["ses:SendRawEmail"]
                        resources = ["*"]
                      }
                    }

                    resource "aws_iam_policy" "${setting.key}" {
                      name = "${namePrefix}-${setting.key}-policy"
                      description = "Allows sending of e-mails via Simple Email Service"
                      policy      = data.aws_iam_policy_document.${setting.key}.json
                    }

                    resource "aws_iam_user_policy_attachment" "${setting.key}" {
                      user       = aws_iam_user.${setting.key}.name
                      policy_arn = aws_iam_policy.${setting.key}.arn
                    }
                    
                    resource "aws_security_group" "${setting.key}" {
                      name   = "demo-${'$'}{var.deployment_name}-${setting.key}"
                      vpc_id = module.vpc.vpc_id
                    
                      ingress {
                        from_port   = 587
                        to_port     = 587
                        protocol    = "tcp"
                        cidr_blocks = [module.vpc.vpc_cidr_block]
                      }
                    }
                    resource "aws_vpc_endpoint" "${setting.key}" {
                      vpc_id = module.vpc.vpc_id
                      service_name = "com.amazonaws.${'$'}{var.deployment_location}.email-smtp"
                      security_group_ids = [aws_security_group.${setting.key}.id]
                      vpc_endpoint_type = "Interface"
                    }
                    
                """.trimIndent())
                appSettings.add("""${setting.key} = {
                    url = "smtp://${'$'}{aws_iam_access_key.${setting.key}.id}:${'$'}{aws_iam_access_key.${setting.key}.ses_smtp_password_v4}@email-smtp.us-west-2.amazonaws.com:587" 
                    fromEmail = var.${setting.key}_sender
                }""".trimIndent())
                noDomain.appendLine("""
                    variable "${setting.key}_sender" {
                    }
                    resource "aws_ses_email_identity" "${setting.key}" {
                      email = var.${setting.key}_sender
                    }
                """.trimIndent())
                noDomainInputs.appendLine("""  ${setting.key}_sender  = var.${setting.key}_sender""")
                domain.appendLine("""
                    
                    resource "aws_ses_domain_identity" "${setting.key}" {
                      domain = var.domain_name
                    }
                    resource "aws_route53_record" "${setting.key}" {
                      zone_id = data.aws_route53_zone.main.zone_id
                      name    = "_amazonses.${'$'}{var.domain_name}"
                      type    = "TXT"
                      ttl     = "600"
                      records = [aws_ses_domain_identity.${setting.key}.verification_token]
                    }
                    
                """.trimIndent())
                domainInputs.appendLine("""  ${setting.key}_sender  = "noreply@${'$'}{var.domain_name}"""")
            }
//            serializer<NotificationSettings>() ->{}
            else -> {
                variables.appendLine("""
                    variable "${setting.key}" {
                      default = ${setting.value.let { Serialization.json.encodeToString(it.serializer as KSerializer<Any?>, it.default) }}
                    }
                """.trimIndent())
                domain.appendLine("""
                    variable "${setting.key}" {
                      default = ${setting.value.let { Serialization.json.encodeToString(it.serializer as KSerializer<Any?>, it.default) }}
                    }
                """.trimIndent())
                noDomain.appendLine("""
                    variable "${setting.key}" {
                      default = ${setting.value.let { Serialization.json.encodeToString(it.serializer as KSerializer<Any?>, it.default) }}
                    }
                """.trimIndent())
                domainInputs.appendLine("""  ${setting.key}  = var.${setting.key}""")
                noDomainInputs.appendLine("""  ${setting.key}  = var.${setting.key}""")
                appSettings.add("""${setting.key} = var.${setting.key}""".trimIndent())
            }
        }
    }

    if(Scheduler.schedules.isNotEmpty()) {
        main.appendLine("""
        """.trimIndent())
    }
    Scheduler.schedules.values.forEach {
        when(val s = it.schedule) {
            is Schedule.Daily -> {
                val utcTime = ZonedDateTime.of(LocalDate.now(), s.time, s.zone)
                main.appendLine("""
                    resource "aws_cloudwatch_event_rule" "scheduled_task_${it.name}" {
                      name                = "${namePrefix}_${it.name.filter { it.isLetterOrDigit() || it == '_' }}"
                      schedule_expression = "cron(${utcTime.minute} ${utcTime.hour} * * ? *)"
                    }
                    resource "aws_cloudwatch_event_target" "scheduled_task_${it.name}" {
                      rule      = aws_cloudwatch_event_rule.scheduled_task_${it.name}.name
                      target_id = "lambda"
                      arn       = aws_lambda_function.main.arn
                      input     = "{\"scheduled\": \"${it.name}\"}"
                    }
                    resource "aws_lambda_permission" "scheduled_task_${it.name}" {
                      statement_id  = "AllowExecutionFromCloudWatch"
                      action        = "lambda:InvokeFunction"
                      function_name = aws_lambda_function.main.function_name
                      principal     = "events.amazonaws.com"
                      source_arn    = aws_cloudwatch_event_rule.scheduled_task_${it.name}.arn
                    }
                """.trimIndent())
            }
            is Schedule.Frequency -> {
                main.appendLine("""
                    resource "aws_cloudwatch_event_rule" "scheduled_task_${it.name}" {
                      name                = "${namePrefix}_${it.name.filter { it.isLetterOrDigit() || it == '_' }}"
                      schedule_expression = "rate(${s.gap.toMinutes()} minute${if(s.gap.toMinutes() > 1) "s" else ""})"
                    }
                    resource "aws_cloudwatch_event_target" "scheduled_task_${it.name}" {
                      rule      = aws_cloudwatch_event_rule.scheduled_task_${it.name}.name
                      target_id = "lambda"
                      arn       = aws_lambda_function.main.arn
                      input     = "{\"scheduled\": \"${it.name}\"}"
                    }
                    resource "aws_lambda_permission" "scheduled_task_${it.name}" {
                      statement_id  = "scheduled_task_${it.name}"
                      action        = "lambda:InvokeFunction"
                      function_name = aws_lambda_function.main.function_name
                      principal     = "events.amazonaws.com"
                      source_arn    = aws_cloudwatch_event_rule.scheduled_task_${it.name}.arn
                    }
                """.trimIndent())
            }
        }
    }

    // Now we create the outputs.

    main.appendLine("""
        ####
        # App Declaration
        ####
        
        resource "aws_lambda_function" "main" {
          function_name = "${namePrefix}-main"

          s3_bucket = aws_s3_bucket.lambda_bucket.id
          s3_key    = aws_s3_object.app_storage.key

          runtime = "java11"
          handler = "$handler"
          
          memory_size = "2048"
          timeout = 30
          # memory_size = "1024"

          source_code_hash = filebase64sha256(local.lambda_source)

          role = aws_iam_role.main_exec.arn
          depends_on = [${dependencies.joinToString()}]
          
          vpc_config {
            subnet_ids = module.vpc.private_subnets
            security_group_ids = [aws_security_group.internal.id, aws_security_group.access_outside.id]
          }
          
          environment {
            variables = {
              LIGHTNING_SERVER_SETTINGS = jsonencode({
                ${appSettings.joinToString(",\n                ")}
              })
            }
          }
        }

        resource "aws_cloudwatch_log_group" "main" {
          name = "${namePrefix}-main-log"

          retention_in_days = 30
        }
    """.trimIndent())

    // HTTP
    run {
        main.appendLine("""
        
        ####
        # ApiGateway for Http
        ####
        variable "public_http_url" {
          default = null
        }
        resource "aws_apigatewayv2_api" "http" {
          name = "${namePrefix}-http"
          protocol_type = "HTTP"
        }

        resource "aws_apigatewayv2_stage" "http" {
          api_id = aws_apigatewayv2_api.http.id

          name = "${namePrefix}-gateway-stage"
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

          integration_uri    = aws_lambda_function.main.invoke_arn
          integration_type   = "AWS_PROXY"
          integration_method = "POST"
        }

        resource "aws_cloudwatch_log_group" "http_api" {
          name = "${namePrefix}-http-gateway-log"

          retention_in_days = 30
        }
        
        resource "aws_apigatewayv2_route" "http" {
            api_id = aws_apigatewayv2_api.http.id
            route_key = "${'$'}default"
            target    = "integrations/${'$'}{aws_apigatewayv2_integration.http.id}"
        }

        resource "aws_lambda_permission" "api_gateway_http" {
          statement_id  = "AllowExecutionFromAPIGatewayHTTP"
          action        = "lambda:InvokeFunction"
          function_name = aws_lambda_function.main.function_name
          principal     = "apigateway.amazonaws.com"

          source_arn = "${'$'}{aws_apigatewayv2_api.http.execution_arn}/*/*"
        }
        
        """.trimIndent())

        outputs.appendLine("""
            output "http_url" {
              value = aws_apigatewayv2_stage.http.invoke_url
            }
            output "http" {
              value = {
                id = aws_apigatewayv2_stage.http.id
                api_id = aws_apigatewayv2_stage.http.api_id
                invoke_url = aws_apigatewayv2_stage.http.invoke_url
                arn = aws_apigatewayv2_stage.http.arn
                name = aws_apigatewayv2_stage.http.name
              }
            }
        """.trimIndent())
        
        domain.appendLine("""
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
              api_id      = module.Base.http.api_id
              domain_name = aws_apigatewayv2_domain_name.http.domain_name
              stage       = module.Base.http.id
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
        """.trimIndent())
    }

    // Websockets
    run {
        main.appendLine("""
                    
        
        ####
        # ApiGateway for Websockets
        ####
        variable "public_ws_url" {
          default = null
        }

        resource "aws_apigatewayv2_api" "ws" {
          name = "${namePrefix}-gateway"
          protocol_type = "WEBSOCKET"
          route_selection_expression = "constant"
        }

        resource "aws_apigatewayv2_stage" "ws" {
          api_id = aws_apigatewayv2_api.ws.id

          name = "${namePrefix}-gateway-stage"
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

          integration_uri    = aws_lambda_function.main.invoke_arn
          integration_type   = "AWS_PROXY"
          integration_method = "POST"
        }

        resource "aws_cloudwatch_log_group" "ws_api" {
          name = "${namePrefix}-ws-gateway-log"

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
          statement_id  = "AllowExecutionFromAPIGatewayWS"
          action        = "lambda:InvokeFunction"
          function_name = aws_lambda_function.main.function_name
          principal     = "apigateway.amazonaws.com"

          source_arn = "${'$'}{aws_apigatewayv2_api.ws.execution_arn}/*/*"
        }
        
        resource "aws_iam_policy" "api_gateway_ws" {
          name        = "${namePrefix}-api_gateway_ws"
          path = "/${namePrefixPath}/api_gateway_ws/"
          description = "Access to the ${namePrefix}_api_gateway_ws management"
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
        
        """.trimIndent())

        outputs.appendLine("""
            output "ws_url" {
              value = aws_apigatewayv2_stage.ws.invoke_url
            }
            output "ws" {
              value = {
                id = aws_apigatewayv2_stage.ws.id
                api_id = aws_apigatewayv2_stage.ws.api_id
                invoke_url = aws_apigatewayv2_stage.ws.invoke_url
                arn = aws_apigatewayv2_stage.ws.arn
                name = aws_apigatewayv2_stage.ws.name
              }
            }
        """.trimIndent())
        
        domain.appendLine("""
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
              api_id      = module.Base.ws.api_id
              domain_name = aws_apigatewayv2_domain_name.ws.domain_name
              stage       = module.Base.ws.id
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
        """.trimIndent())
    }

    domain.appendLine("""
        module "Base" {
          source              = "../base"
          deployment_location = var.deployment_location
          deployment_name     = var.deployment_name
          debug               = var.debug
          public_http_url     = var.domain_name
          public_ws_url       = "ws.${'$'}{var.domain_name}"
          ${domainInputs}
        }
    """.trimIndent())
    noDomain.appendLine("""
        module "Base" {
          source              = "../base"
          deployment_location = var.deployment_location
          deployment_name     = var.deployment_name
          debug               = var.debug
          ${noDomainInputs}
        }
    """.trimIndent())

    root.resolve("base/main.tf").apply { parentFile!!.mkdirs() }.writeText(main.toString())
    root.resolve("base/variables.tf").apply { parentFile!!.mkdirs() }.writeText(variables.toString())
    root.resolve("base/outputs.tf").apply { parentFile!!.mkdirs() }.writeText(outputs.toString())

    root.resolve("nodomain/main.tf").apply { parentFile!!.mkdirs() }.writeText(noDomain.toString())
    root.resolve("domain/main.tf").apply { parentFile!!.mkdirs() }.writeText(domain.toString())

    root.resolve("example/main.tf").takeUnless { it.exists() }?.apply { parentFile!!.mkdirs() }?.writeText("""
        module "Base" {
          source      = "../base"
          deployment_location = "us-west-2"
          deployment_name = "example"
          debug = true
          email_sender = "example@example.com"
        }
        terraform {
          backend "s3" {
            bucket = "${projectName.filter { it.isLetterOrDigit() }}"
            key    = "example"
            region = "us-west-2"
          }
        }
    """.trimIndent())
}

private val HttpEndpoint.terraformName: String get() =
    path.terraformName
    .plus('_')
    .plus(method.toString())

private val ServerPath.terraformName: String get() = toString()
    .replace('/', '_')
    .filter { it.isLetterOrDigit() || it == '_' }