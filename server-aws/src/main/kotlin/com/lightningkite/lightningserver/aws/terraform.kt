@file:Suppress("DEPRECATION")

package com.lightningkite.lightningserver.aws

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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.File
import java.util.Properties

@Serializable
internal data class TerraformProjectInfo(
    val projectName: String,
    val bucket: String,
    val bucketPathOverride: String? = null,
    val vpc: Boolean = true,
    val existingVpc: Boolean = false,
    val domain: Boolean = true,
    val profile: String,
    val createBeforeDestroy: Boolean = false,
    val handlers: Map<String, String> = mapOf(),
) {
}

internal val TerraformProjectInfo.privateSubnets get() = if (existingVpc) "[for s in data.aws_subnet.private : s.id]" else "module.vpc.private_subnets"
internal val TerraformProjectInfo.subnet_cidr_blocks get() = if (existingVpc) "[for s in data.aws_subnet.private : s.cidr_block]" else "concat(module.vpc.private_subnets_cidr_blocks, module.vpc.private_subnets_cidr_blocks, [])"
internal val TerraformProjectInfo.vpc_id get() = if (existingVpc) "data.aws_vpc.main.id" else "module.vpc.vpc_id"
internal val TerraformProjectInfo.vpc_cidr_block get() = if (existingVpc) "data.aws_vpc.main.cidr_block" else "module.vpc.vpc_cidr_block"
internal val TerraformProjectInfo.public_route_table_ids get() = if (existingVpc) "toset([data.aws_vpc.main.main_route_table_id])" else "module.vpc.public_route_table_ids"
internal val TerraformProjectInfo.natGatewayIp get() = if (existingVpc) "[for s in data.aws_nat_gateway.main : s.public_ip]" else "module.vpc.nat_public_ips"

internal val TerraformProjectInfo.projectNameSafe: String
    get() = projectName.filter {
        it.isLetterOrDigit() || it in setOf(
            '-',
            '_'
        )
    }
internal val TerraformProjectInfo.namePrefix: String get() = projectNameSafe
internal val TerraformProjectInfo.namePrefixLower: String get() = projectNameSafe.lowercase()
internal val TerraformProjectInfo.namePrefixUnderscores: String get() = projectNameSafe.replace("-", "_")
internal val TerraformProjectInfo.namePrefixSafe: String get() = projectNameSafe.filter { it.isLetterOrDigit() }
internal val TerraformProjectInfo.namePrefixPath: String
    get() = projectNameSafe.lowercase().replace("-", "/").replace("_", "")
internal val TerraformProjectInfo.namePrefixPathSegment: String get() = projectNameSafe.lowercase().replace("_", "")

internal data class TerraformRequirementBuildInfo(
    val project: TerraformProjectInfo,
    val name: String,
    val appendable: Appendable,
) : Appendable by appendable {
    val key: String get() = name
}

internal val TerraformRequirementBuildInfo.namePrefix: String get() = project.namePrefix
internal val TerraformRequirementBuildInfo.namePrefixLower: String get() = project.namePrefixLower
internal val TerraformRequirementBuildInfo.namePrefixUnderscores: String get() = project.namePrefixUnderscores
internal val TerraformRequirementBuildInfo.namePrefixSafe: String get() = project.namePrefixSafe
internal val TerraformRequirementBuildInfo.namePrefixPath: String get() = project.namePrefixPath
internal val TerraformRequirementBuildInfo.namePrefixPathSegment: String get() = project.namePrefixPathSegment

internal data class TerraformProvider(
    val name: String,
    val source: String,
    val version: String,
) {
    companion object {
        val aws = TerraformProvider("aws", "hashicorp/aws", "~> 4.30")
        val random = TerraformProvider("random", "hashicorp/random", "~> 3.1.0")
        val archive = TerraformProvider("archive", "hashicorp/archive", "~> 2.2.0")
        val mongodbatlas = TerraformProvider("mongodbatlas", "mongodb/mongodbatlas", "~> 1.4")
        val local = TerraformProvider("local", "hashicorp/local", "~> 2.2")
        val nullProvider = TerraformProvider("null", "hashicorp/null", "~> 3.2")
    }
}

