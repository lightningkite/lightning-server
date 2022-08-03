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
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.GeneralServerSettings
import com.lightningkite.lightningserver.settings.Settings
import com.lightningkite.lightningserver.websocket.WebSockets
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer



fun terraformAws(handler: String, projectName: String = "project", appendable: Appendable) {
    AwsAdapter.cache
    val d = "$"
    val namePrefix = "${projectName}-\${var.deployment_name}"
    val namePrefixSafe = "${projectName.filter { it.isLetterOrDigit() }}\${var.deployment_name}"
    val namePrefixPath = "${projectName}/\${var.deployment_name}"
    val dependencies = ArrayList<String>()
    val appSettings = ArrayList<String>()
    appendable.appendLine("""
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
        variable "basis_domain" {
        }
        variable "subdomain" {
          default = "api"
        }
        variable "debug" {
          default = false
        }
        
        # locals {
        #     subdomainPrefix = "${'$'}{var.deployment_name}.${'$'}{var.subdomain}" 
        #     subdomain = "${'$'}{var.deployment_name}.${'$'}{var.subdomain}.${'$'}{var.basis_domain}"
        # }
        # 
        # data "aws_route53_zone" "main" {
        #   name = var.basis_domain
        # }

        module "vpc" {
          source = "terraform-aws-modules/vpc/aws"
        
          name = "$namePrefix"
          cidr = "10.0.0.0/16"
        
          azs             = ["${'$'}{var.deployment_location}a", "${'$'}{var.deployment_location}b", "${'$'}{var.deployment_location}c"]
          private_subnets = ["10.0.1.0/24", "10.0.2.0/24", "10.0.3.0/24"]
          public_subnets  = ["10.0.101.0/24", "10.0.102.0/24", "10.0.103.0/24"]
        
          enable_nat_gateway = false
          enable_vpn_gateway = false
        }
        
        resource "aws_vpc_endpoint" "s3" {
          vpc_id = "${'$'}{module.vpc.vpc_id}"
          service_name = "com.amazonaws.${'$'}{var.deployment_location}.s3"
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
        
        resource "aws_s3_bucket" "lambda_bucket" {
          bucket_prefix = "${namePrefix}-lambda-bucket"
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

    """.trimIndent())
    dependencies.add("aws_s3_object.app_storage")
    for(setting in Settings.requirements) {
        when(setting.value.serializer) {
            serializer<GeneralServerSettings>() -> {
                appSettings.add("""${setting.key} = {
                    projectName = "$projectName"
                    publicUrl = aws_apigatewayv2_stage.http.invoke_url
                    wsUrl = aws_apigatewayv2_stage.ws.invoke_url
                    debug = var.debug
                }""".trimIndent())
            }
            serializer<FilesSettings>() -> {
                appendable.appendLine("""
                    
                    ####
                    # ${setting.key}: FilesSettings
                    ####
                    
                    variable "${setting.key}_expiry" {
                        default = "P1D"
                    }
        
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
                appendable.appendLine("""
                    
                    ####
                    # ${setting.key}: DatabaseSettings
                    ####
                    
                    variable "${setting.key}_expiry" {
                        default = "P1D"
                    }
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
                appendable.appendLine("""

                    ####
                    # ${setting.key}: CacheSettings
                    ####

                    variable "${setting.key}_node_type" {
                      default = "cache.t2.micro"
                    }
                    variable "${setting.key}_node_count" {
                      default = 1
                    }

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
                    url = "memcached://${'$'}{aws_elasticache_cluster.${setting.key}.cluster_address}:11211"
                }""".trimIndent())
            }
            serializer<JwtSigner>() -> {
                appendable.appendLine("""
                    
                    ####
                    # ${setting.key}: JwtSigner
                    ####
                    
                    variable "${setting.key}_expirationMilliseconds" {
                      default = 31540000000
                    }
                    variable "${setting.key}_emailExpirationMilliseconds" {
                      default = 1800000
                    }
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
                appendable.appendLine("""
                    
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
                    variable "${setting.key}_sender" {
                    }
                    resource "aws_ses_email_identity" "${setting.key}" {
                      email = var.${setting.key}_sender
                    }
                    
                """.trimIndent())
                appSettings.add("""${setting.key} = {
                    option = "Smtp" 
                    smtp = {
                        hostName = "email-smtp.us-west-2.amazonaws.com"
                        port = 587
                        username = aws_iam_access_key.${setting.key}.id
                        password = aws_iam_access_key.${setting.key}.ses_smtp_password_v4
                        useSSL = true
                        fromEmail = aws_ses_email_identity.${setting.key}.email
                    }
                }""".trimIndent())
            }
//            serializer<NotificationSettings>() ->{}
            else -> {
                appendable.appendLine("""
                    
                    ####
                    # ${setting.key}
                    ####
                    
                    variable "${setting.key}" {
                      default = ${setting.value.let { Serialization.json.encodeToString(it.serializer as KSerializer<Any?>, it.default) }}
                    }
                """.trimIndent())
                appSettings.add("""${setting.key} = var.${setting.key}""".trimIndent())
            }
        }
    }

    // Now we create the outputs.

    appendable.appendLine("""
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

          source_code_hash = filesha256(local.lambda_source)

          role = aws_iam_role.main_exec.arn
          depends_on = [${dependencies.joinToString()}]
          
          vpc_config {
            subnet_ids = module.vpc.public_subnets
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

    if(Http.endpoints.isNotEmpty()) {
        appendable.appendLine("""
        
        ####
        # ApiGateway for Http
        ####
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
        
        """.trimIndent())
    }

    if(WebSockets.handlers.isNotEmpty()) {
        appendable.appendLine("""
                    
        
        ####
        # ApiGateway for Websockets
        ####
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
        """.trimIndent())
    }
}

private val HttpEndpoint.terraformName: String get() =
    path.terraformName
    .plus('_')
    .plus(method.toString())

private val ServerPath.terraformName: String get() = toString()
    .replace('/', '_')
    .filter { it.isLetterOrDigit() || it == '_' }