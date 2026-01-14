package com.lightningkite.lightningserver.terraform.awsec2

import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.terraform.*
import com.lightningkite.services.Untested
import com.lightningkite.services.data.DataSize
import com.lightningkite.services.data.DataSize.Companion.mebibytes
import com.lightningkite.services.data.EmailAddress
import com.lightningkite.services.kfile.KFile
import com.lightningkite.services.terraform.*
import com.lightningkite.services.terraform.TerraformJsonObject.Companion.expression
import kotlinx.serialization.json.*
import software.amazon.awssdk.regions.Region
import java.io.File
import java.util.*


/**
 * Terraform builder for deploying Lightning Server to single AWS EC2.
 *
 * This generates Terraform configuration for:
 * - VPC with public/private subnets across multiple availability zones
 * - Application Load Balancer with HTTPS termination
 * - Auto Scaling Group with configurable instance counts
 * - CloudWatch logging and monitoring
 *
 * Example usage:
 * ```kotlin
 * object MyDeployment : TerraformAwsEc2Builder<Server>(Server) {
 *     override val storageBucket = "my-terraform-state"
 *     override val region = Region.US_WEST_2
 *     override val displayName = "My Server"
 *     override val domainZone = "example.com"
 *     override val domain = "api.example.com"
 *     override val mainClass = com.example.Main::class
 *     override val debug = false
 *     override val emergencyContact = EmailAddress("ops@example.com")
 *
 *     override fun Server.settings() {
 *         database.need.mongoDbAtlas(...)
 *         cache.need.awsElasticacheMemcached(...)
 *         files.need.awsS3(...)
 *     }
 * }
 * ```
 *
 * @param S The ServerBuilder type being deployed
 */