internal data class TerraformSection(
    val name: String,
    val providers: List<TerraformProvider> = listOf(
        TerraformProvider.aws,
        TerraformProvider.local,
        TerraformProvider.random,
        TerraformProvider.nullProvider,
        TerraformProvider.archive
    ),
    val inputs: List<TerraformInput> = listOf(),
    val emit: Appendable.() -> Unit = {},
    val toLightningServer: Map<String, String>? = null,
    val outputs: List<TerraformOutput> = listOf(),
) {
    companion object {

        fun <T> default(setting: Settings.Requirement<T, *>) = TerraformSection(
            name = setting.name,
            inputs = listOf(
                TerraformInput(
                    name = setting.name,
                    type = "any",
                    default = setting.default.let {
                        Serialization.Internal.json.encodeToString(
                            setting.serializer,
                            it
                        )
                    },
                    nullable = setting.serializer.descriptor.isNullable,
                    description = setting.description
                ),
            ),
            toLightningServer = mapOf(setting.name to "var.${setting.name}")
        )
    }
}

internal data class TerraformHandler(
    val name: String,
    val priority: Int = 0,
    val makeSection: TerraformProjectInfo.(settingKey: String) -> TerraformSection,
) {
    companion object {
        val handlers =
            HashMap<KSerializer<*>, HashMap<String, TerraformHandler>>()

        inline fun <reified T : Any> handler(
            name: String = "Standard",
            priority: Int = 0,
            providers: List<TerraformProvider> = listOf(
                TerraformProvider.aws,
                TerraformProvider.random,
                TerraformProvider.archive
            ),
            noinline inputs: TerraformProjectInfo.(settingKey: String) -> List<TerraformInput> = { listOf() },
            noinline emit: TerraformRequirementBuildInfo.() -> Unit = { },
            noinline settingOutput: TerraformProjectInfo.(settingKey: String) -> String,
        ) {
            handlers.getOrPut(serializer<T>()) { HashMap() }.put(name, TerraformHandler(name, priority) { it ->
                TerraformSection(
                    name = it,
                    providers = providers,
                    inputs = inputs(this, it),
                    emit = { emit(TerraformRequirementBuildInfo(this@TerraformHandler, it, this)) },
                    toLightningServer = mapOf(it to settingOutput(this, it)),
                    outputs = listOf()
                )
            })
        }
    }
}

internal data class Validation(
    val condition: String,
    val errorMessage: String,
)

internal data class TerraformInput(
    val name: String,
    val type: String,
    val default: String?,
    val nullable: Boolean = false,
    val description: String? = null,
    val validations: List<Validation> = emptyList(),
) {
    companion object {
        fun stringList(name: String, default: List<String>?, nullable: Boolean = false, description: String? = null) =
            TerraformInput(
                name,
                "list(string)",
                default?.joinToString(", ", "[", "]") { "\"$it\"" },
                nullable = nullable,
                description = description,
            )

        fun string(name: String, default: String?, nullable: Boolean = false, description: String? = null) =
            TerraformInput(name, "string", default?.let { "\"$it\"" }, nullable = nullable, description = description)

        fun boolean(name: String, default: Boolean?, nullable: Boolean = false, description: String? = null) =
            TerraformInput(name, "bool", default?.toString(), nullable = nullable, description = description)

        fun number(name: String, default: Number?, nullable: Boolean = false, description: String? = null) =
            TerraformInput(name, "number", default?.toString(), nullable = nullable, description = description)
    }
}

internal data class TerraformOutput(val name: String, val value: String) {
    companion object {

    }
}

