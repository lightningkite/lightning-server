package com.lightningkite.lightningserver.aws.terraform

import com.lightningkite.lightningserver.auth.JwtSigner
import com.lightningkite.lightningserver.cache.CacheSettings
import com.lightningkite.lightningserver.db.DatabaseSettings
import com.lightningkite.lightningserver.email.EmailSettings
import com.lightningkite.lightningserver.encryption.SecretBasis
import com.lightningkite.lightningserver.files.FilesSettings
import com.lightningkite.lightningserver.metrics.MetricSettings
import com.lightningkite.lightningserver.metrics.MetricType
import com.lightningkite.lightningserver.schedule.Schedule
import com.lightningkite.lightningserver.schedule.Scheduler
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.GeneralServerSettings
import com.lightningkite.lightningserver.settings.Settings
import kotlinx.datetime.*
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import java.io.File
import java.util.*


internal fun defaultAwsHandler(project: TerraformProjectInfo) = with(project) {
    TerraformSection(
        name = "cloud",
        inputs = listOf(
            TerraformInput.string(
                "deployment_location",
                "us-west-2",
                description = "The AWS region key to deploy all resources in."
            ),
            TerraformInput.boolean(
                "debug",
                false,
                description = "The GeneralSettings debug. Debug true will turn on various things during run time for easier development and bug tracking. Should be false for production environments."
            ),
            TerraformInput.string("ip_prefix", "10.0"),
        ) + (if (domain) listOf(
            TerraformInput.string(
                "domain_name_zone",
                null,
                description = "The AWS Hosted zone the domain will be placed under."
            ),
            TerraformInput.string("domain_name", null, description = "The domain the server will be hosted at.")
        ) else listOf()) + (if (vpc && existingVpc) listOf(
            TerraformInput.string(
                "vpc_id",
                null,
                description = "The AWS VPC id that you want your resources to be placed under."
            ),
            TerraformInput.stringList("vpc_private_subnets", null),
            TerraformInput.stringList("vpc_nat_gateways", null),
        ) else listOf()),
        emit = {
            if (vpc) {
                if (existingVpc) {
                    appendLine(
                        """   
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
                    """.trimIndent()
                    )
                } else {
                    appendLine(
                        """   
                    module "vpc" {
                      source = "terraform-aws-modules/vpc/aws"
                      version = "4.0.2"
                    
                      name = "$namePrefix"
                      cidr = "${'$'}{var.ip_prefix}.0.0/16"
                    
                      azs             = ["${'$'}{var.deployment_location}a", "${'$'}{var.deployment_location}b", "${'$'}{var.deployment_location}c"]
                      private_subnets = ["${'$'}{var.ip_prefix}.1.0/24", "${'$'}{var.ip_prefix}.2.0/24", "${'$'}{var.ip_prefix}.3.0/24"]
                      public_subnets  = ["${'$'}{var.ip_prefix}.101.0/24", "${'$'}{var.ip_prefix}.102.0/24", "${'$'}{var.ip_prefix}.103.0/24"]
                    
                      enable_nat_gateway = true
                      single_nat_gateway = true
                      enable_vpn_gateway = false
                      enable_dns_hostnames = false
                      enable_dns_support   = true
                    }
                    """.trimIndent()
                    )
                }
                appendLine(
                    """
                    
                    resource "aws_vpc_endpoint" "s3" {
                      vpc_id = ${project.vpc_id}
                      service_name = "com.amazonaws.${'$'}{var.deployment_location}.s3"
                      route_table_ids = ${project.public_route_table_ids}
                    }
                    resource "aws_vpc_endpoint" "executeapi" {
                      vpc_id = ${project.vpc_id}
                      service_name = "com.amazonaws.${'$'}{var.deployment_location}.execute-api"
                      security_group_ids = [aws_security_group.executeapi.id]
                      vpc_endpoint_type = "Interface"
                    }
                    resource "aws_vpc_endpoint" "lambdainvoke" {
                      vpc_id = ${project.vpc_id}
                      service_name = "com.amazonaws.${'$'}{var.deployment_location}.lambda"
                      security_group_ids = [aws_security_group.lambdainvoke.id]
                      vpc_endpoint_type = "Interface"
                    }
        
                    resource "aws_security_group" "internal" {
                      name   = "$namePrefix-private"
                      vpc_id = ${project.vpc_id}
                    
                      ingress {
                        from_port   = 0
                        to_port     = 0
                        protocol    = "-1"
                        cidr_blocks = ${project.subnet_cidr_blocks}
                      }
                    
                      egress {
                        from_port   = 0
                        to_port     = 0
                        protocol    = "-1"
                        cidr_blocks = ${project.subnet_cidr_blocks}
                      }
                    }
            
                    resource "aws_security_group" "access_outside" {
                      name   = "$namePrefix-access-outside"
                      vpc_id = ${project.vpc_id}
                    
                      egress {
                        from_port   = 0
                        to_port     = 0
                        protocol    = "-1"
                        cidr_blocks     = ["0.0.0.0/0"]
                      }
                    }
            
                    resource "aws_security_group" "executeapi" {
                      name   = "$namePrefix-execute-api"
                      vpc_id = ${project.vpc_id}
                    
                      ingress {
                        from_port   = 443
                        to_port     = 443
                        protocol    = "tcp"
                        cidr_blocks = [${project.vpc_cidr_block}]
                      }
                    }
            
                    resource "aws_security_group" "lambdainvoke" {
                      name   = "$namePrefix-lambda-invoke"
                      vpc_id = ${project.vpc_id}
                    
                      ingress {
                        from_port   = 443
                        to_port     = 443
                        protocol    = "tcp"
                        cidr_blocks = [${project.vpc_cidr_block}]
                      }
                    }
                """.trimIndent()
                )
            }
            if (domain) {
                appendLine(
                    """
                    data "aws_route53_zone" "main" {
                      name = var.domain_name_zone
                    }
                """.trimIndent()
                )
            }
        },
    )
}