package com.lightningkite.lightningserver.aws.terraform

import com.lightningkite.lightningserver.auth.JwtSigner
import com.lightningkite.lightningserver.cache.CacheSettings
import com.lightningkite.lightningserver.db.DatabaseSettings
import com.lightningkite.lightningserver.email.EmailSettings
import com.lightningkite.lightningserver.encryption.SecretBasis
import com.lightningkite.lightningserver.files.FilesSettings
import com.lightningkite.lightningserver.metrics.MetricSettings
import com.lightningkite.lightningserver.metrics.MetricType
import com.lightningkite.lightningserver.settings.GeneralServerSettings


object SettingsHandlers {
    val general = TerraformHandler.handler<GeneralServerSettings>(
        inputs = {
            listOf(
                TerraformInput(
                    name = "cors",
                    type = "object({ allowedDomains = list(string), allowedHeaders = list(string) })",
                    default = "null",
                    nullable = true,
                    description = "Defines the cors rules for the server."
                ),
                TerraformInput.string(
                    "display_name",
                    projectName,
                    description = "The GeneralSettings projectName."
                )
            )
        },
        settingOutput = {
            """
            {
                projectName = var.display_name
                publicUrl = ${if (domain) "\"https://${'$'}{var.domain_name}\"" else "aws_apigatewayv2_stage.http.invoke_url"}
                wsUrl = ${if (domain) "\"wss://ws.${'$'}{var.domain_name}?path=\"" else "\"\${aws_apigatewayv2_stage.ws.invoke_url}?path=\""}
                debug = var.debug
                cors = var.cors
            }
        """.trimIndent()
        }
    )
    val S3 = TerraformHandler.handler<FilesSettings>(
        name = "S3",
        inputs = { key ->
            listOf(
                TerraformInput.string("${key}_expiry", "P1D", nullable = true)
            )
        },
        emit = {
            appendLine(
                """
                resource "aws_s3_bucket" "${key}" {
                  bucket_prefix = "${namePrefixPathSegment}-${key.lowercase()}"
                  force_destroy = var.debug
                }
                resource "aws_s3_bucket_cors_configuration" "${key}" {
                  bucket = aws_s3_bucket.${key}.bucket
                
                  cors_rule {
                    allowed_headers = ["*"]
                    allowed_methods = ["PUT", "POST"]
                    allowed_origins = ["*"]
                    expose_headers  = ["ETag"]
                    max_age_seconds = 3000
                  }
                
                  cors_rule {
                    allowed_headers = ["*"]
                    allowed_methods = ["GET", "HEAD"]
                    allowed_origins = ["*"]
                  }
                }
                resource "aws_s3_bucket_public_access_block" "$key" {
                  count = var.${key}_expiry == null ? 1 : 0
                  bucket = aws_s3_bucket.$key.id
                
                  block_public_acls   = false
                  block_public_policy = false
                  ignore_public_acls = false
                  restrict_public_buckets = false
                }
                resource "aws_s3_bucket_policy" "$key" {  
                  depends_on = [aws_s3_bucket_public_access_block.${key}]
                  count = var.${key}_expiry == null ? 1 : 0
                  bucket = aws_s3_bucket.$key.id   
                  policy = <<POLICY
                {    
                    "Version": "2012-10-17",    
                    "Statement": [        
                      {            
                          "Sid": "PublicReadGetObject",            
                          "Effect": "Allow",            
                          "Principal": "*",            
                          "Action": [                
                             "s3:GetObject"            
                          ],            
                          "Resource": [
                             "arn:aws:s3:::${'$'}{aws_s3_bucket.$key.id}/*"            
                          ]        
                      }    
                    ]
                }
                POLICY
                }
                # resource "aws_s3_bucket_acl" "${key}" {
                #   bucket = aws_s3_bucket.${key}.id
                #   acl    = var.${key}_expiry == null ? "public-read" : "private" 
                # }
                resource "aws_iam_policy" "${key}" {
                  name        = "${namePrefix}-${key}"
                  path = "/${namePrefixPath}/${key}/"
                  description = "Access to the ${namePrefix}_${key} bucket"
                  policy = jsonencode({
                    Version = "2012-10-17"
                    Statement = [
                      {
                        Action = [
                          "s3:*",
                        ]
                        Effect   = "Allow"
                        Resource = [
                            "${'$'}{aws_s3_bucket.${key}.arn}",
                            "${'$'}{aws_s3_bucket.${key}.arn}/*",
                        ]
                      },
                    ]
                  })
                }
            """.trimIndent()
            )
        },
        policies = { key -> listOf(key) },
        settingOutput = { key ->
            """
                {
                    storageUrl = "s3://${'$'}{aws_s3_bucket.${key}.id}.s3-${'$'}{aws_s3_bucket.${key}.region}.amazonaws.com"
                    signedUrlExpiration = var.${key}_expiry
                }
            """.trimIndent()
        }
    )
    val DocumentDB = TerraformHandler.handler<DatabaseSettings>(
        name = "DocumentDB",
        inputs = { key ->
            listOf(TerraformInput.string("${key}_instance_class", "db.t4g.medium"))
        },
        emit = {
            if (!project.vpc) throw UnsupportedOperationException("DocumentDB requires VPC")
            appendLine(
                """
                resource "random_password" "${key}" {
                  length           = 32
                  special          = true
                  override_special = "-_"
                }
                resource "aws_docdb_subnet_group" "${key}" {
                  name       = "$namePrefix-${key}"
                  subnet_ids = ${project.privateSubnets}
                }
                resource "aws_docdb_cluster_parameter_group" "${key}" {
                  family = "docdb4.0"
                  name = "$namePrefix-${key}-parameter-group"
                  parameter {
                    name  = "tls"
                    value = "disabled"
                  }
                }
                resource "aws_docdb_cluster" "${key}" {
                  cluster_identifier = "${namePrefix}-${key}"
                  engine = "docdb"
                  master_username = "master"
                  master_password = random_password.${key}.result
                  backup_retention_period = 5
                  preferred_backup_window = "07:00-09:00"
                  skip_final_snapshot = true

                  db_cluster_parameter_group_name = "${'$'}{aws_docdb_cluster_parameter_group.${key}.name}"
                  vpc_security_group_ids = [aws_security_group.internal.id]
                  db_subnet_group_name    = "${'$'}{aws_docdb_subnet_group.${key}.name}"
                }
                resource "aws_docdb_cluster_instance" "${key}" {
                  count              = 1
                  identifier         = "$namePrefix-${key}-${'$'}{count.index}"
                  cluster_identifier = "${'$'}{aws_docdb_cluster.${key}.id}"
                  instance_class     = "db.t4g.medium"
                }
            """.trimIndent()
            )
        },
        settingOutput = { key ->
            """
                {
                    url = "mongodb://master:${'$'}{random_password.${key}.result}@${'$'}{aws_docdb_cluster_instance.${key}[0].endpoint}/?retryWrites=false"
                    databaseName = "${namePrefix}_${key}"
                }
            """.trimIndent()
        }
    )
    val `AuroraDB Serverless V1` = TerraformHandler.handler<DatabaseSettings>(
        name = "AuroraDB Serverless V1",
        priority = 1,
        inputs = { key ->
            listOf(
                TerraformInput.number("${key}_min_capacity", 2),
                TerraformInput.number("${key}_max_capacity", 4),
                TerraformInput.boolean("${key}_auto_pause", true)
            )
        },
        emit = {
            if (!project.vpc) throw UnsupportedOperationException("DocumentDB requires VPC")
            appendLine(
                """
                resource "random_password" "${key}" {
                  length           = 32
                  special          = true
                  override_special = "-_"
                }
                resource "aws_db_subnet_group" "${key}" {
                  name       = "$namePrefix-${key}"
                  subnet_ids = ${project.privateSubnets}
                }
                resource "aws_rds_cluster" "$key" {
                  cluster_identifier = "$namePrefix-${key}"
                  engine             = "aurora-postgresql"
                  engine_mode        = "serverless"
                  engine_version     = "10.18"
                  database_name      = "$namePrefixSafe${key}"
                  master_username = "master"
                  master_password = random_password.${key}.result
                  skip_final_snapshot = var.debug
                  final_snapshot_identifier = "$namePrefix-${key}"
                  enable_http_endpoint = true
                  vpc_security_group_ids = [aws_security_group.internal.id]
                  db_subnet_group_name    = "${'$'}{aws_db_subnet_group.${key}.name}"

                  scaling_configuration {
                    auto_pause = var.${key}_auto_pause
                    min_capacity = var.${key}_min_capacity
                    max_capacity = var.${key}_max_capacity
                    seconds_until_auto_pause = 300
                    timeout_action = "ForceApplyCapacityChange"
                  }
                }
            """.trimIndent()
            )
        },
        settingOutput = { key ->
            """
                {
                    url = "postgresql://master:${'$'}{random_password.${key}.result}@${'$'}{aws_rds_cluster.database.endpoint}/$namePrefixSafe${key}"
                }
            """.trimIndent()
        }
    )
    val `AuroraDB Serverless V2` = TerraformHandler.handler<DatabaseSettings>(
        name = "AuroraDB Serverless V2",
        priority = 2,
        inputs = { key ->
            listOf(
                TerraformInput.number("${key}_min_capacity", 0.5),
                TerraformInput.number("${key}_max_capacity", 2),
                TerraformInput.boolean("${key}_auto_pause", true)
            )
        },
        emit = {
            if (project.vpc) {
                appendLine(
                    """
                    resource "aws_db_subnet_group" "${key}" {
                      name       = "$namePrefix-${key}"
                      subnet_ids = ${project.privateSubnets} 
                    }
                """.trimIndent()
                )
            }
            appendLine(
                """
                resource "random_password" "${key}" {
                  length           = 32
                  special          = true
                  override_special = "-_"
                }
                resource "aws_rds_cluster" "$key" {
                  cluster_identifier = "$namePrefix-${key}"
                  engine             = "aurora-postgresql"
                  engine_mode        = "provisioned"
                  engine_version     = "13.6"
                  database_name      = "$namePrefixSafe${key}"
                  master_username = "master"
                  master_password = random_password.${key}.result
                  skip_final_snapshot = var.debug
                  final_snapshot_identifier = "$namePrefix-${key}"
                  ${if (project.vpc) """vpc_security_group_ids = [aws_security_group.internal.id]""" else ""}
                  ${if (project.vpc) """db_subnet_group_name    = "${'$'}{aws_db_subnet_group.${key}.name}"""" else ""}

                  serverlessv2_scaling_configuration {
                    min_capacity = var.${key}_min_capacity
                    max_capacity = var.${key}_max_capacity
                  }
                }

                resource "aws_rds_cluster_instance" "$key" {
                  publicly_accessible = ${!project.vpc}
                  cluster_identifier = aws_rds_cluster.$key.id
                  instance_class     = "db.serverless"
                  engine             = aws_rds_cluster.$key.engine
                  engine_version     = aws_rds_cluster.$key.engine_version
                  ${if (project.vpc) """db_subnet_group_name    = "${'$'}{aws_db_subnet_group.${key}.name}"""" else ""}
                }
            """.trimIndent()
            )
        },
        settingOutput = { key ->
            //url = "${'$'}{var.${key}_auto_pause ? "auroradb-autopause" : "postgresql"}://master:${'$'}{random_password.${key}.result}@${'$'}{aws_rds_cluster.database.endpoint}/$namePrefixSafe${key}"
            """
                {
                    url = "postgresql://master:${'$'}{random_password.${key}.result}@${'$'}{aws_rds_cluster.database.endpoint}/$namePrefixSafe${key}"
                }
            """.trimIndent()
        }
    )
    val `MongoDB Serverless` = TerraformHandler.handler<DatabaseSettings>(
        name = "MongoDB Serverless",
        priority = 0,
        providers = listOf(TerraformProvider.mongodbatlas),
        inputs = { key ->
            listOf(
                TerraformInput.string("${key}_org_id", null),
                TerraformInput.boolean("${key}_continuous_backup", false),
//                TerraformInput.string("${key}_team_id", null)
            )
        },
        emit = {
            appendLine(
                """
                resource "mongodbatlas_project" "$key" {
                  name   = "$namePrefixSafe$key"
                  org_id = var.${key}_org_id
                  
                  is_collect_database_specifics_statistics_enabled = true
                  is_data_explorer_enabled                         = true
                  is_performance_advisor_enabled                   = true
                  is_realtime_performance_panel_enabled            = true
                  is_schema_advisor_enabled                        = true
                }
                resource "random_password" "${key}" {
                  length           = 32
                  special          = true
                  override_special = "-_"
                }
                resource "mongodbatlas_serverless_instance" "$key" {
                  project_id   = mongodbatlas_project.$key.id
                  name         = "$namePrefixSafe$key"

                  provider_settings_backing_provider_name = "AWS"
                  provider_settings_provider_name = "SERVERLESS"
                  provider_settings_region_name = replace(upper(var.deployment_location), "-", "_")
                  
                  continuous_backup_enabled = var.${key}_continuous_backup
                }
                resource "mongodbatlas_database_user" "$key" {
                  username           = "$namePrefixSafe$key-main"
                  password           = random_password.$key.result
                  project_id         = mongodbatlas_project.$key.id
                  auth_database_name = "admin"

                  roles {
                    role_name     = "readWrite"
                    database_name = "default"
                  }

                  roles {
                    role_name     = "readAnyDatabase"
                    database_name = "admin"
                  }

                }
            """.trimIndent()
            )
            if (project.vpc) {
                appendLine(
                    """
                resource "mongodbatlas_project_ip_access_list" "$key" {
                  for_each = toset(${project.natGatewayIp})
                  project_id   = mongodbatlas_project.$key.id
                  cidr_block = "${'$'}{each.value}/32"
                  comment    = "NAT Gateway"
                }
                """.trimIndent()
                )
            } else {
                appendLine(
                    """
                resource "mongodbatlas_project_ip_access_list" "$key" {
                  project_id   = mongodbatlas_project.$key.id
                  cidr_block = "0.0.0.0/0"
                  comment    = "Anywhere"
                }
                """.trimIndent()
                )
            }
        },
        settingOutput = { key ->
            """
                {
                  url = "mongodb+srv://$namePrefixSafe$key-main:${'$'}{random_password.${key}.result}@${'$'}{replace(mongodbatlas_serverless_instance.$key.connection_strings_standard_srv, "mongodb+srv://", "")}/default?retryWrites=true&w=majority"
                }
            """.trimIndent()
        }
    )
    val `MongoDB Dedicated` = TerraformHandler.handler<DatabaseSettings>(
        name = "MongoDB Dedicated",
        priority = 0,
        providers = listOf(TerraformProvider.mongodbatlas),
        inputs = { key ->
            listOf(
                TerraformInput.string("${key}_org_id", null),
                TerraformInput.string("${key}_min_size", "M10"),
                TerraformInput.string("${key}_max_size", "M40")
            )
        },
        emit = {
            appendLine(
                """
                resource "mongodbatlas_project" "$key" {
                  name   = "$namePrefixSafe$key"
                  org_id = var.${key}_org_id
                  
                  is_collect_database_specifics_statistics_enabled = true
                  is_data_explorer_enabled                         = true
                  is_performance_advisor_enabled                   = true
                  is_realtime_performance_panel_enabled            = true
                  is_schema_advisor_enabled                        = true
                }
                resource "random_password" "${key}" {
                  length           = 32
                  special          = true
                  override_special = "-_"
                }
                resource "mongodbatlas_advanced_cluster" "database" {
                  project_id   = mongodbatlas_project.database.id
                  name         = "$namePrefixSafe$key"
                  cluster_type = "REPLICASET"
                #  lifecycle { ignore_changes = [instance_size] }
                  replication_specs {
                    region_configs {
                      auto_scaling {
                        compute_enabled = true
                        compute_min_instance_size = "M10"
                        compute_max_instance_size = var.${key}_max_size
                        compute_scale_down_enabled = true
                        disk_gb_enabled = true
                      }
                      electable_specs {
                        instance_size = var.${key}_min_size
                        node_count    = 3
                      }
                      analytics_specs {
                        instance_size = var.${key}_min_size
                        node_count    = 1
                      }
                      priority      = 7
                      provider_name = "AWS"
                      region_name   = replace(upper(var.deployment_location), "-", "_")
                    }
                  }
                }
                resource "mongodbatlas_database_user" "$key" {
                  username           = "$namePrefixSafe$key-main"
                  password           = random_password.$key.result
                  project_id         = mongodbatlas_project.$key.id
                  auth_database_name = "admin"

                  roles {
                    role_name     = "readWrite"
                    database_name = "default"
                  }

                  roles {
                    role_name     = "readAnyDatabase"
                    database_name = "admin"
                  }

                }
            """.trimIndent()
            )
            if (project.vpc) {
                appendLine(
                    """
                resource "mongodbatlas_project_ip_access_list" "$key" {
                  for_each = toset(${project.natGatewayIp})
                  project_id   = mongodbatlas_project.$key.id
                  cidr_block = "${'$'}{each.value}/32"
                  comment    = "NAT Gateway"
                }
                """.trimIndent()
                )
            } else {
                appendLine(
                    """
                resource "mongodbatlas_project_ip_access_list" "$key" {
                  project_id   = mongodbatlas_project.$key.id
                  cidr_block = "0.0.0.0/0"
                  comment    = "Anywhere"
                }
                """.trimIndent()
                )
            }
        },
        settingOutput = { key ->
            """
                {
                  url = "mongodb+srv://$namePrefixSafe$key-main:${'$'}{random_password.${key}.result}@${'$'}{replace(mongodbatlas_advanced_cluster.$key.connection_strings_standard_srv, "mongodb+srv://", "")}/default?retryWrites=true&w=majority"
                }
            """.trimIndent()
        }
    )
    val `MongoDB Serverless on Existing Project` = TerraformHandler.handler<DatabaseSettings>(
        name = "MongoDB Serverless on Existing Project",
        priority = 0,
        providers = listOf(TerraformProvider.mongodbatlas),
        inputs = { key ->
            listOf(
                TerraformInput.string("${key}_org_id", null),
                TerraformInput.boolean("${key}_continuous_backup", false),
                TerraformInput.string("${key}_project_id", null)
            )
        },
        emit = {
            appendLine(
                """
                resource "random_password" "${key}" {
                  length           = 32
                  special          = true
                  override_special = "-_"
                }
                resource "mongodbatlas_serverless_instance" "$key" {
                  project_id   = var.${key}_project_id
                  name         = "$namePrefixSafe$key"

                  provider_settings_backing_provider_name = "AWS"
                  provider_settings_provider_name = "SERVERLESS"
                  provider_settings_region_name = replace(upper(var.deployment_location), "-", "_")
                  
                  continuous_backup_enabled = var.${key}_continuous_backup
                }
                resource "mongodbatlas_database_user" "$key" {
                  username           = "$namePrefixSafe$key-main"
                  password           = random_password.$key.result
                  project_id         = var.${key}_project_id
                  auth_database_name = "admin"

                  roles {
                    role_name     = "readWrite"
                    database_name = "default"
                  }

                  roles {
                    role_name     = "readAnyDatabase"
                    database_name = "admin"
                  }

                }
            """.trimIndent()
            )
            if (project.vpc) {
                appendLine(
                    """
                resource "mongodbatlas_project_ip_access_list" "$key" {
                  for_each = toset(${project.natGatewayIp})
                  project_id   = var.${key}_project_id
                  cidr_block = "${'$'}{each.value}/32"
                  comment    = "NAT Gateway"
                }
                """.trimIndent()
                )
            } else {
                appendLine(
                    """
                resource "mongodbatlas_project_ip_access_list" "$key" {
                  project_id   = var.${key}_project_id
                  cidr_block = "0.0.0.0/0"
                  comment    = "Anywhere"
                }
                """.trimIndent()
                )
            }
        },
        settingOutput = { key ->
            """
                {
                  url = "mongodb+srv://$namePrefixSafe$key-main:${'$'}{random_password.${key}.result}@${'$'}{replace(mongodbatlas_serverless_instance.$key.connection_strings_standard_srv, "mongodb+srv://", "")}/default?retryWrites=true&w=majority"
                }
            """.trimIndent()
        }
    )
    val `MongoDB Dedicated on Existing Project` = TerraformHandler.handler<DatabaseSettings>(
        name = "MongoDB Dedicated on Existing Project",
        priority = 0,
        providers = listOf(TerraformProvider.mongodbatlas),
        inputs = { key ->
            listOf(
                TerraformInput.string("${key}_org_id", null),
                TerraformInput.string("${key}_min_size", "M10"),
                TerraformInput.string("${key}_max_size", "M40"),
                TerraformInput.string("${key}_project_id", null)
            )
        },
        emit = {
            appendLine(
                """
                resource "random_password" "${key}" {
                  length           = 32
                  special          = true
                  override_special = "-_"
                }
                resource "mongodbatlas_advanced_cluster" "database" {
                  project_id   = var.${key}_project_id
                  name         = "$namePrefixSafe$key"
                  cluster_type = "REPLICASET"
                #  lifecycle { ignore_changes = [instance_size] }
                  replication_specs {
                    region_configs {
                      auto_scaling {
                        compute_enabled = true
                        compute_min_instance_size = "M10"
                        compute_max_instance_size = var.${key}_max_size
                        compute_scale_down_enabled = true
                        disk_gb_enabled = true
                      }
                      electable_specs {
                        instance_size = var.${key}_min_size
                        node_count    = 3
                      }
                      analytics_specs {
                        instance_size = var.${key}_min_size
                        node_count    = 1
                      }
                      priority      = 7
                      provider_name = "AWS"
                      region_name   = replace(upper(var.deployment_location), "-", "_")
                    }
                  }
                }
                resource "mongodbatlas_database_user" "$key" {
                  username           = "$namePrefixSafe$key-main"
                  password           = random_password.$key.result
                  project_id         = var.${key}_project_id
                  auth_database_name = "admin"

                  roles {
                    role_name     = "readWrite"
                    database_name = "default"
                  }

                  roles {
                    role_name     = "readAnyDatabase"
                    database_name = "admin"
                  }

                }
            """.trimIndent()
            )
            if (project.vpc) {
                appendLine(
                    """
                resource "mongodbatlas_project_ip_access_list" "$key" {
                  for_each = toset(${project.natGatewayIp})
                  project_id   = var.${key}_project_id
                  cidr_block = "${'$'}{each.value}/32"
                  comment    = "NAT Gateway"
                }
                """.trimIndent()
                )
            } else {
                appendLine(
                    """
                resource "mongodbatlas_project_ip_access_list" "$key" {
                  project_id   = var.${key}_project_id
                  cidr_block = "0.0.0.0/0"
                  comment    = "Anywhere"
                }
                """.trimIndent()
                )
            }
        },
        settingOutput = { key ->
            """
                {
                  url = "mongodb+srv://$namePrefixSafe$key-main:${'$'}{random_password.${key}.result}@${'$'}{replace(mongodbatlas_advanced_cluster.$key.connection_strings_standard_srv, "mongodb+srv://", "")}/default?retryWrites=true&w=majority"
                }
            """.trimIndent()
        }
    )
    val ElastiCache = TerraformHandler.handler<CacheSettings>(
        name = "ElastiCache",
        inputs = { key ->
            listOf(
                TerraformInput.string("${key}_node_type", "cache.t2.micro"),
                TerraformInput.number("${key}_node_count", 1)
            )
        },
        emit = {
            if (!project.vpc) throw IllegalArgumentException("A VPC is required for ElastiCache for security purposes.")
            appendLine(
                """
                resource "aws_elasticache_cluster" "${key}" {
                  cluster_id           = "${namePrefix}-${key}"
                  engine               = "memcached"
                  node_type            = var.${key}_node_type
                  num_cache_nodes      = var.${key}_node_count
                  parameter_group_name = "default.memcached1.6"
                  port                 = 11211
                  security_group_ids   = [aws_security_group.internal.id]
                  subnet_group_name    = aws_elasticache_subnet_group.${key}.name
                }
                resource "aws_elasticache_subnet_group" "${key}" {
                  name       = "$namePrefix-${key}"
                  subnet_ids = ${project.privateSubnets}
                }
            """.trimIndent()
            )
        },
        settingOutput = { key ->
            """
                {
                    url = "memcached-aws://${'$'}{aws_elasticache_cluster.${key}.cluster_address}:11211"
                }
            """.trimIndent()
        }
    )
    val DynamoDB = TerraformHandler.handler<CacheSettings>(
        name = "DynamoDB",
        priority = 1,
        settingOutput = { _ ->
            """
                {
                    url = "dynamodb://${'$'}{var.deployment_location}/${namePrefixUnderscores}"
                }
            """.trimIndent()
        }
    )
    val jwtSigner = TerraformHandler.handler<JwtSigner>(
        inputs = { key ->
            listOf(
                TerraformInput.string("${key}_expiration", "PT8760H"),
                TerraformInput.string("${key}_emailExpiration", "PT1H"),
            )
        },
        emit = {
            appendLine(
                """
                resource "random_password" "${key}" {
                  length           = 32
                  special          = true
                  override_special = "!#${'$'}%&*()-_=+[]{}<>:?"
                }
            """.trimIndent()
            )
        },
        settingOutput = { key ->
            """
                {
                    expiration = var.${key}_expiration 
                    emailExpiration = var.${key}_emailExpiration 
                    secret = random_password.${key}.result
                }
            """.trimIndent()
        }
    )
    val secretBasis = TerraformHandler.handler<SecretBasis>(
        inputs = { _ ->
            listOf(
            )
        },
        emit = {
            appendLine(
                """
                resource "random_password" "${key}" {
                  length           = 88
                  special          = true
                  override_special = "+/"
                }
            """.trimIndent()
            )
        },
        settingOutput = { key ->
            """
                random_password.${key}.result
            """.trimIndent()
        }
    )
    val Cloudwatch = TerraformHandler.handler<MetricSettings>(
        name = "Cloudwatch",
        inputs = { key ->
            listOf(
                TerraformInput.stringList("${key}_tracked", MetricType.known.map { it.name }),
                TerraformInput.string("${key}_namespace", this.projectName),
            )
        },
        emit = {
            appendLine(
                """
                resource "aws_iam_policy" "${key}" {
                  name        = "${namePrefix}-${key}"
                  path = "/${namePrefixPath}/${key}/"
                  description = "Access to publish metrics"
                  policy = jsonencode({
                    Version = "2012-10-17"
                    Statement = [
                      {
                        Action = [
                          "cloudwatch:PutMetricData",
                        ]
                        Effect   = "Allow"
                        Condition = {
                            StringEquals = {
                                "cloudwatch:namespace": var.${key}_namespace
                            }
                        }
                        Resource = ["*"]
                      },
                    ]
                  })
                }
                """.trimIndent()
            )
        },
        policies = { key -> listOf(key) },
        settingOutput = { key ->
            """
                {
                    url = "cloudwatch://${'$'}{var.deployment_location}/${'$'}{var.${key}_namespace}"
                    trackingByEntryPoint = var.${key}_tracked
                }
            """.trimIndent()
        }
    )
    val `SMTP through SES` = TerraformHandler.handler<EmailSettings>(
        name = "SMTP through SES",
        inputs = { key ->
            if (domain) {
                listOf(
                    TerraformInput.string("reporting_email", null)
                )
            } else {
                listOf(
                    TerraformInput.string("${key}_sender", null)
                )
            }
        },
        emit = {
            appendLine(
                """
                resource "aws_iam_user" "${key}" {
                  name = "${namePrefix}-${key}-user"
                }

                resource "aws_iam_access_key" "${key}" {
                  user = aws_iam_user.${key}.name
                }

                data "aws_iam_policy_document" "${key}" {
                  statement {
                    actions   = ["ses:SendRawEmail"]
                    resources = ["*"]
                  }
                }

                resource "aws_iam_policy" "${key}" {
                  name = "${namePrefix}-${key}-policy"
                  description = "Allows sending of e-mails via Simple Email Service"
                  policy      = data.aws_iam_policy_document.${key}.json
                }

                resource "aws_iam_user_policy_attachment" "${key}" {
                  user       = aws_iam_user.${key}.name
                  policy_arn = aws_iam_policy.${key}.arn
                }
                
            """.trimIndent()
            )

            if (project.vpc) {
                appendLine(
                    """
                    resource "aws_security_group" "${key}" {
                      name   = "${namePrefix}-${key}"
                      vpc_id = ${project.vpc_id}
                    
                      ingress {
                        from_port   = 587
                        to_port     = 587
                        protocol    = "tcp"
                        cidr_blocks = [${project.vpc_cidr_block}]
                      }
                    }
                    resource "aws_vpc_endpoint" "${key}" {
                      vpc_id = ${project.vpc_id}
                      service_name = "com.amazonaws.${'$'}{var.deployment_location}.email-smtp"
                      security_group_ids = [aws_security_group.${key}.id]
                      vpc_endpoint_type = "Interface"
                    }
                """.trimIndent()
                )
            }

            if (project.domain) {
                appendLine(
                    """
                    resource "aws_ses_domain_identity" "${key}" {
                      domain = var.domain_name
                    }
                    resource "aws_ses_domain_mail_from" "$key" {
                      domain           = aws_ses_domain_identity.$key.domain
                      mail_from_domain = "mail.${'$'}{var.domain_name}"
                    }
                    resource "aws_route53_record" "${key}_mx" {
                      zone_id = data.aws_route53_zone.main.zone_id
                      name    = aws_ses_domain_mail_from.$key.mail_from_domain
                      type    = "MX"
                      ttl     = "600"
                      records = ["10 feedback-smtp.${'$'}{var.deployment_location}.amazonses.com"] # Change to the region in which `aws_ses_domain_identity.example` is created
                    }
                    resource "aws_route53_record" "${key}" {
                      zone_id = data.aws_route53_zone.main.zone_id
                      name    = "_amazonses.${'$'}{var.domain_name}"
                      type    = "TXT"
                      ttl     = "600"
                      records = [aws_ses_domain_identity.${key}.verification_token]
                    }
                    resource "aws_ses_domain_dkim" "${key}_dkim" {
                      domain = aws_ses_domain_identity.${key}.domain
                    }
                    resource "aws_route53_record" "${key}_spf_mail_from" {
                      zone_id = data.aws_route53_zone.main.zone_id
                      name    = aws_ses_domain_mail_from.$key.mail_from_domain
                      type    = "TXT"
                      ttl     = "300"
                      records = [
                        "v=spf1 include:amazonses.com -all"
                      ]
                    }
                    resource "aws_route53_record" "${key}_spf_domain" {
                      zone_id = data.aws_route53_zone.main.zone_id
                      name    = aws_ses_domain_identity.$key.domain
                      type    = "TXT"
                      ttl     = "300"
                      records = [
                        "v=spf1 include:amazonses.com -all"
                      ]
                    }
                    resource "aws_route53_record" "${key}_dkim_records" {
                      count   = 3
                      zone_id = data.aws_route53_zone.main.zone_id
                      name    = "${'$'}{element(aws_ses_domain_dkim.${key}_dkim.dkim_tokens, count.index)}._domainkey.${'$'}{var.domain_name}"
                      type    = "CNAME"
                      ttl     = "300"
                      records = [
                        "${'$'}{element(aws_ses_domain_dkim.${key}_dkim.dkim_tokens, count.index)}.dkim.amazonses.com",
                      ]
                    }
                    resource "aws_route53_record" "${key}_route_53_dmarc_txt" {
                      zone_id = data.aws_route53_zone.main.zone_id
                      name    = "_dmarc.${'$'}{var.domain_name}"
                      type    = "TXT"
                      ttl     = "300"
                      records = [
                        "v=DMARC1;p=quarantine;pct=75;rua=mailto:${'$'}{var.reporting_email}"
                      ]
                    }
                """.trimIndent()
                )
            } else {
                appendLine(
                    """
                    resource "aws_ses_email_identity" "${key}" {
                      email = var.${key}_sender
                    }
                """.trimIndent()
                )
            }
        },
        settingOutput = { key ->
            """
                {
                    url = "smtp://${'$'}{aws_iam_access_key.${key}.id}:${'$'}{aws_iam_access_key.${key}.ses_smtp_password_v4}@email-smtp.${'$'}{var.deployment_location}.amazonaws.com:587" 
                    fromEmail = ${if (domain) "\"noreply@${'$'}{var.domain_name}\"" else "var.${key}_sender"}
                }
            """.trimIndent()
        }
    )
    val `SMTP through SES with Existing Identity` = TerraformHandler.handler<EmailSettings>(
        name = "SMTP through SES with Existing Identity",
        inputs = { key ->
            listOf(
                TerraformInput.string("${key}_sender", null)
            )
        },
        emit = {
            appendLine(
                """
                resource "aws_iam_user" "${key}" {
                  name = "${namePrefix}-${key}-user"
                }

                resource "aws_iam_access_key" "${key}" {
                  user = aws_iam_user.${key}.name
                }

                data "aws_iam_policy_document" "${key}" {
                  statement {
                    actions   = ["ses:SendRawEmail"]
                    resources = ["*"]
                  }
                }

                resource "aws_iam_policy" "${key}" {
                  name = "${namePrefix}-${key}-policy"
                  description = "Allows sending of e-mails via Simple Email Service"
                  policy      = data.aws_iam_policy_document.${key}.json
                }

                resource "aws_iam_user_policy_attachment" "${key}" {
                  user       = aws_iam_user.${key}.name
                  policy_arn = aws_iam_policy.${key}.arn
                }
                
            """.trimIndent()
            )

            if (project.vpc) {
                appendLine(
                    """
                    resource "aws_security_group" "${key}" {
                      name   = "${namePrefix}-${key}"
                      vpc_id = ${project.vpc_id}
                    
                      ingress {
                        from_port   = 587
                        to_port     = 587
                        protocol    = "tcp"
                        cidr_blocks = [${project.vpc_cidr_block}]
                      }
                    }
                    resource "aws_vpc_endpoint" "${key}" {
                      vpc_id = ${project.vpc_id}
                      service_name = "com.amazonaws.${'$'}{var.deployment_location}.email-smtp"
                      security_group_ids = [aws_security_group.${key}.id]
                      vpc_endpoint_type = "Interface"
                    }
                """.trimIndent()
                )
            }
        },
        settingOutput = { key ->
            """
                {
                    url = "smtp://${'$'}{aws_iam_access_key.${key}.id}:${'$'}{aws_iam_access_key.${key}.ses_smtp_password_v4}@email-smtp.${'$'}{var.deployment_location}.amazonaws.com:587" 
                    fromEmail = var.${key}_sender
                }
            """.trimIndent()
        }
    )
}