internal fun handlers() {
    TerraformHandler.handler<GeneralServerSettings>(
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
    TerraformHandler.handler<FilesSettings>(
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
                resource "aws_iam_role_policy_attachment" "${key}" {
                  role       = aws_iam_role.main_exec.name
                  policy_arn = aws_iam_policy.${key}.arn
                }
            """.trimIndent()
            )
        },
        settingOutput = { key ->
            """
                {
                    storageUrl = "s3://${'$'}{aws_s3_bucket.${key}.id}.s3-${'$'}{aws_s3_bucket.${key}.region}.amazonaws.com"
                    signedUrlExpiration = var.${key}_expiry
                }
            """.trimIndent()
        }
    )
    TerraformHandler.handler<DatabaseSettings>(
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
    TerraformHandler.handler<DatabaseSettings>(
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
    TerraformHandler.handler<DatabaseSettings>(
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
    TerraformHandler.handler<DatabaseSettings>(
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
    TerraformHandler.handler<DatabaseSettings>(
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
    TerraformHandler.handler<CacheSettings>(
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
    TerraformHandler.handler<CacheSettings>(
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
    TerraformHandler.handler<JwtSigner>(
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
    TerraformHandler.handler<SecretBasis>(
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
    TerraformHandler.handler<MetricSettings>(
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
                resource "aws_iam_role_policy_attachment" "${key}" {
                  role       = aws_iam_role.main_exec.name
                  policy_arn = aws_iam_policy.${key}.arn
                }
                """.trimIndent()
            )
        },
        settingOutput = { key ->
            """
                {
                    url = "cloudwatch://${'$'}{var.deployment_location}/${'$'}{var.${key}_namespace}"
                    trackingByEntryPoint = var.${key}_tracked
                }
            """.trimIndent()
        }
    )
    TerraformHandler.handler<EmailSettings>(
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
    TerraformHandler.handler<EmailSettings>(
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
            appendLine(
                """
        
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

internal fun awsCloudwatch(projectInfo: TerraformProjectInfo) = with(projectInfo) {
    TerraformSection(
        name = "alarms",
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
                    resource "aws_lambda_permission" "scheduled_task_${safeName}" {
                      action        = "lambda:InvokeFunction"
                      function_name = "${'$'}{aws_lambda_alias.main.function_name}:${'$'}{aws_lambda_alias.main.name}"
                      principal     = "events.amazonaws.com"
                      source_arn    = aws_cloudwatch_event_rule.scheduled_task_${safeName}.arn
                      lifecycle {
                        create_before_destroy = $createBeforeDestroy
                      }
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
                    resource "aws_lambda_permission" "scheduled_task_${safeName}" {
                      action        = "lambda:InvokeFunction"
                      function_name = "${'$'}{aws_lambda_alias.main.function_name}:${'$'}{aws_lambda_alias.main.name}"
                      principal     = "events.amazonaws.com"
                      source_arn    = aws_cloudwatch_event_rule.scheduled_task_${safeName}.arn
                      lifecycle {
                        create_before_destroy = $createBeforeDestroy
                      }
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
                    resource "aws_lambda_permission" "scheduled_task_${safeName}" {
                      action        = "lambda:InvokeFunction"
                      function_name = "${'$'}{aws_lambda_alias.main.function_name}:${'$'}{aws_lambda_alias.main.name}"
                      principal     = "events.amazonaws.com"
                      source_arn    = aws_cloudwatch_event_rule.scheduled_task_${safeName}.arn
                      lifecycle {
                        create_before_destroy = $createBeforeDestroy
                      }
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

fun terraformMigrate(handlerFqn: String, folder: File) {
    val newFolder = folder
    val oldFolder = folder.parentFile!!.resolve(folder.name + "-old")
    folder.renameTo(oldFolder)
    newFolder.mkdirs()
    try {

        val handlerFile = oldFolder.resolve("handlers.properties")
        val handlerNames: Properties = handlerFile
            .takeIf { it.exists() }
            ?.let { Properties().apply { it.inputStream().use { s -> load(s) } } }
            ?: Properties()

        val oldBaseTfText = oldFolder.resolve("base/main.tf").readText()

        for (environmentOld in oldFolder.listFiles()!!) {
            if (!environmentOld.isDirectory) continue
            if (environmentOld.name == "base") continue
            if (environmentOld.name == "domain") continue
            if (environmentOld.name == "nodomain") continue
            val environmentNew = newFolder.resolve(environmentOld.name)
            environmentNew.mkdirs()
            println("AWS Profile for ${environmentOld.name}:")
            val profile = readln()
            val oldTfText = environmentOld.resolve("main.tf").readText()
            val info = TerraformProjectInfo(
                projectName = oldBaseTfText
                    .substringAfter("name")
                    .substringAfter('=')
                    .trim()
                    .substringBefore("$")
                    .trim('"')
                    .trim('-') + "-" + oldTfText
                    .substringAfter("deployment_name")
                    .substringAfter('=')
                    .substringAfter('"')
                    .trim()
                    .substringBefore('"')
                    .trim('-'),
                bucket = oldTfText
                    .substringAfter("bucket")
                    .substringAfter('=')
                    .substringAfter('"')
                    .trim()
                    .substringBefore('"'),
                bucketPathOverride = oldTfText
                    .substringAfter("key")
                    .substringAfter('=')
                    .substringAfter('"')
                    .trim()
                    .substringBefore('"'),
                vpc = false,
                domain = oldTfText.contains("../domain"),
                profile = profile,
                handlers = handlerNames.keys.filterIsInstance<String>().associateWith { handlerNames.getProperty(it) }
            )
            val projectInfoFile = environmentNew.resolve("project.json")
            @Suppress("JSON_FORMAT_REDUNDANT")
            projectInfoFile.writeText(
                Json(Serialization.Internal.json) { prettyPrint = true }
                    .encodeToString(TerraformProjectInfo.serializer(), info)
            )
            val oldStateFile = environmentNew.resolve("oldstate.json")
            assert(ProcessBuilder()
                .directory(environmentOld)
                .apply { environment()["AWS_PROFILE"] = profile }
                .command("terraform", "state", "pull")
                .inheritIO()
                .redirectOutput(oldStateFile)
                .start()
                .waitFor() == 0)
            environmentNew.resolve("terraform.tfvars").takeIf { !it.exists() }?.writeText(
                oldTfText.substringAfter("module \"domain\" {").trim().removeSuffix("}")
            )
            terraformEnvironmentAws(handlerFqn, environmentNew)
            println("For $environmentNew:")
            println(" - Clean up terraform.tfvars")
            println(" - Run `./tf init && ./tf state push newstate.json` to import a migrated state")
        }
    } catch (e: Exception) {
        newFolder.deleteRecursively()
        oldFolder.renameTo(newFolder)
    }
}

fun terraformAws(handlerFqn: String, projectName: String = "project", root: File) {
    if (root.resolve("base").exists()) {
        println("Base folder detected; need to migrate to new Terraform format.")
        println("***WARNING***")
        println("You *MUST* rebuild your program to use the new terraform due to a new settings parser!")
        println("Ensure the new AwsHandler uses 'loadSettings(AwsHandler::class.java)' to load settings.")
        println("Enter 'understood' to proceed.")
        assert(readln().equals("understood", true))
        terraformMigrate(handlerFqn, root)
        return
    }
    root.mkdirs()
    root.listFiles()!!.filter { it.isDirectory }.plus(
        root.resolve("example")
    ).distinct().forEach { terraformEnvironmentAws(handlerFqn, it, projectName) }
}

fun terraformEnvironmentAws(handlerFqn: String, folder: File, projectName: String = "project") {
    handlers()
    val projectInfoFile = folder.resolve("project.json")
    folder.mkdirs()
    val defaultHandlers = Settings.requirements.entries.associate {
        it.key to (TerraformHandler.handlers[it.value.serializer]?.maxBy { it.value.priority }?.key ?: "Direct")
    }
    val info = projectInfoFile
        .takeIf { it.exists() }
        ?.readText()
        ?.let { Serialization.Internal.json.decodeFromString(TerraformProjectInfo.serializer(), it) }
        ?.let { it.copy(handlers = defaultHandlers + it.handlers) }
        ?: TerraformProjectInfo(
            projectName = projectName,
            bucket = "your-deployment-bucket",
            vpc = false,
            domain = true,
            profile = "default",
            handlers = defaultHandlers,
        )
    @Suppress("JSON_FORMAT_REDUNDANT")
    projectInfoFile.writeText(
        Json(Serialization.Internal.json) { prettyPrint = true }
            .encodeToString(TerraformProjectInfo.serializer(), info)
    )

    val sections = listOf(
        listOf(defaultAwsHandler(info)),
        Settings.requirements.values.map {
            val handler = TerraformHandler.handlers[it.serializer]?.let { handlers ->
                val handlerName = info.handlers.get(it.name)
                if (handlerName == "Direct") return@let null
                handlers[handlerName]!!
            }
            handler?.makeSection?.invoke(info, it.name) ?: TerraformSection.default(it)
        },
        listOfNotNull(
//            if(Http.endpoints.isNotEmpty()) httpAwsHandler(info) else null,
            httpAwsHandler(info),
//            if(WebSockets.handlers.isNotEmpty()) wsAwsHandler(info) else null,
            wsAwsHandler(info),
            awsCloudwatch(info),
        ),
        scheduleAwsHandlers(info)
    ).flatten()
    val allSections = sections + awsLambdaHandler(info, handlerFqn, sections)

    val sectionToFile = allSections.associateWith { section ->
        folder.resolve(section.name.filter { it.isLetterOrDigit() } + ".tf")
    }
    val warning = "# Generated via Lightning Server.  This file will be overwritten or deleted when regenerating."
    folder.listFiles()!!.filter {
        it.extension == "tf" && it.readText().contains(warning)
    }.forEach { it.delete() }
    for ((section, file) in sectionToFile) {
//        if(!file.readText().contains(warning)) continue
        file.printWriter().use { it ->
            it.appendLine(warning)
            it.appendLine("##########")
            it.appendLine("# Inputs")
            it.appendLine("##########")
            it.appendLine()
            for (input in section.inputs) {
                it.appendLine("variable \"${input.name}\" {")
                it.appendLine("    type = ${input.type}")
                input.default?.let { d ->
                    it.appendLine("    default = $d")
                }
                it.appendLine("    nullable = ${input.nullable}")
                input.description?.let { d ->
                    it.appendLine("    description = \"$d\"")
                }
                input.validations.forEach { validation ->
                    it.appendLine("    validation {")
                    it.appendLine("        condition = ${validation.condition}")
                    it.appendLine("        error_message = ${validation.errorMessage}")
                    it.appendLine("    }")
                }
                it.appendLine("}")
            }
            it.appendLine()
            it.appendLine("##########")
            it.appendLine("# Outputs")
            it.appendLine("##########")
            it.appendLine()
            for (output in section.outputs) {
                it.appendLine("output \"${output.name}\" {")
                it.appendLine("    value = ${output.value}")
                it.appendLine("}")
            }
            it.appendLine()
            it.appendLine("##########")
            it.appendLine("# Resources")
            it.appendLine("##########")
            it.appendLine()
            section.emit(it)
            it.appendLine()
        }
    }

    val usingMongo = allSections.any { it.providers.any { it.name == "mongodbatlas" } }
    if (usingMongo) {
        fun get(name: String): String {
            println("$name for profile ${info.profile}:")
            return readln()
        }

        val mongoCredsFile = File(System.getProperty("user.home")).resolve(".mongo/profiles/${info.profile}.env")
        val mongoCredsFile2 = File(System.getProperty("user.home")).resolve(".mongo/profiles/${info.profile}.ps1")
        mongoCredsFile.parentFile.mkdirs()
        if (!mongoCredsFile.exists()) {
            val mongoPublic = if (usingMongo) get("MongoDB Public Key") else null
            val mongoPrivate = if (usingMongo) get("MongoDB Private Key") else null
            mongoCredsFile.writeText(
                """
                    MONGODB_ATLAS_PUBLIC_KEY="$mongoPublic"
                    MONGODB_ATLAS_PRIVATE_KEY="$mongoPrivate"
                """.trimIndent() + "\n"
            )
            mongoCredsFile.setExecutable(true)
            mongoCredsFile2.writeText(
                """
                    ${'$'}env:MONGODB_ATLAS_PUBLIC_KEY = "$mongoPublic"
                    ${'$'}env:MONGODB_ATLAS_PRIVATE_KEY = "$mongoPrivate"
                """.trimIndent() + "\n"
            )
            mongoCredsFile2.setExecutable(true)
        }
    }

    folder.resolve("tf").printWriter().use {
        it.appendLine("#!/bin/bash")
        it.appendLine("export AWS_PROFILE=${info.profile}")
        if (usingMongo) {
            it.appendLine(
                """
                  export ${'$'}(cat ~/.mongo/profiles/${info.profile}.env | xargs)
            """.trimIndent()
            )
        }
        it.appendLine("terraform \"$@\"")
    }
    folder.resolve("tf").setExecutable(true)

    folder.resolve("tf.ps1").printWriter().use {
        it.appendLine("\$env:AWS_PROFILE = \"${info.profile}\"")
        if (usingMongo) {
            it.appendLine(
                """
                  . ~/.mongo/profiles/${info.profile}.ps1
            """.trimIndent()
            )
        }
        it.appendLine("terraform \$args")
    }
    folder.resolve("tf.ps1").setExecutable(true)

    folder.resolve("main.tf").printWriter().use {
        it.appendLine("""terraform {""")
        it.appendLine("  required_providers {")
        for (provider in allSections.flatMap { it.providers }.distinct()) {
            it.appendLine("    ${provider.name} = {")
            it.appendLine("      source = \"${provider.source}\"")
            it.appendLine("      version = \"${provider.version}\"")
            it.appendLine("    }")
        }
        it.appendLine("  }")
        it.appendLine("""  backend "s3" {""")
        it.appendLine("""    bucket = "${info.bucket}"""")
        info.bucketPathOverride?.let { override ->
            it.appendLine("""    key    = "${override}"""")
        } ?: run {
            it.appendLine("""    key    = "${info.projectNameSafe}/${folder.name}"""")
        }
        it.appendLine("""    region = "us-west-2"""")
        it.appendLine("""  }""")
        it.appendLine("""}""")
        it.appendLine("""provider "aws" {""")
        it.appendLine("""  region = "us-west-2"""")
        it.appendLine("""}""")
        it.appendLine("""provider "aws" {""")
        it.appendLine("""  alias = "acm"""")
        it.appendLine("""  region = "us-east-1"""")
        it.appendLine("""}""")
        if (usingMongo) {
            it.appendLine(
                """   
                provider "mongodbatlas" {
                }
            """.trimIndent()
            )
        }
    }

    folder.resolve("oldstate.json").takeIf { it.exists() }?.readText()?.let {
        folder.resolve("newstate.json").writeText(it
            .replace("module.domain.module.Base.", "")
            .replace("module.nodomain.module.Base.", "")
            .replace("module.domain.module.Base", "")
            .replace("module.nodomain.module.Base", "")
            .replace("module.Base.", "")
            .replace("module.domain.", "")
            .replace("module.nodomain.", "")
            .replace("module.Base", "")
            .replace("module.domain", "")
            .replace("module.nodomain", "")
            .replace(""""module": "",""", "")
            .let {
                it.substringBefore("\"serial\": ") +
                        "\"serial\": " +
                        it.substringAfter("\"serial\": ").substringBefore(",").toInt().plus(1) +
                        "," +
                        it.substringAfter("\"serial\": ").substringAfter(",")
            }
        )
    }

    folder.resolve("terraform.tfvars").takeUnless { it.exists() }?.writeText(
        allSections.flatMap { it.inputs }.distinct().joinToString("\n") { it.name + " = " + it.default } + "\n"
    )
}