@Untested
public abstract class TerraformAwsSingleEc2Builder<S : ServerBuilder>(
    override val builder: S,
) : BaseTerraformEmitter<S>(), TerraformEmitterAws, TerraformEmitterAwsDomain {

    // === Identity & Storage ===

    /** S3 bucket for Terraform state storage. */
    public abstract val storageBucket: String

    /** AWS region for deployment. */
    public abstract val region: Region

    /** Human-readable display name for the deployment. */
    public abstract val displayName: String

    public override val deploymentTag: String get() = displayName
    public override val projectPrefix: String
        get() = displayName.lowercase().replace(" ", "-").filter { it.isLetterOrDigit() || it == '-' }
    public open val storageBucketPath: String get() = projectPrefix
    public open val storageEncryptionEnabled: Boolean get() = true

    override val terraformRoot: File get() = File("terraform/$projectPrefix")
    override val secretsSource: SecretSource by lazy {
        val fetcher = PasswordFetcher()
        ManySecretSources(
            EnvironmentSecretSource,
            EncryptedFileSecretSource(
                storageBucket.substringAfterLast('/') + "_" + projectPrefix,
                passwordFetcher = fetcher
            ),
            EncryptedFileSecretSource(storageBucket.substringAfterLast('/'), passwordFetcher = fetcher),
        )
    }

    // === Domain Configuration (required) ===

    /** Route53 hosted zone name (e.g., "example.com"). */
    public abstract val domainZone: String

    /** Domain name for the application (e.g., "api.example.com"). */
    public abstract override val domain: String
    public open val wsDomain: String get() = domain

    override val domainZoneId: String = expression("data.aws_route53_zone.main.zone_id")

    // === Application Configuration ===

    /** Gradle task that produces the application JAR (default: "shadowJar"). */
//    public open val jarTask: String get() = "shadowJar"

    /** Explicit path to JAR file. If null, uses build output from jarTask. */
    public open val distributionZipPath: String? get() = null

    /** JVM arguments for the application. */
    public open val jvmArgs: List<String> get() = listOf("-Xmx512m")

    /** Command to start the server (passed to main class). */
    public open val serverCommand: String get() = "serve"

    /** Whether this is a debug deployment. */
    public abstract val debug: Boolean

    /** Emergency contact email for alerts. */
    public abstract val emergencyContact: EmailAddress

    abstract override val applicationVpc: AwsVpc.EC2Safe

    // === Instance Configuration ===

    public enum class CPUArchitecture {
        X86,
        Arm,
    }

    /** EC2 instance type. eg: t2.medium, t4g.medium */
    public abstract val instanceType: String

    /**
     *  The CPU architecture of the instance. This is entirely dependent on the instanceType.
     *  There are too many Instance types in AWS to keep track of so we force the end user to set this value.
     *  */
    public abstract val instanceArchitecture: CPUArchitecture

    /** EBS volume size in GiB. */
    public open val volumeSizeGiB: Int get() = 20

    /** Max body size for any request. Enforced by Angie */
    public open val maxBodySize: DataSize = 10.mebibytes

    /** IP Stack Configuration */
    // Disabling IPv4 won't remove ALL IPv4 address from the vpc and instance. It will just not create an EIP and Domain registration for IPv4
    public open val enableIPv4: Boolean = true
    public open val enableIPv6: Boolean = true

    // sshAllowed with the related IP enabling is what will allow an ssh connection.
    public open val sshAllowedV4CIDR: List<String> =
        emptyList() // Example: 0.0.0.0/0, would allow everyone in the world to attempt to ssh in
    public open val sshAllowedV6CIDR: List<String> =
        emptyList() // Example: ::/0, would allow everyone in the world to attempt to ssh in

    // === Logging Configuration ===

    /** CloudWatch log retention in days. */
    public open val logRetentionDays: Int get() = 30

    // === Customization Hooks ===

    /** Additional packages to install on EC2 instances. */
    public open val additionalPackages: List<String> = emptyList()

    /** Additional ec2_init scripts to run after base setup. */
    public open val ec2InitScriptsRaw: List<String> = emptyList()
    public open val ec2InitScripts: List<KFile> = emptyList()

    /** Additional systemd environment variables. */
    public open val systemdEnvironment: Map<String, String> = emptyMap()

    public enum class FileType(public val basePath: String) {
        Config("/etc/lightning-server/"),
        Script("/usr/local/bin"),
    }

    /**
     * Additional files to place on EC2 instances. Key = File Name to FileType, Value = raw string content.
     * File Type will determine where the file gets placed. If the type is Config, it will be placed
     * in /etc/lightning-server/. If it is type Script it will be placed in /usr/local/bin. The file name
     * must be unique. If that file already exists it will not overwrite it.
     * */
    public val instanceFilesRaw: MutableMap<Pair<String, FileType>, String> = mutableMapOf()

    /**
     * Additional files to place on EC2 instances. Key = File Name to FileType, Value = KFile on your local system.
     * File Type will determine where the file gets placed. If the type is Config, it will be placed
     * in /etc/lightning-server/. If it is type Script it will be placed in /usr/local/bin. The file name
     * must be unique. If that file already exists it will not overwrite it.
     * */
    public val instanceFiles: MutableMap<Pair<String, FileType>, KFile> = mutableMapOf()

    // === Internal State ===

    override val additionalSettings: Set<ServerSetting<*, *>> = setOf(
        secretBasis,
        telemetrySettings,
        loggingSettings,
    )
    override val applicationRegion: String get() = region.id()
    override val policyStatements: MutableCollection<AwsPolicyStatement> = ArrayList()

    /** Whether to attach X-Ray write access policy. Set by OTel extensions. */
    public var attachXRayPolicy: Boolean = false


    override fun finalize() {

        if (!enableIPv6 && !enableIPv4) throw IllegalStateException("At least one IP stack must be enabled.")

        if (projectPrefix.any { !it.isLetterOrDigit() && !(it == '-' || it == '_') })
            throw IllegalArgumentException("The projectPrefix has illegal characters in it. It can only contain: Letters, Digits, '-', and '_'.")

        super.finalize()
        require(TerraformProviderImport.aws)
        require(
            TerraformProvider(
                TerraformProviderImport.aws,
                null,
                buildJsonObject { put("region", region.id()) })
        )
        require(TerraformProviderImport.archive)
        require(TerraformProviderImport.external)
        require(TerraformProviderImport.local)
        require(TerraformProviderImport.nullProvider)
        require(TerraformProviderImport.random)
        require(TerraformProviderImport.tls)

        // Fulfill general settings
        fulfillSetting(generalSettings.name, buildJsonObject {
            put("projectName", displayName)
            put("publicUrl", "https://$domain")
            put("wsUrl", "wss://$wsDomain")
            put("debug", debug)
            put("emergencyContact", emergencyContact.raw)
        })

        emitMainTerraformConfig()
        emitDeploymentResources()
        emitNetworkResources()
        (applicationVpc as? AwsVpc.TFManaged)?.also {
            emitVpc(it, enableIPv6)
        }
        if (enableIPv4)
            emitEIPResources()
        emitEc2Resources()
        emitDnsResources()
        emitMonitoringResources()
    }

    private fun emitDeploymentResources() {
        val emitter = this@TerraformAwsSingleEc2Builder

        // Add S3 permissions for EC2 instances to download JAR and Settings
        policyStatements += AwsPolicyStatement(
            action = listOf("s3:GetObject"),
            resource = listOf(
                $$"${aws_s3_bucket.deployment.arn}/*",
            )
        )

        // Add CloudWatch Logs permissions
        policyStatements += AwsPolicyStatement(
            action = listOf(
                "logs:CreateLogStream",
                "logs:PutLogEvents",
                "logs:DescribeLogStreams",
            ),
            resource = listOf($$"${aws_cloudwatch_log_group.application.arn}:*")
        )

        // Add SSM permissions
        policyStatements += AwsPolicyStatement(
            action = listOf(
                "ssm:GetParameter",
            ),
            resource = listOf(expression("aws_ssm_parameter.settings_password.arn"))
        )

        emit("deployment") {
            "data.external.jar_hash" {
                "program" - listOf(
                    "bash",
                    "-c",
                    $$"""f=$(ls "$${distributionZipPath ?: $$"${path.module}/../../build/distributions/server.zip"}" | head -1) && hash=$(sha256sum "$f" | cut -d' ' -f1) && printf '{"hash":"%s","path":"%s"}' "$hash" "$f"""",
                )
            }

            // S3 Bucket for deployment artifacts
            "resource.aws_s3_bucket.deployment" {
                "bucket_prefix" - "${projectPrefix.lowercase().replace("_", "")}-deploy"
                "force_destroy" - true
            }

            // Block public access to deployment bucket
            "resource.aws_s3_bucket_public_access_block.deployment" {
                "bucket" - expression("aws_s3_bucket.deployment.id")
                "block_public_acls" - true
                "block_public_policy" - true
                "ignore_public_acls" - true
                "restrict_public_buckets" - true
            }

            // Enable server-side encryption
            "resource.aws_s3_bucket_server_side_encryption_configuration.deployment" {
                "bucket" - expression("aws_s3_bucket.deployment.id")
                "rule" {
                    "apply_server_side_encryption_by_default" {
                        "sse_algorithm" - "AES256"
                    }
                }
            }

            // Enable versioning for deployment artifacts
            "resource.aws_s3_bucket_versioning.deployment" {
                "bucket" - expression("aws_s3_bucket.deployment.id")
                "versioning_configuration" {
                    "status" - "Enabled"
                }
            }

            // Version lifetimes for deployment artifacts
            "resource.aws_s3_bucket_lifecycle_configuration.deployment" {
                "bucket" - expression("aws_s3_bucket.deployment.id")
                "depends_on" - listOf("aws_s3_bucket_versioning.deployment")

                "rule" - listOf(
                    terraformJsonObject {
                        "id" - "expire-noncurrent-versions"
                        "status" - "Enabled"
                        "noncurrent_version_expiration" {
                            "noncurrent_days" - 30
                        }
                    },
                    terraformJsonObject {
                        "id" - "abort-incomplete-multipart-uploads"
                        "status" - "Enabled"
                        "abort_incomplete_multipart_upload" {
                            "days_after_initiation" - 7
                        }
                    },
                )
            }

            // IAM Role for EC2 instances
            "resource.aws_iam_role.ec2" {
                "name" - "$projectPrefix-ec2-role"
                "assume_role_policy" - Json.encodeToString(buildJsonObject {
                    put("Version", "2012-10-17")
                    putJsonArray("Statement") {
                        addJsonObject {
                            put("Action", "sts:AssumeRole")
                            put("Effect", "Allow")
                            put("Principal", buildJsonObject {
                                put("Service", "ec2.amazonaws.com")
                            })
                        }
                    }
                })
            }

            // IAM Instance Profile
            "resource.aws_iam_instance_profile.ec2" {
                "name" - "$projectPrefix-ec2-profile"
                "role" - expression("aws_iam_role.ec2.name")
            }


            // Services Access Policy
            "locals" {
                val j = Json { encodeDefaults = true; explicitNulls = false }
                "servicesAccessPolicy" - buildJsonObject {
                    put("Version", "2012-10-17")
                    put("Statement", j.encodeToJsonElement(emitter.policyStatements.toList()))
                }
            }
            "resource.aws_iam_policy.servicesAccess" {
                "name" - "$projectPrefix-servicesAccess"
                "path" - "/${projectPrefixPath}/servicesAccess/"
                "description" - "Access to $displayName services"
                "policy" - expression("jsonencode(local.servicesAccessPolicy)")
            }
            "resource.aws_iam_role_policy_attachment.servicesAccess" {
                "role" - expression("aws_iam_role.ec2.name")
                "policy_arn" - expression("aws_iam_policy.servicesAccess.arn")
            }

            // SSM for Session Manager access
            "resource.aws_iam_role_policy_attachment.ssm" {
                "role" - expression("aws_iam_role.ec2.name")
                "policy_arn" - "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
            }

            "resource.aws_iam_role_policy_attachment.cloudwatch_agent" {
                "policy_arn" - "arn:aws:iam::aws:policy/CloudWatchAgentServerPolicy"
                "role" - expression("aws_iam_role.ec2.name")
            }

            if (emitter.attachXRayPolicy) {
                "resource.aws_iam_role_policy_attachment.xray" {
                    "role" - expression("aws_iam_role.ec2.name")
                    "policy_arn" - "arn:aws:iam::aws:policy/AWSXRayDaemonWriteAccess"
                }
            }

            // Random password for settings encryption
            "resource.random_password.settings" {
                "length" - 32
                "special" - true
                "override_special" - "-_"
            }

            "resource.aws_ssm_parameter.settings_password" {
                "name" - "/$projectPrefix/settings-password"
                "type" - "SecureString"
                "value" - expression("random_password.settings.result")
                "description" - "AES-256 passphrase used to encrypt/decrypt the $displayName settings bundle"
            }

            // Settings file (raw)
            "locals" {
                "settings_raw" - JsonObject(settings)
            }
            "resource.local_sensitive_file.settings_raw" {
                "content" - expression("jsonencode(local.settings_raw)")
                "filename" - $$"${path.module}/build/raw-settings.json"
            }

            // Encrypt settings using PBKDF2 for stronger key derivation
            "resource.null_resource.encrypt_settings" {
                "triggers" {
                    "settings_hash" - expression("local_sensitive_file.settings_raw.content_sha256")
                }
                "provisioner.local-exec" {
                    "command" - $$"openssl enc -aes-256-cbc -pbkdf2 -iter 100000 -md sha256 -in \"${local_sensitive_file.settings_raw.filename}\" -out \"${path.module}/build/settings.enc\" -pass pass:${random_password.settings.result}"
                }
                "depends_on" - listOf("local_sensitive_file.settings_raw")
            }

            // Upload JAR to S3
            "resource.null_resource.upload_jar" {
                "triggers" {
                    "jar_hash" - expression("data.external.jar_hash.result.hash")
                }

                "provisioner.local-exec" {
                    "command" - $$"""aws s3 cp "${data.external.jar_hash.result.path}" s3://${aws_s3_bucket.deployment.id}/server.zip --region $${emitter.applicationRegion}"""
                }
                "depends_on" - listOf("aws_s3_bucket.deployment")
            }

            // Upload encrypted settings to S3
            "resource.null_resource.upload_settings" {
                "triggers" {
                    "settings_hash" - expression("local_sensitive_file.settings_raw.content_sha256")
                }
                "provisioner.local-exec" {
                    "command" - $$"aws s3 cp \"${path.module}/build/settings.enc\" s3://${aws_s3_bucket.deployment.id}/settings.enc --region $${emitter.applicationRegion}"
                }
                "depends_on" - listOf("null_resource.encrypt_settings", "aws_s3_bucket.deployment")
            }

            // TLS key pair for SSH (optional, for debugging)
            "resource.tls_private_key.ec2" {
                "algorithm" - "RSA"
                "rsa_bits" - 4096
            }
            "resource.aws_key_pair.ec2" {
                "key_name" - "$projectPrefix-key"
                "public_key" - expression("tls_private_key.ec2.public_key_openssh")
            }
            "resource.local_sensitive_file.private_key" {
                "content" - expression("tls_private_key.ec2.private_key_pem")
                "filename" - $$"${path.module}/build/$$projectPrefix-key.pem"
                "file_permission" - "0600"
            }
        }
    }

    private fun emitEc2Resources() {

        emit("redeploy") {
            emitExtra("redeploy.sh", redeployScript())
            "resource.null_resource.redeploy_app" {
                "triggers" {
                    "jar_hash" - expression("data.external.jar_hash.result.hash")
                    "settings_hash" - expression("local_sensitive_file.settings_raw.content_sha256")
                }

                "depends_on" - listOfNotNull(
                    "null_resource.upload_jar",
                    "null_resource.upload_settings",
                    "aws_instance.ubuntu",
                    "aws_iam_role_policy_attachment.ssm",
                    "aws_iam_role_policy_attachment.servicesAccess",
                    if (enableIPv4) "aws_eip_association.main" else null,
                    "aws_ssm_parameter.settings_password",
                )
                "provisioner" {
                    "local-exec" {
                        "command" - $$"bash ${path.module}/redeploy.sh ${aws_instance.ubuntu.id} $$applicationRegion"
                    }
                }
            }
        }

        emit("ec2") {
            // Get latest Amazon Linux 2023 AMI
            "data.aws_ami.ubuntu" {
                "most_recent" - true
                "owners" - listOf("099720109477")

                "filter" - listOf(
                    terraformJsonObject {
                        "name" - "name"
                        "values" - listOf("ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-${if (instanceArchitecture == CPUArchitecture.Arm) "arm64" else "amd64"}-server-*")
                    },
                    terraformJsonObject {
                        "name" - "architecture"
                        "values" - listOf(if (instanceArchitecture == CPUArchitecture.Arm) "arm64" else "x86_64")
                    },
                    terraformJsonObject {
                        "name" - "virtualization-type"
                        "values" - listOf("hvm")
                    }
                )
            }

            // EC2 Instance
            "resource.aws_instance.ubuntu" {
                "ami" - expression("data.aws_ami.ubuntu.id")
                "instance_type" - instanceType
                "iam_instance_profile" - expression("aws_iam_instance_profile.ec2.name")
                "key_name" - expression("aws_key_pair.ec2.key_name")
                "vpc_security_group_ids" - listOfNotNull(
                    expression("aws_security_group.ec2.id"),
                    (applicationVpc as? AwsVpc.VpcInfo)?.securityGroup,
                )
                when (val vpcInfo = applicationVpc) {
                    AwsVpc.Default -> {
                        "subnet_id" - expression("data.aws_subnets.main.ids[0]")
                    }

                    is AwsVpc.VpcInfo -> {
                        "subnet_id" - vpcInfo.applicationSubnet
                    }
                }

                if (enableIPv6) {
                    "ipv6_address_count" - 1
                    "enable_primary_ipv6" - true
                }

                "user_data" - expression("local.ec2_init")
                "user_data_replace_on_change" - true

                "root_block_device" {
                    "volume_size" - volumeSizeGiB
                    "volume_type" - "gp3"
                    "encrypted" - true
                    "delete_on_termination" - true
                    "tags" {
                        "Name" - "${projectPrefix}-root"
                    }
                }

                "tags" {
                    "Name" - displayName
                }

                "depends_on" - listOf(
                    "null_resource.upload_jar",
                    "null_resource.upload_settings",
                    "aws_ssm_parameter.settings_password",
                    "aws_iam_role_policy_attachment.servicesAccess",
                    "aws_iam_role_policy_attachment.ssm",
                    "aws_iam_role_policy_attachment.cloudwatch_agent",
                    "aws_s3_bucket_server_side_encryption_configuration.deployment",
                )
            }

            // User data script
            "locals" {
                emitExtra("ec2_init.sh", generateEC2Init())
                "ec2_init" - $$"""${templatefile("${path.module}/ec2_init.sh", { deployment_bucket = aws_s3_bucket.deployment.id })}"""
            }
        }
    }


    private fun emitDnsResources() {
        emit("dns") {
            // Route53 Zone Data
            "data.aws_route53_zone.main" {
                "name" - domainZone
            }

            // A Record pointing to EC2
            if (enableIPv4)
                "resource.aws_route53_record.main" {
                    "zone_id" - domainZoneId
                    "name" - domain
                    "type" - "A"
                    "ttl" - 300
                    "records" - listOf(expression("aws_eip.main.public_ip"))
                }
            if (enableIPv6)
                "resource.aws_route53_record.main_v6" {
                    "zone_id" - domainZoneId
                    "name" - domain
                    "type" - "AAAA"
                    "ttl" - 300
                    "records" - listOf(expression("aws_instance.ubuntu.ipv6_addresses[0]"))
                }


            if (wsDomain != domain) {
                // A Record pointing to EC2 for wsDomain
                if (enableIPv4)
                    "resource.aws_route53_record.ws_main" {
                        "zone_id" - domainZoneId
                        "name" - wsDomain
                        "type" - "A"
                        "ttl" - 300
                        "records" - listOf(expression("aws_eip.main.public_ip"))
                    }
                if (enableIPv6)
                    "resource.aws_route53_record.ws_main_v6" {
                        "zone_id" - domainZoneId
                        "name" - wsDomain
                        "type" - "AAAA"
                        "ttl" - 300
                        "records" - listOf(expression("aws_instance.ubuntu.ipv6_addresses"))
                    }
            }
        }
    }

    private fun emitMonitoringResources() {
        emit("monitoring") {
            // CloudWatch Log Group for application logs
            "resource.aws_cloudwatch_log_group.application" {
                "name" - "/ec2/$projectPrefix/application"
                "retention_in_days" - logRetentionDays
            }

            // SNS Topic for alarms
            "resource.aws_sns_topic.emergency" {
                "name" - "${projectPrefix}_emergencies"
            }
            "resource.aws_sns_topic_subscription.emergency_primary" {
                "topic_arn" - expression("aws_sns_topic.emergency.arn")
                "protocol" - "email"
                "endpoint" - emergencyContact.raw
            }

            // System Status Check Failure
            "resource.aws_cloudwatch_metric_alarm.system_status_check" {
                "alarm_name" - "$projectPrefix-system-status-check-failed"
                "alarm_description" - "EC2 system status check failing"
                "namespace" - "AWS/EC2"
                "metric_name" - "StatusCheckFailed_System"
                "statistic" - "Maximum"
                "period" - 60
                "evaluation_periods" - 2
                "threshold" - 0
                "comparison_operator" - "GreaterThanThreshold"
                "treat_missing_data" - "breaching"

                "dimensions" {
                    "InstanceId" - expression("aws_instance.ubuntu.id")
                }

                "alarm_actions" - listOf(expression("aws_sns_topic.emergency.arn"))
            }

            // High CPU Alarm
            "resource.aws_cloudwatch_metric_alarm.high_cpu" {
                "alarm_name" - "$projectPrefix-high-cpu"
                "comparison_operator" - "GreaterThanThreshold"
                "evaluation_periods" - 3
                "metric_name" - "CPUUtilization"
                "namespace" - "AWS/EC2"
                "period" - 300
                "statistic" - "Average"
                "threshold" - 90
                "alarm_description" - "High CPU utilization"

                "dimensions" {
                    "InstanceId" - expression("aws_instance.ubuntu.id")
                }

                "alarm_actions" - listOf(expression("aws_sns_topic.emergency.arn"))
            }

            "resource.aws_cloudwatch_metric_alarm.high_memory" {
                "alarm_name" - "$projectPrefix-high-memory"
                "alarm_description" - "High memory utilization (CWAgent mem_used_percent)"
                "namespace" - "CWAgent"
                "metric_name" - "mem_used_percent"
                "statistic" - "Average"
                "period" - 300
                "evaluation_periods" - 3
                "threshold" - 90
                "comparison_operator" - "GreaterThanThreshold"

                "dimensions" {
                    "InstanceId" - expression("aws_instance.ubuntu.id")
                }

                "alarm_actions" - listOf(expression("aws_sns_topic.emergency.arn"))
            }

            "resource.aws_cloudwatch_metric_alarm.high_disk" {
                "alarm_name" - "$projectPrefix-high-disk"
                "alarm_description" - "High root-volume disk utilization (CWAgent disk_used_percent)"
                "namespace" - "CWAgent"
                "metric_name" - "disk_used_percent"
                "statistic" - "Average"
                "period" - 300
                "evaluation_periods" - 3
                "threshold" - 85
                "comparison_operator" - "GreaterThanThreshold"

                "dimensions" {
                    "InstanceId" - expression("aws_instance.ubuntu.id")
                }

                "alarm_actions" - listOf(expression("aws_sns_topic.emergency.arn"))
            }
        }

        // Outputs
        emit("outputs") {
            "output.application_url" {
                "description" - "Application URL"
                "value" - "https://$domain"
            }
        }
    }

    private fun emitMainTerraformConfig() {
        emit("main") {
            "terraform" {
                "required_providers" {
                    terraformProviderImports
                        .distinct()
                        .map { it.toTerraformJson() }
                        .forEach { include(it) }
                }
                "required_version" - "~> 1.0"
                "backend.s3" {
                    "bucket" - storageBucket
                    "key" - storageBucketPath
                    "region" - applicationRegion
                    "encrypt" - storageEncryptionEnabled
                }
            }
            if (terraformProviders.isNotEmpty()) {
                include(terraformProviders)
            }
        }
    }

    private fun emitEIPResources() {
        emit("eip") {
            "resource.aws_eip.main" {
                "domain" - "vpc"
                "tags" {
                    "Name" - displayName
                }
            }

            "resource.aws_eip_association.main" {
                "instance_id" - expression("aws_instance.ubuntu.id")
                "allocation_id" - expression("aws_eip.main.id")
            }
        }
    }

    private fun emitNetworkResources() {
        emit("network") {
            when (val vpcInfo = applicationVpc) {
                AwsVpc.Default -> {

                    "data.aws_vpc.default" {
                        "default" - true
                    }

                    "data.aws_subnets.main" {
                        "filter" {
                            "name" - "vpc-id"
                            "values" - listOf(expression("data.aws_vpc.default.id"))
                        }
                    }

                    "resource.aws_security_group.ec2" {
                        "name" - "$projectPrefix-ec2"
                        "description" - "$displayName single-instance access"
                        "vpc_id" - expression("data.aws_vpc.default.id")
                    }
                }

                is AwsVpc.VpcInfo -> {

                    "resource.aws_security_group.ec2" {
                        "name" - "$projectPrefix-ec2"
                        "description" - "$displayName single-instance access"
                        "vpc_id" - vpcInfo.id
                    }
                }
            }


            "resource.aws_vpc_security_group_egress_rule.access_outside" {
                "security_group_id" - expression("aws_security_group.ec2.id")
                "ip_protocol" - -1
                "cidr_ipv4" - "0.0.0.0/0"
            }

            if (enableIPv4) {
                "resource.aws_vpc_security_group_ingress_rule.http" {
                    "security_group_id" - expression("aws_security_group.ec2.id")
                    "ip_protocol" - "tcp"
                    "from_port" - 80
                    "to_port" - 80
                    "cidr_ipv4" - "0.0.0.0/0"
                }
                "resource.aws_vpc_security_group_ingress_rule.https" {
                    "security_group_id" - expression("aws_security_group.ec2.id")
                    "ip_protocol" - "tcp"
                    "from_port" - 443
                    "to_port" - 443
                    "cidr_ipv4" - "0.0.0.0/0"
                }
                val v4CIDRs = sshAllowedV4CIDR.joinToString { "\"$it\"" }
                if (v4CIDRs.isNotBlank())
                    "resource.aws_vpc_security_group_ingress_rule.ssh" {
                        "for_each" - expression(
                            $$"""toset([$${v4CIDRs}])"""
                        )
                        "security_group_id" - expression("aws_security_group.ec2.id")
                        "ip_protocol" - "tcp"
                        "from_port" - 22
                        "to_port" - 22
                        "cidr_ipv4" - expression("each.key")
                    }
            }

            if (enableIPv6) {
                "resource.aws_vpc_security_group_egress_rule.access_outside_v6" {
                    "security_group_id" - expression("aws_security_group.ec2.id")
                    "ip_protocol" - -1
                    "cidr_ipv6" - "::/0"
                }

                "resource.aws_vpc_security_group_ingress_rule.http_v6" {
                    "security_group_id" - expression("aws_security_group.ec2.id")
                    "ip_protocol" - "tcp"
                    "from_port" - 80
                    "to_port" - 80
                    "cidr_ipv6" - "::/0"
                }

                "resource.aws_vpc_security_group_ingress_rule.https_v6" {
                    "security_group_id" - expression("aws_security_group.ec2.id")
                    "ip_protocol" - "tcp"
                    "from_port" - 443
                    "to_port" - 443
                    "cidr_ipv6" - "::/0"
                }
                val v6CIDRs = sshAllowedV6CIDR.joinToString { "\"$it\"" }
                if (v6CIDRs.isNotBlank())
                    "resource.aws_vpc_security_group_ingress_rule.sshv6" {
                        "for_each" - expression(
                            $$"""toset([$${v6CIDRs}])"""
                        )
                        "security_group_id" - expression("aws_security_group.ec2.id")
                        "ip_protocol" - "tcp"
                        "from_port" - 22
                        "to_port" - 22
                        "cidr_ipv6" - expression("each.key")
                    }
            }
        }
    }

    private fun generateEC2Init(): String {
        val emitter = this@TerraformAwsSingleEc2Builder

        // Validate inputs to prevent shell injection
        validateCustomInputs()

        // language="Shell Script"
        return buildString {
            appendLine(
                """#!/bin/bash

set -euo pipefail

# === Logging Setup ===
mkdir -p /var/log/$projectPrefix
touch /var/log/$projectPrefix/ec2_init.log
exec > >(tee /var/log/$projectPrefix/ec2_init.log | logger -t ec2_init -s) 2>&1

BOOT_START=$(date +%s)
echo "[INFO] EC2 Init script started at $(date)"

# === System update + base packages ===
echo "[INFO] Updating system packages..."
export DEBIAN_FRONTEND=noninteractive
apt update -y
apt install -y openjdk-17-jre-headless openssl curl gnupg ca-certificates unzip
"""
            )

            // Additional packages - each validated
            if (additionalPackages.isNotEmpty()) {
                appendLine("# === Additional Packages ===")
                appendLine("echo \"[INFO] Installing additional packages at \$(date)\"")
                appendLine("apt install -y ${additionalPackages.joinToString(" ") { it.shellEscape() }}")
                appendLine()
            }

            cloudwatchAgent(emitter.applicationRegion)

            awsCli()

            angieInstall()

            ssm()

            systemD()

            instanceRedeployScript()

            if (instanceFiles.isNotEmpty() || instanceFilesRaw.isNotEmpty())
                instanceFiles()

            // Custom ec2_init scripts
            if (ec2InitScripts.isNotEmpty() || ec2InitScriptsRaw.isNotEmpty()) {
                appendLine("echo \"[INFO] Running Custom EC2 Init Scripts at \$(date)\"")
                appendLine("# === Custom ec2_init Scripts ===")
                for (script in ec2InitScripts) {
                    appendLine(script.readString())
                    appendLine()
                }
                for (script in ec2InitScriptsRaw) {
                    appendLine(script)
                    appendLine()
                }
            }


            // language="Shell Script"
            appendLine(
                $$"""
systemctl enable $$projectPrefix

# First-time deploy uses the same script that subsequent SSM-driven redeploys
# will use, so there's exactly one code path that knows how to deploy the server.
echo "[INFO] Running first-time application deploy..."
/usr/local/bin/lightning-server-redeploy

# === Start Services ===
echo "[INFO] Starting services..."
systemctl daemon-reload

systemctl enable angie
systemctl restart angie

# Verify CloudWatch agent started (non-fatal if it fails)
if ! systemctl is-active --quiet amazon-cloudwatch-agent; then
    echo "[WARN] CloudWatch agent failed to start. Logs may not be collected."
    echo "[WARN] Check: journalctl -u amazon-cloudwatch-agent"
fi

BOOT_END=$(date +%s)
BOOT_DURATION=$((BOOT_END - BOOT_START))
echo "[INFO] EC2 Init script completed in $BOOT_DURATION seconds"
"""
            )
        }
    }

    private fun StringBuilder.instanceFiles() {

        // language="Shell Script"
        appendLine("# === Copying in additional files ===")
        // language="Shell Script"
        appendLine("""echo "[INFO] Copying in additional files at $(date)"""")
        // language="Shell Script"
        appendLine("""mkdir -p ${FileType.Config.basePath}""")

        fun writeOutput(path: String, type: FileType, base64Content: String) {
            // language="Shell Script"
            appendLine(
                """# Write file: $path
[ ! -e ${path.shellEscape()} ] && echo $base64Content | base64 -d > ${path.shellEscape()} || true"""
            )

            if (type == FileType.Script) {
                // language="Shell Script"
                appendLine("chmod +x ${path.shellEscape()}")
            }

            appendLine()
        }

        // Instance files - using base64 encoding to prevent heredoc injection
        for ((key, content) in instanceFilesRaw) {
            val base64Content = Base64.getEncoder().encodeToString(content.toByteArray())
            writeOutput("${key.second.basePath}/${key.first}", key.second, base64Content)

        }
        for ((key, file) in instanceFiles) {
            val base64Content = Base64.getEncoder().encodeToString(file.readByteArray())
            writeOutput("${key.second.basePath}/${key.first}", key.second, base64Content)
        }
    }

    private fun StringBuilder.ssm() {

        // language="Shell Script"
        appendLine(
            $$"""
# === SSM Agent ===
echo "[INFO] Ensuring SSM agent is installed and running at $(date)"
# Required so terraform can drive in-place jar/settings redeploys via
# `aws ssm send-command` (see redeploy.sh / null_resource.redeploy_app).
# Ubuntu 24.04 ships amazon-ssm-agent as a snap; install if missing,
# then enable + start so newly-created instances register with SSM.
if ! snap list amazon-ssm-agent >/dev/null 2>&1; then
    snap install amazon-ssm-agent --classic
    systemctl enable snap.amazon-ssm-agent.amazon-ssm-agent.service
    systemctl start snap.amazon-ssm-agent.amazon-ssm-agent.service
fi
"""
        )
    }

    private fun StringBuilder.cloudwatchAgent(applicationRegion: String) {

        // language="Shell Script"
        appendLine(
            $$"""
# === Install CloudWatch Agent ===
echo "[INFO] Installing CloudWatch Agent at $(date)"
curl $${
                if (instanceArchitecture == CPUArchitecture.Arm)
                    "https://amazoncloudwatch-agent-${applicationRegion}.s3.${applicationRegion}.amazonaws.com/ubuntu/arm64/latest/amazon-cloudwatch-agent.deb"
                else
                    "https://amazoncloudwatch-agent-${applicationRegion}.s3.${applicationRegion}.amazonaws.com/ubuntu/amd64/latest/amazon-cloudwatch-agent.deb"
            } -o cw_agent.deb
dpkg -i cw_agent.deb
rm -f cw_agent.deb

# === CloudWatch Agent Configuration ===
echo "[INFO] Creating amazon-cloudwatch-agent.json for CloudWatch $(date)"
cat > /opt/aws/amazon-cloudwatch-agent/etc/amazon-cloudwatch-agent.json << EOF
{
  "logs": {
    "logs_collected": {
      "files": {
        "collect_list": [
          {
            "file_path": "/var/log/$$projectPrefix/ec2_init.log",
            "log_group_name": "/ec2/$$projectPrefix/application",
            "log_stream_name": "{instance_id}/ec2_init"
          },
          {
            "file_path": "/var/log/$$projectPrefix/server.log",
            "log_group_name": "/ec2/$$projectPrefix/application",
            "log_stream_name": "{instance_id}/server"
          },
          {
            "file_path": "/var/log/$$projectPrefix/redeploy.log",
            "log_group_name": "/ec2/$$projectPrefix/application",
            "log_stream_name": "{instance_id}/redeploy"
          }
        ]
      }
    }
  },
  "metrics": {
    "metrics_collected": {
      "mem": {
        "measurement": ["mem_used_percent"]
      },
      "disk": {
        "measurement": ["disk_used_percent"],
        "resources": ["/"]
      }
    }
  }
}
EOF

systemctl enable amazon-cloudwatch-agent
systemctl restart amazon-cloudwatch-agent  
"""
        )
    }

    private fun StringBuilder.awsCli() {

        // language="Shell Script"
        appendLine(
            $$"""
# === Install AWS CLI v2 ===
echo "[INFO] Installing AWS CLI V2 at $(date)"
curl $${
                if (instanceArchitecture == CPUArchitecture.Arm)
                    "https://awscli.amazonaws.com/awscli-exe-linux-aarch64.zip"
                else
                    "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip"
            } -o "awscliv2.zip"
unzip -q awscliv2.zip
./aws/install
rm -rf aws/ awscliv2.zip
"""
        )
    }

    private fun StringBuilder.systemD() {

        // language="Shell Script"
        appendLine(
            $$"""
# === Systemd Service ===
echo "[INFO] Creating Systemd unit for $$displayName"
cat > /etc/systemd/system/$$projectPrefix.service << 'UNIT_EOF'
[Unit]
Description=$${displayName}
After=network.target
Requires=network.target

[Service]
Type=simple
User=ubuntu
WorkingDirectory=/opt/lightning-server
ExecStart=/opt/lightning-server/server/bin/server $$serverCommand

Restart=always
RestartSec=5
StandardOutput=append:/var/log/$$projectPrefix/server.log
StandardError=append:/var/log/$$projectPrefix/server.log

$${if (jvmArgs.isNotEmpty()) "Environment=JAVA_OPTS=${jvmArgs.joinToString(" ")}" else ""}
$${systemdEnvironment.entries.joinToString("\n") { (key, value) -> "Environment=$key=${value.systemdEscape()}" }}
       
[Install]
WantedBy=multi-user.target
UNIT_EOF

# === Server log file ===
echo "[INFO] Preparing server log directory"
mkdir -p /var/log/$$projectPrefix
touch /var/log/$$projectPrefix/server.log
chown -R ubuntu:ubuntu /var/log/$$projectPrefix

cat > /etc/logrotate.d/$$projectPrefix << 'LOGROTATE_EOF'
/var/log/$$projectPrefix/*.log {
    daily
    rotate 7
    compress
    delaycompress
    missingok
    notifempty
    copytruncate
    su ubuntu ubuntu
}
LOGROTATE_EOF
"""
        )
    }

    private fun StringBuilder.angieInstall() {

        fun outputServer(domain: String, includeUpgrade: Boolean): String = $$"""
server {
    listen 80;
    listen [::]:80;
    server_name $$domain;

    location ~ ^.*$ {
        add_header  X-Robots-Tag "noindex, nofollow, nosnippet, noarchive" always;
        rewrite ^(.*) https://$$domain$1 permanent;
    }
}

server {
    server_name $$domain;
    listen 443 ssl;
    listen [::]:443 ssl;
    http2 on;

    acme letsencrypt;
    ssl_certificate     $acme_cert_letsencrypt;
    ssl_certificate_key $acme_cert_key_letsencrypt;

    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305;
    ssl_prefer_server_ciphers off;
    ssl_session_cache shared:SSL:10m;
    ssl_session_timeout 1d;
    ssl_session_tickets off;

    add_header Strict-Transport-Security "max-age=63072000" always;
    add_header  X-Robots-Tag "noindex, nofollow, nosnippet, noarchive" always;
    server_tokens off;
    
    client_max_body_size $${maxBodySize.decimalMegabytes.toUInt()}M;
    
    location / {
        proxy_pass http://app;
        proxy_http_version 1.1;
        
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Host $server_name;
        proxy_set_header X-Real-IP $remote_addr;
$${
            if (includeUpgrade)
                $$"""
        proxy_set_header Upgrade    $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
"""
            else
                ""
        }
    }
}
"""

        // language="Shell Script"
        appendLine(
            $$"""
# === Install Angie + ACME module ===
echo "[INFO] Installing Angie $(date)"
curl -o /etc/apt/trusted.gpg.d/angie-signing.gpg \
            https://angie.software/keys/angie-signing.gpg
echo "deb https://download.angie.software/angie/$(. /etc/os-release && echo "$ID/$VERSION_ID $VERSION_CODENAME") main" \
    | sudo tee /etc/apt/sources.list.d/angie.list > /dev/null
apt-get update -y
apt-get install -y angie

# === Angie config (built-in ACME client handles cert issuance + renewal) ===
echo "[INFO] Creating server.conf for Angie $(date)"
rm -f /etc/angie/http.d/default.conf
cat > /etc/angie/http.d/server.conf << 'NGX_EOF'
resolver 8.8.8.8 1.1.1.1 ipv6=off;
resolver_timeout 5s;

map $http_upgrade $connection_upgrade {
    default   upgrade;
    ''        '';
}

upstream app {
    server 127.0.0.1:8080;
    keepalive 32;
    keepalive_timeout 45s;
    keepalive_requests 1000;
}

acme_client letsencrypt https://acme-v02.api.letsencrypt.org/directory;
$${outputServer(domain, wsDomain == domain)}
$${if (wsDomain != domain) outputServer(domain, true) else ""} 
NGX_EOF
"""
        )
    }


    // language="Shell Script"
    private fun StringBuilder.instanceRedeployScript() {

        // language="Shell Script"
        appendLine(
            $$"""
# === Lightning Server Redeploy Script ===
echo "[INFO] Creating Lightning Server Redeploy Script"
cat > /usr/local/bin/lightning-server-redeploy << 'REDEPLOY_EOF'
#!/bin/bash
set -eu pipefail

log() { echo "[lightning-server-redeploy] $*"; }
err() { echo "[lightning-server-redeploy] ERROR: $*" >&2; }

BUCKET="${deployment_bucket}"
REGION="$$applicationRegion"
SSM_PARAM="/$$projectPrefix/settings-password"
APP_DIR="/opt/lightning-server"
LOG_FILE="/var/log/$$projectPrefix/redeploy.log"

mkdir -p "$(dirname "$LOG_FILE")"
touch "$LOG_FILE"
chown ubuntu:ubuntu "$LOG_FILE"
exec > >(tee -a "$LOG_FILE" | logger -t lightning-server-redeploy -s) 2>&1

log "Redeploy started at $(date)"
mkdir -p "$APP_DIR"

download_with_retry() {
    local src="$1" dst="$2" max=5 attempt=1
    while [ $attempt -le $max ]; do
        log "Downloading $src (attempt $attempt/$max)"
        if aws s3 cp "$src" "$dst" --region "$REGION" --no-progress; then return 0; fi
        sleep $((attempt * 5))
        attempt=$((attempt + 1))
    done
    err "Failed to download $src after $max attempts"
    return 1
}

download_with_retry "s3://$BUCKET/server.zip" "$APP_DIR/server.zip"
[ -d "$APP_DIR/server-old" ] && rm -rf "$APP_DIR/server-old"
[ -d "$APP_DIR/server" ] && mv "$APP_DIR/server" "$APP_DIR/server-old"
log "Successful Download"
log "Unziping server.zip"
unzip -q "$APP_DIR/server.zip" -d $APP_DIR

download_with_retry "s3://$BUCKET/settings.enc" "$APP_DIR/settings.enc"
SETTINGS_PASS=$(aws ssm get-parameter --name "$SSM_PARAM" --with-decryption --query Parameter.Value --output text --region "$REGION")
if ! openssl enc -d -aes-256-cbc -pbkdf2 -iter 100000 -md sha256 \
    -in "$APP_DIR/settings.enc" -out "$APP_DIR/settings.json.new" -pass pass:"$SETTINGS_PASS"; then
    err "Failed to decrypt settings"
    exit 1
fi
log "Successfully decrypted settings"
[ -f "$APP_DIR/settings.json.old" ] && rm "$APP_DIR/settings.json.old"
[ -f "$APP_DIR/settings.json" ] && mv "$APP_DIR/settings.json" "$APP_DIR/settings.json.old"
mv "$APP_DIR/settings.json.new" "$APP_DIR/settings.json"
rm -f "$APP_DIR/settings.enc"

chown -R ubuntu:ubuntu "$APP_DIR"
chmod 600 "$APP_DIR/settings.json"

log "Restarting $$displayName"
systemctl restart $$projectPrefix
sleep 5
if ! systemctl is-active --quiet $$projectPrefix; then
    err "Service failed to start"
    tail -n 100 /var/log/$$projectPrefix/server.log >&2 || true
    exit 1
fi
log "Done"
REDEPLOY_EOF

chmod +x /usr/local/bin/lightning-server-redeploy
echo "[INFO] Creating Lightning Server Redeploy Script - DONE"
"""
        )
    }


    // language="Shell Script"
    private fun redeployScript(): String = $$"""
#!/usr/bin/env bash
# Triggered by null_resource.redeploy_app when the jar or settings hash
# changes. Tells the running EC2 instance to run /usr/local/bin/lightning-server-redeploy
# (installed by ec2_init.sh) via SSM Run Command. The on-instance script does
# the actual stop/fetch/decrypt/start work.
set -euo pipefail

INSTANCE_ID="${1:?usage: redeploy.sh <instance-id> [region]}"
REGION="${2:-us-west-2}"

log() { echo "[redeploy] $*"; }
err() { echo "[redeploy] ERROR: $*" >&2; }

log "Waiting for SSM agent on $INSTANCE_ID..."
for i in $(seq 1 60); do
    status=$(aws ssm describe-instance-information \
        --filters "Key=InstanceIds,Values=$INSTANCE_ID" \
        --region "$REGION" \
        --query 'InstanceInformationList[0].PingStatus' \
        --output text 2>/dev/null || echo "None")
    if [ "$status" = "Online" ]; then
        log "SSM agent online"
        break
    fi
    if [ "$i" = "60" ]; then
        err "SSM agent never reported Online for $INSTANCE_ID (last status: $status)"
        exit 1
    fi
    sleep 5
done

# Wait for cloud-init (ec2_init.sh) to finish so the redeploy script and
# systemd unit are guaranteed to be installed. On first apply this blocks
# until ec2_init completes; on subsequent applies it returns immediately.
log "Waiting for cloud-init to complete on $INSTANCE_ID..."
WAIT_CMD_ID=$(aws ssm send-command \
    --instance-ids "$INSTANCE_ID" \
    --document-name "AWS-RunShellScript" \
    --comment "terraform redeploy: wait for cloud-init" \
    --parameters 'commands=["cloud-init status --wait"],executionTimeout=900' \
    --region "$REGION" \
    --query 'Command.CommandId' \
    --output text)

for i in $(seq 1 180); do
    status=$(aws ssm get-command-invocation \
        --command-id "$WAIT_CMD_ID" \
        --instance-id "$INSTANCE_ID" \
        --region "$REGION" \
        --query 'Status' \
        --output text 2>/dev/null || echo "Pending")
    case "$status" in
        Success)
            log "cloud-init complete"
            break
            ;;
        Cancelled|Failed|TimedOut)
            err "cloud-init wait finished with status: $status"
            aws ssm get-command-invocation \
                --command-id "$WAIT_CMD_ID" \
                --instance-id "$INSTANCE_ID" \
                --region "$REGION" \
                --query 'StandardErrorContent' \
                --output text >&2 || true
            exit 1
            ;;
        *)
            sleep 5
            ;;
    esac
    if [ "$i" = "180" ]; then
        err "cloud-init did not complete within polling window"
        exit 1
    fi
done

log "Sending SSM command to run /usr/local/bin/lightning-server-redeploy..."
CMD_ID=$(aws ssm send-command \
    --instance-ids "$INSTANCE_ID" \
    --document-name "AWS-RunShellScript" \
    --comment "terraform redeploy: jar/settings refresh" \
    --parameters 'commands=/usr/local/bin/lightning-server-redeploy,executionTimeout=600' \
    --region "$REGION" \
    --query 'Command.CommandId' \
    --output text)

log "Command ID: $CMD_ID — polling for completion..."
for i in $(seq 1 180); do
    status=$(aws ssm get-command-invocation \
        --command-id "$CMD_ID" \
        --instance-id "$INSTANCE_ID" \
        --region "$REGION" \
        --query 'Status' \
        --output text 2>/dev/null || echo "Pending")
    case "$status" in
        Success)
            log "Command succeeded"
            aws ssm get-command-invocation \
                --command-id "$CMD_ID" \
                --instance-id "$INSTANCE_ID" \
                --region "$REGION" \
                --query 'StandardOutputContent' \
                --output text
            exit 0
            ;;
        Cancelled|Failed|TimedOut)
            err "SSM command finished with status: $status"
            echo "--- stdout ---" >&2
            aws ssm get-command-invocation \
                --command-id "$CMD_ID" \
                --instance-id "$INSTANCE_ID" \
                --region "$REGION" \
                --query 'StandardOutputContent' \
                --output text >&2 || true
            echo "--- stderr ---" >&2
            aws ssm get-command-invocation \
                --command-id "$CMD_ID" \
                --instance-id "$INSTANCE_ID" \
                --region "$REGION" \
                --query 'StandardErrorContent' \
                --output text >&2 || true
            exit 1
            ;;
        *)
            sleep 5
            ;;
    esac
done

err "SSM command did not finish within polling window"
exit 1
    """.trimIndent()

    /**
     * Validates all user-provided inputs that will be used in the ec2_init script
     * to prevent shell injection and path traversal attacks.
     */
    private fun validateCustomInputs() {
        // Validate package names (alphanumeric, hyphens, underscores, dots only)
        val packageNameRegex = Regex("^[a-zA-Z0-9._-]+$")
        for (pkg in additionalPackages) {
            require(packageNameRegex.matches(pkg)) {
                "Invalid package name '$pkg': must contain only alphanumeric characters, dots, hyphens, and underscores"
            }
        }

        // Validate JVM arguments (no shell metacharacters that could cause injection)
        val dangerousChars = setOf('`', '$', '|', ';', '&', '>', '<', '\n', '\r')
        for (arg in jvmArgs) {
            require(arg.none { it in dangerousChars }) {
                "Invalid JVM argument '$arg': contains potentially dangerous characters"
            }
        }

        // Validate server command
        require(serverCommand.none { it in dangerousChars }) {
            "Invalid server command '$serverCommand': contains potentially dangerous characters"
        }

        // Validate instance file names (must be absolute, no traversal)
        for ((name, _) in instanceFiles.keys + instanceFilesRaw.keys) {
            require(name.matches(Regex("[A-Za-z0-9._-]+"))) {
                "Instance file names can only contain the characters A-Z a-z 0-9 ._-"
            }
        }

        // Validate systemd environment variable names (alphanumeric and underscore only)
        val envKeyRegex = Regex("^[a-zA-Z_][a-zA-Z0-9_]*$")
        for ((key, _) in systemdEnvironment) {
            require(envKeyRegex.matches(key)) {
                "Invalid environment variable name '$key': must start with letter/underscore and contain only alphanumeric/underscore"
            }
        }
    }

    /**
     * Escapes a string for safe use in shell commands by wrapping in single quotes
     * and escaping any existing single quotes.
     */
    private fun String.shellEscape(): String {
        // Replace single quotes with '\'' (end quote, escaped quote, start quote)
        return "'" + this.replace("'", "'\\''") + "'"
    }

    /**
     * Escapes a string for safe use in systemd Environment= directives.
     * Values with spaces or special characters should be quoted.
     */
    private fun String.systemdEscape(): String {
        // If the value contains spaces, quotes, or other special chars, wrap in quotes
        return if (this.any { it in " \t\n\"'\\$`" }) {
            '"' + this.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + '"'
        } else {
            this
        }
    }
}


internal fun TerraformEmitterAws.emitVpc(
    info: AwsVpc.TFManaged,
    enableIPv6: Boolean,
) {
    val cidr = "${info.ipPrefix}.0.0/16"
    emit("cloud") {
        "module.vpc" {
            "source" - "terraform-aws-modules/vpc/aws"
            "version" - "6.6.0"

            "name" - projectPrefix
            "cidr" - cidr

            "azs" - info.availabilityZones

            // IPv4 Subnets
            "private_subnets" - List(info.availabilityZones.size) { index -> "${info.ipPrefix}.${index + 1}.0/24" }
            "public_subnets" - List(info.availabilityZones.size) { index -> "${info.ipPrefix}.${index + 100}.0/24" }

            // IPv6 Support and Subnets

            if (enableIPv6) {
                "enable_ipv6" - true
                "create_egress_only_igw" - true
                "public_subnet_assign_ipv6_address_on_creation" - true
                "public_subnet_ipv6_prefixes" - List(info.availabilityZones.size) { index -> index }// listOf(0, 1, 2)
                "private_subnet_assign_ipv6_address_on_creation" - true
                "private_subnet_ipv6_prefixes" - List(info.availabilityZones.size) { index -> index + 3 }// listOf(3, 4, 5)
            }

            "enable_nat_gateway" - (info.natGateway != AwsVpc.NatGateway.None)
            "single_nat_gateway" - (info.natGateway == AwsVpc.NatGateway.Single)
            "one_nat_gateway_per_az" - (info.natGateway == AwsVpc.NatGateway.PerAvailabilityZone)
            "enable_vpn_gateway" - false
            "enable_dns_hostnames" - true
            "enable_dns_support" - true
        }
        "resource.aws_vpc_endpoint.s3" {
            "vpc_id" - expression("module.vpc.vpc_id")
            "service_name" - "com.amazonaws.${this@emitVpc.applicationRegion}.s3"
            "route_table_ids" - expression("module.vpc.public_route_table_ids")
            if (enableIPv6) {
                "ip_address_type" - "dualstack"
                "dns_options" {
                    "dns_record_ip_type" - "dualstack"
                }
            }
        }
        "resource.aws_security_group.internal" {
            "name" - "$projectPrefix-private"
            "vpc_id" - expression("module.vpc.vpc_id")
        }
        "resource.aws_vpc_security_group_ingress_rule.freeInternal" {
            "referenced_security_group_id" - expression("aws_security_group.internal.id")
            "security_group_id" - expression("aws_security_group.internal.id")
            "ip_protocol" - -1
        }
        "resource.aws_vpc_security_group_egress_rule.freeInternal" {
            "referenced_security_group_id" - expression("aws_security_group.internal.id")
            "security_group_id" - expression("aws_security_group.internal.id")
            "ip_protocol" - -1
        }
    }
}