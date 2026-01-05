package com.lightningkite.lightningserver.terraform.awsec2

import com.lightningkite.DataSize
import com.lightningkite.DataSize.Companion.gibibytes
import com.lightningkite.EmailAddress
import com.lightningkite.lightningserver.data.Schedule
import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.definition.loggingSettings
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.definition.telemetrySettings
import com.lightningkite.lightningserver.terraform.*
import com.lightningkite.services.Untested
import com.lightningkite.services.terraform.*
import kotlinx.serialization.json.*
import software.amazon.awssdk.regions.Region
import java.io.File
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Deployment strategy for EC2 Auto Scaling Group updates.
 */
public sealed class DeploymentStrategy {
    /**
     * Rolling update strategy - updates instances in batches while maintaining availability.
     *
     * @property minHealthyPercent Minimum percentage of healthy instances during update (0-100)
     * @property maxHealthyPercent Maximum percentage of instances during update (100-200)
     */
    public data class Rolling(
        val minHealthyPercent: Int = 50,
        val maxHealthyPercent: Int = 100,
    ) : DeploymentStrategy()

    /**
     * Blue-green deployment strategy - creates new instances before terminating old ones.
     * Requires CodeDeploy or manual traffic shifting.
     *
     * @property terminationWaitMinutes Time to wait before terminating old instances after traffic shift
     */
    public data class BlueGreen(
        val terminationWaitMinutes: Int = 5,
    ) : DeploymentStrategy()
}

/**
 * Terraform builder for deploying Lightning Server to AWS EC2 with Auto Scaling and Load Balancing.
 *
 * This generates Terraform configuration for:
 * - VPC with public/private subnets across multiple availability zones
 * - Application Load Balancer with HTTPS termination
 * - Auto Scaling Group with configurable instance counts
 * - SQS queue for scheduled task distribution
 * - CloudWatch logging and monitoring
 *
 * Example usage:
 * ```kotlin
 * object MyDeployment : TerraformAwsEc2Builder<Server>(Server) {
 *     override val storageBucket = "my-terraform-state"
 *     override val region = Region.US_WEST_2
 *     override val displayName = "My App"
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
public abstract class TerraformAwsEc2Builder<S : ServerBuilder>(
    override val builder: S,
) : BaseTerraformEmitter<S>(), TerraformEmitterAws, TerraformEmitterAwsDomain, TerraformEmitterAwsVpc {

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

    override val domainZoneId: String by lazy { domainZoneId(domainZone) }

    // === Application Configuration ===

    /**
     * The main class that starts the server.
     * Using a KClass reference provides compile-time validation that the class exists.
     *
     * Example: `override val mainClass = com.example.Main::class`
     */
    public abstract val mainClass: KClass<*>

    /** Gradle task that produces the application JAR (default: "shadowJar"). */
    public open val jarTask: String get() = "shadowJar"

    /** Explicit path to JAR file. If null, uses build output from jarTask. */
    public open val jarPath: String? get() = null

    /** JVM arguments for the application. */
    public open val jvmArgs: List<String> get() = listOf("-Xmx512m")

    /** Command to start the server (passed to main class). */
    public open val serverCommand: String get() = "serve"

    /** Whether this is a debug deployment. */
    public abstract val debug: Boolean

    /** Emergency contact email for alerts. */
    public abstract val emergencyContact: EmailAddress

    // === Instance Configuration ===

    /** EC2 instance type. Defaults to Graviton (ARM64) for cost efficiency. */
    public open val instanceType: String get() = "t4g.medium"

    /** Minimum number of instances in the Auto Scaling Group. */
    public open val minInstances: Int get() = 1

    /** Maximum number of instances in the Auto Scaling Group. */
    public open val maxInstances: Int get() = 4

    /** Desired number of instances in the Auto Scaling Group. */
    public open val desiredInstances: Int get() = 2

    /** EBS volume size in GiB. */
    public open val volumeSizeGiB: Int get() = 20

    // === Health Check Configuration ===

    /**
     * Health check endpoint path (should match where MetaEndpoints is mounted).
     *
     * OPERATIONAL NOTE: If this path doesn't return HTTP 200, the ALB will mark instances
     * as unhealthy and the ASG will continuously replace them. Ensure your server:
     * 1. Exposes MetaEndpoints at this path (default `/meta/online`)
     * 2. Returns 200 quickly (within [healthCheckTimeout])
     * 3. Only returns 200 when truly ready to serve traffic
     */
    public open val healthCheckPath: String get() = "/meta/online"

    /** Interval between health checks. */
    public open val healthCheckInterval: Duration get() = 30.seconds

    /** Timeout for each health check. */
    public open val healthCheckTimeout: Duration get() = 5.seconds

    /** Number of consecutive successful checks to consider instance healthy. */
    public open val healthyThreshold: Int get() = 2

    /** Number of consecutive failed checks to consider instance unhealthy. */
    public open val unhealthyThreshold: Int get() = 3

    /** Grace period after instance launch before health checks begin. */
    public open val healthCheckGracePeriod: Duration get() = 5.minutes

    // === Scaling Configuration ===

    /** Target CPU utilization percentage for scaling (0-100). */
    public open val targetCpuUtilization: Int get() = 70

    /** Cooldown period after scale-in action. */
    public open val scaleInCooldown: Duration get() = 5.minutes

    /** Cooldown period after scale-out action. */
    public open val scaleOutCooldown: Duration get() = 1.minutes

    // === Deployment Configuration ===

    /** Deployment strategy for ASG updates. */
    public open val deploymentStrategy: DeploymentStrategy get() = DeploymentStrategy.Rolling()

    // === VPC Configuration ===

    /** CIDR prefix for VPC (will create X.X.0.0/16). */
    public open val ipPrefix: String get() = "10.0"

    /** Availability zones to deploy to (e.g., ["a", "b", "c"]). */
    public open val availabilityZones: List<String>
        get() = listOf("${region.id()}a", "${region.id()}b", "${region.id()}c")

    override val applicationVpc: TerraformAwsVpcInfo by lazy { createVpc() }

    // === Logging Configuration ===

    /** CloudWatch log retention in days. */
    public open val logRetentionDays: Int get() = 30

    // === Customization Hooks ===

    /** Additional packages to install on EC2 instances. */
    public val additionalPackages: MutableList<String> = mutableListOf()

    /** Additional user-data scripts to run after base setup. */
    public val userDataScripts: MutableList<String> = mutableListOf()

    /** Additional systemd environment variables. */
    public val systemdEnvironment: MutableMap<String, String> = mutableMapOf()

    /** Additional files to place on EC2 instances. Key = path, Value = content. */
    public val instanceFiles: MutableMap<String, String> = mutableMapOf()

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
        super.finalize()
        require(TerraformProviderImport.aws)
        require(
            TerraformProvider(
                TerraformProviderImport.aws,
                null,
                buildJsonObject { put("region", region.id()) })
        )
        require(TerraformProvider(TerraformProviderImport.mongodbAtlas, null, JsonObject(emptyMap())))
        require(TerraformProviderImport.random)
        require(TerraformProviderImport.archive)
        require(TerraformProviderImport.local)
        require(TerraformProviderImport.nullProvider)
        require(TerraformProviderImport.tls)

        val emitter = this@TerraformAwsEc2Builder

        // Fulfill general settings
        fulfillSetting(generalSettings.name, buildJsonObject {
            put("projectName", displayName)
            put("publicUrl", "https://$domain")
            put("wsUrl", "wss://$domain/ws?path=")
            put("debug", debug)
            put("emergencyContact", emergencyContact.raw)
        })

        emitVpcResources()
        emitSecurityGroupResources()
        emitAlbResources()
        emitDeploymentResources()
        emitAsgResources()
        emitSqsScheduleResources()
        emitDnsResources()
        emitMonitoringResources()
        emitMainTerraformConfig()
    }

    private fun emitVpcResources() {
        // VPC is created via applicationVpc lazy property
        applicationVpc // trigger creation
    }

    private fun emitSecurityGroupResources() {
        emit("security") {
            // ALB Security Group - allows inbound HTTP/HTTPS from internet
            "resource.aws_security_group.alb" {
                "name" - "$projectPrefix-alb-sg"
                "description" - "Security group for ALB"
                "vpc_id" - expression("module.vpc.vpc_id")

                "tags" {
                    "Name" - "$projectPrefix-alb-sg"
                }
            }
            "resource.aws_vpc_security_group_ingress_rule.alb_http" {
                "security_group_id" - expression("aws_security_group.alb.id")
                "cidr_ipv4" - "0.0.0.0/0"
                "from_port" - 80
                "to_port" - 80
                "ip_protocol" - "tcp"
            }
            "resource.aws_vpc_security_group_ingress_rule.alb_https" {
                "security_group_id" - expression("aws_security_group.alb.id")
                "cidr_ipv4" - "0.0.0.0/0"
                "from_port" - 443
                "to_port" - 443
                "ip_protocol" - "tcp"
            }
            "resource.aws_vpc_security_group_egress_rule.alb_all" {
                "security_group_id" - expression("aws_security_group.alb.id")
                "cidr_ipv4" - "0.0.0.0/0"
                "ip_protocol" - -1
            }

            // EC2 Security Group - allows inbound from ALB only
            "resource.aws_security_group.ec2" {
                "name" - "$projectPrefix-ec2-sg"
                "description" - "Security group for EC2 instances"
                "vpc_id" - expression("module.vpc.vpc_id")

                "tags" {
                    "Name" - "$projectPrefix-ec2-sg"
                }
            }
            "resource.aws_vpc_security_group_ingress_rule.ec2_from_alb" {
                "security_group_id" - expression("aws_security_group.ec2.id")
                "referenced_security_group_id" - expression("aws_security_group.alb.id")
                "from_port" - 8080
                "to_port" - 8080
                "ip_protocol" - "tcp"
            }
            "resource.aws_vpc_security_group_egress_rule.ec2_all" {
                "security_group_id" - expression("aws_security_group.ec2.id")
                "cidr_ipv4" - "0.0.0.0/0"
                "ip_protocol" - -1
            }
        }
    }

    private fun emitAlbResources() {
        emit("alb") {
            // Application Load Balancer
            "resource.aws_lb.main" {
                "name" - "$projectPrefix-alb"
                "internal" - false
                "load_balancer_type" - "application"
                "security_groups" - listOf(expression("aws_security_group.alb.id"))
                "subnets" - expression("module.vpc.public_subnets")

                "enable_deletion_protection" - false

                "tags" {
                    "Name" - "$projectPrefix-alb"
                }
            }

            // Target Group
            "resource.aws_lb_target_group.main" {
                "name" - "$projectPrefix-tg"
                "port" - 8080
                "protocol" - "HTTP"
                "vpc_id" - expression("module.vpc.vpc_id")
                "target_type" - "instance"
                "deregistration_delay" - 30

                "health_check" {
                    "enabled" - true
                    "path" - healthCheckPath
                    "port" - "traffic-port"
                    "protocol" - "HTTP"
                    "interval" - healthCheckInterval.inWholeSeconds
                    "timeout" - healthCheckTimeout.inWholeSeconds
                    "healthy_threshold" - healthyThreshold
                    "unhealthy_threshold" - unhealthyThreshold
                    "matcher" - "200"
                }

                "tags" {
                    "Name" - "$projectPrefix-tg"
                }
            }

            // HTTP Listener (redirect to HTTPS)
            "resource.aws_lb_listener.http" {
                "load_balancer_arn" - expression("aws_lb.main.arn")
                "port" - 80
                "protocol" - "HTTP"

                "default_action" {
                    "type" - "redirect"
                    "redirect" {
                        "port" - "443"
                        "protocol" - "HTTPS"
                        "status_code" - "HTTP_301"
                    }
                }
            }

            // HTTPS Listener
            "resource.aws_lb_listener.https" {
                "load_balancer_arn" - expression("aws_lb.main.arn")
                "port" - 443
                "protocol" - "HTTPS"
                "ssl_policy" - "ELBSecurityPolicy-TLS13-1-2-2021-06"
                "certificate_arn" - expression("aws_acm_certificate.main.arn")

                "default_action" {
                    "type" - "forward"
                    "target_group_arn" - expression("aws_lb_target_group.main.arn")
                }

                "depends_on" - listOf("aws_acm_certificate_validation.main")
            }
        }
    }

    private fun emitDeploymentResources() {
        val emitter = this@TerraformAwsEc2Builder

        // Add S3 permissions for EC2 instances to download JAR
        policyStatements += AwsPolicyStatement(
            action = listOf("s3:GetObject"),
            resource = listOf(
                $$"${aws_s3_bucket.deployment.arn}",
                $$"${aws_s3_bucket.deployment.arn}/*",
            )
        )

        // Add SQS permissions
        policyStatements += AwsPolicyStatement(
            action = listOf(
                "sqs:ReceiveMessage",
                "sqs:DeleteMessage",
                "sqs:GetQueueAttributes",
            ),
            resource = listOf($$"${aws_sqs_queue.scheduled_tasks.arn}")
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

        emit("deployment") {
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
                "description" - "Access to $projectPrefix services"
                "policy" - expression("jsonencode(local.servicesAccessPolicy)")
            }
            "resource.aws_iam_role_policy_attachment.servicesAccess" {
                "role" - expression("aws_iam_role.ec2.name")
                "policy_arn" - expression("aws_iam_policy.servicesAccess.arn")
            }

            // SSM for Session Manager access (optional but useful)
            "resource.aws_iam_role_policy_attachment.ssm" {
                "role" - expression("aws_iam_role.ec2.name")
                "policy_arn" - "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
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
                    "settings_hash" - expression("local_sensitive_file.settings_raw.content")
                }
                "provisioner.local-exec" {
                    "command" - $$"openssl enc -aes-256-cbc -pbkdf2 -iter 100000 -md sha256 -in \"${local_sensitive_file.settings_raw.filename}\" -out \"${path.module}/build/settings.enc\" -pass pass:${random_password.settings.result}"
                }
                "depends_on" - listOf("local_sensitive_file.settings_raw")
            }

            // Upload JAR to S3
            "resource.null_resource.upload_jar" {
                "triggers" {
                    "always" - expression("timestamp()")
                }
                "provisioner.local-exec" {
                    "command" - $$"aws s3 cp \"${path.module}/../../build/libs/*-all.jar\" s3://${aws_s3_bucket.deployment.id}/app.jar --region ${emitter.applicationRegion}"
                }
                "depends_on" - listOf("aws_s3_bucket.deployment")
            }

            // Upload encrypted settings to S3
            "resource.null_resource.upload_settings" {
                "triggers" {
                    "settings_hash" - expression("local_sensitive_file.settings_raw.content")
                }
                "provisioner.local-exec" {
                    "command" - $$"aws s3 cp \"${path.module}/build/settings.enc\" s3://${aws_s3_bucket.deployment.id}/settings.enc --region ${emitter.applicationRegion}"
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
                "filename" - $$"${path.module}/build/$projectPrefix-key.pem"
                "file_permission" - "0600"
            }
        }
    }

    private fun emitAsgResources() {
        val emitter = this@TerraformAwsEc2Builder
        val isArm = instanceType.startsWith("t4g") ||
                    instanceType.startsWith("m7g") ||
                    instanceType.startsWith("c7g") ||
                    instanceType.startsWith("r7g") ||
                    instanceType.contains("g.")

        emit("asg") {
            // Get latest Amazon Linux 2023 AMI
            "data.aws_ami.amazon_linux" {
                "most_recent" - true
                "owners" - listOf("amazon")

                "filter" {
                    "name" - "name"
                    "values" - listOf("al2023-ami-*")
                }
                "filter" {
                    "name" - "architecture"
                    "values" - listOf(if (isArm) "arm64" else "x86_64")
                }
                "filter" {
                    "name" - "virtualization-type"
                    "values" - listOf("hvm")
                }
            }

            // Launch Template
            "resource.aws_launch_template.main" {
                "name" - "$projectPrefix-lt"
                "image_id" - expression("data.aws_ami.amazon_linux.id")
                "instance_type" - instanceType
                "key_name" - expression("aws_key_pair.ec2.key_name")

                "iam_instance_profile" {
                    "name" - expression("aws_iam_instance_profile.ec2.name")
                }

                "network_interfaces" {
                    "associate_public_ip_address" - false
                    "security_groups" - listOf(
                        expression("aws_security_group.ec2.id"),
                        expression("aws_security_group.internal.id")
                    )
                }

                // SECURITY: Require IMDSv2 to prevent SSRF attacks on instance metadata
                "metadata_options" {
                    "http_endpoint" - "enabled"
                    "http_tokens" - "required"  // Require IMDSv2
                    "http_put_response_hop_limit" - 1
                }

                "block_device_mappings" {
                    "device_name" - "/dev/xvda"
                    "ebs" {
                        "volume_size" - volumeSizeGiB
                        "volume_type" - "gp3"
                        "encrypted" - true
                        "delete_on_termination" - true
                    }
                }

                "user_data" - expression("base64encode(local.user_data)")

                "tag_specifications" {
                    "resource_type" - "instance"
                    "tags" {
                        "Name" - "$projectPrefix-instance"
                    }
                }

                "depends_on" - listOf(
                    "null_resource.upload_jar",
                    "null_resource.upload_settings"
                )
            }

            // User data script
            "locals" {
                "user_data" - generateUserData()
            }

            // Auto Scaling Group
            "resource.aws_autoscaling_group.main" {
                "name" - "$projectPrefix-asg"
                "min_size" - minInstances
                "max_size" - maxInstances
                "desired_capacity" - desiredInstances
                "vpc_zone_identifier" - expression("module.vpc.private_subnets")
                "target_group_arns" - listOf(expression("aws_lb_target_group.main.arn"))
                "health_check_type" - "ELB"
                "health_check_grace_period" - healthCheckGracePeriod.inWholeSeconds
                "termination_policies" - listOf("OldestInstance")

                "launch_template" {
                    "id" - expression("aws_launch_template.main.id")
                    "version" - "\$Latest"
                }

                when (val strategy = deploymentStrategy) {
                    is DeploymentStrategy.Rolling -> {
                        "instance_refresh" {
                            "strategy" - "Rolling"
                            "preferences" {
                                "min_healthy_percentage" - strategy.minHealthyPercent
                                "max_healthy_percentage" - strategy.maxHealthyPercent
                                "instance_warmup" - healthCheckGracePeriod.inWholeSeconds
                            }
                        }
                    }
                    is DeploymentStrategy.BlueGreen -> {
                        // Blue-green would require CodeDeploy or manual handling
                    }
                }

                "tag" {
                    "key" - "Name"
                    "value" - "$projectPrefix-instance"
                    "propagate_at_launch" - true
                }
            }

            // Target Tracking Scaling Policy - CPU
            "resource.aws_autoscaling_policy.cpu" {
                "name" - "$projectPrefix-cpu-scaling"
                "autoscaling_group_name" - expression("aws_autoscaling_group.main.name")
                "policy_type" - "TargetTrackingScaling"

                "target_tracking_configuration" {
                    "predefined_metric_specification" {
                        "predefined_metric_type" - "ASGAverageCPUUtilization"
                    }
                    "target_value" - targetCpuUtilization
                    "scale_in_cooldown" - scaleInCooldown.inWholeSeconds
                    "scale_out_cooldown" - scaleOutCooldown.inWholeSeconds
                }
            }
        }
    }

    private fun emitSqsScheduleResources() {
        val emitter = this@TerraformAwsEc2Builder

        emit("schedules") {
            // SQS Queue for scheduled tasks
            "resource.aws_sqs_queue.scheduled_tasks" {
                "name" - "$projectPrefix-scheduled-tasks"
                "visibility_timeout_seconds" - 300  // 5 minutes
                "message_retention_seconds" - 86400  // 1 day
                "receive_wait_time_seconds" - 20  // Long polling
            }

            // Dead Letter Queue
            "resource.aws_sqs_queue.scheduled_tasks_dlq" {
                "name" - "$projectPrefix-scheduled-tasks-dlq"
                "message_retention_seconds" - 1209600  // 14 days
            }

            // DLQ Redrive Policy
            "resource.aws_sqs_queue_redrive_policy.scheduled_tasks" {
                "queue_url" - expression("aws_sqs_queue.scheduled_tasks.id")
                "redrive_policy" - expression("""jsonencode({
                    deadLetterTargetArn = aws_sqs_queue.scheduled_tasks_dlq.arn
                    maxReceiveCount     = 3
                })""")
            }

            // EventBridge rules for each scheduled task
            for ((path, schedule) in builder.build().schedules) {
                val name = path.toString().filter { it.isLetterOrDigit() || it == '_' }

                "resource.aws_cloudwatch_event_rule.scheduled_task_$name" {
                    "name" - "${projectPrefix}_$name"
                    "schedule_expression" - when (val s = schedule.schedule) {
                        is Schedule.Daily -> "cron(${s.time.minute} ${s.time.hour} * * ? *)"
                        is Schedule.Frequency -> "rate(${s.gap.inWholeMinutes} minute${if (s.gap.inWholeMinutes > 1) "s" else ""})"
                        is Schedule.Cron -> "cron(${s.cron} *)"
                    }
                }

                "resource.aws_cloudwatch_event_target.scheduled_task_$name" {
                    "rule" - expression("aws_cloudwatch_event_rule.scheduled_task_$name.name")
                    "target_id" - "sqs"
                    "arn" - expression("aws_sqs_queue.scheduled_tasks.arn")
                    "input" - Json.encodeToString(buildJsonObject {
                        put("scheduled", path.toString())
                    })
                }
            }

            // Allow EventBridge to send to SQS
            "resource.aws_sqs_queue_policy.scheduled_tasks" {
                "queue_url" - expression("aws_sqs_queue.scheduled_tasks.id")
                "policy" - expression("""jsonencode({
                    Version = "2012-10-17"
                    Statement = [{
                        Effect = "Allow"
                        Principal = { Service = "events.amazonaws.com" }
                        Action = "sqs:SendMessage"
                        Resource = aws_sqs_queue.scheduled_tasks.arn
                    }]
                })""")
            }
        }
    }

    private fun emitDnsResources() {
        emit("dns") {
            // Route53 Zone Data
            "data.aws_route53_zone.main" {
                "name" - domainZone
            }

            // ACM Certificate
            "resource.aws_acm_certificate.main" {
                "domain_name" - domain
                "validation_method" - "DNS"

                "lifecycle" {
                    "create_before_destroy" - true
                }
            }

            // DNS Validation Record
            "resource.aws_route53_record.cert_validation" {
                "for_each" - expression("""{
                    for dvo in aws_acm_certificate.main.domain_validation_options : dvo.domain_name => {
                        name   = dvo.resource_record_name
                        record = dvo.resource_record_value
                        type   = dvo.resource_record_type
                    }
                }""")
                "zone_id" - expression("data.aws_route53_zone.main.zone_id")
                "name" - expression("each.value.name")
                "type" - expression("each.value.type")
                "records" - listOf(expression("each.value.record"))
                "ttl" - 60
            }

            // Certificate Validation
            "resource.aws_acm_certificate_validation.main" {
                "certificate_arn" - expression("aws_acm_certificate.main.arn")
                "validation_record_fqdns" - expression("[for record in aws_route53_record.cert_validation : record.fqdn]")
            }

            // A Record pointing to ALB
            "resource.aws_route53_record.main" {
                "zone_id" - expression("data.aws_route53_zone.main.zone_id")
                "name" - domain
                "type" - "A"

                "alias" {
                    "name" - expression("aws_lb.main.dns_name")
                    "zone_id" - expression("aws_lb.main.zone_id")
                    "evaluate_target_health" - true
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

            // Unhealthy Host Count Alarm
            "resource.aws_cloudwatch_metric_alarm.unhealthy_hosts" {
                "alarm_name" - "$projectPrefix-unhealthy-hosts"
                "comparison_operator" - "GreaterThanThreshold"
                "evaluation_periods" - 2
                "metric_name" - "UnHealthyHostCount"
                "namespace" - "AWS/ApplicationELB"
                "period" - 60
                "statistic" - "Average"
                "threshold" - 0
                "alarm_description" - "Unhealthy hosts in target group"
                "alarm_actions" - listOf(expression("aws_sns_topic.emergency.arn"))
                "dimensions" {
                    "TargetGroup" - expression("aws_lb_target_group.main.arn_suffix")
                    "LoadBalancer" - expression("aws_lb.main.arn_suffix")
                }
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
                "alarm_actions" - listOf(expression("aws_sns_topic.emergency.arn"))
                "dimensions" {
                    "AutoScalingGroupName" - expression("aws_autoscaling_group.main.name")
                }
            }
        }

        // Outputs
        emit("outputs") {
            "output.alb_dns_name" {
                "description" - "DNS name of the load balancer"
                "value" - expression("aws_lb.main.dns_name")
            }
            "output.application_url" {
                "description" - "Application URL"
                "value" - "https://$domain"
            }
            "output.sqs_queue_url" {
                "description" - "SQS queue URL for scheduled tasks"
                "value" - expression("aws_sqs_queue.scheduled_tasks.url")
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

    private fun generateUserData(): String {
        val emitter = this@TerraformAwsEc2Builder

        // Validate inputs to prevent shell injection
        validateUserDataInputs()

        return buildString {
            appendLine("#!/bin/bash")
            appendLine("set -euo pipefail")
            appendLine()
            appendLine("# === Logging Setup ===")
            appendLine("exec > >(tee /var/log/user-data.log | logger -t user-data -s 2>/dev/console) 2>&1")
            appendLine("BOOT_START=\$(date +%s)")
            appendLine("echo \"[INFO] User data script started at \$(date)\"")
            appendLine()
            appendLine("# === System Update ===")
            appendLine("echo \"[INFO] Updating system packages...\"")
            appendLine("dnf update -y")
            appendLine()
            appendLine("# === Install Java 17 (Corretto) ===")
            appendLine("dnf install -y java-17-amazon-corretto-headless")
            appendLine()
            appendLine("# === Install CloudWatch Agent ===")
            appendLine("dnf install -y amazon-cloudwatch-agent")
            appendLine()

            // Additional packages - each validated
            if (additionalPackages.isNotEmpty()) {
                appendLine("# === Additional Packages ===")
                for (pkg in additionalPackages) {
                    // Package names are validated in validateUserDataInputs()
                    appendLine("dnf install -y ${pkg.shellEscape()}")
                }
                appendLine()
            }

            appendLine("# === Application Setup ===")
            appendLine("mkdir -p /opt/app")
            appendLine()
            appendLine("# ISSUE: IAM role may not be immediately available after instance launch.")
            appendLine("# Retry S3 downloads with exponential backoff to handle eventual consistency.")
            appendLine("download_with_retry() {")
            appendLine("    local src=\"\$1\"")
            appendLine("    local dst=\"\$2\"")
            appendLine("    local max_attempts=5")
            appendLine("    local attempt=1")
            appendLine("    while [ \$attempt -le \$max_attempts ]; do")
            appendLine("        echo \"[DEBUG] Downloading \$src (attempt \$attempt/\$max_attempts)\"")
            appendLine("        if aws s3 cp \"\$src\" \"\$dst\" --region ${emitter.applicationRegion}; then")
            appendLine("            echo \"[INFO] Successfully downloaded \$src\"")
            appendLine("            return 0")
            appendLine("        fi")
            appendLine("        echo \"[WARN] Download failed, waiting before retry...\"")
            appendLine("        sleep \$((attempt * 5))")
            appendLine("        attempt=\$((attempt + 1))")
            appendLine("    done")
            appendLine("    echo \"[ERROR] Failed to download \$src after \$max_attempts attempts\"")
            appendLine("    return 1")
            appendLine("}")
            appendLine()
            appendLine("echo \"[INFO] Downloading application JAR from S3...\"")
            appendLine("download_with_retry s3://\${aws_s3_bucket.deployment.id}/app.jar /opt/app/server.jar")
            appendLine()
            appendLine("echo \"[INFO] Downloading encrypted settings from S3...\"")
            appendLine("download_with_retry s3://\${aws_s3_bucket.deployment.id}/settings.enc /opt/app/settings.enc")
            appendLine()
            appendLine("# Decrypt settings using PBKDF2 for strong key derivation")
            appendLine("echo \"[INFO] Decrypting settings...\"")
            appendLine("if ! openssl enc -d -aes-256-cbc -pbkdf2 -iter 100000 -md sha256 -in /opt/app/settings.enc -out /opt/app/settings.json -pass pass:\${random_password.settings.result}; then")
            appendLine("    echo \"[ERROR] Failed to decrypt settings. Check that the encryption password matches.\"")
            appendLine("    exit 1")
            appendLine("fi")
            appendLine()
            appendLine("# ISSUE: Encrypted file left on disk could be a minor security concern.")
            appendLine("# Remove encrypted file after successful decryption.")
            appendLine("rm -f /opt/app/settings.enc")
            appendLine("echo \"[INFO] Settings decrypted successfully\"")
            appendLine()
            appendLine("# ISSUE: Service runs as ec2-user but files are created as root.")
            appendLine("# Set proper ownership so the application can read its files.")
            appendLine("chown -R ec2-user:ec2-user /opt/app")
            appendLine("chmod 600 /opt/app/settings.json  # Restrict settings file permissions")
            appendLine()

            // Instance files - using base64 encoding to prevent heredoc injection
            for ((path, content) in instanceFiles) {
                // Path is validated in validateUserDataInputs()
                val base64Content = java.util.Base64.getEncoder().encodeToString(content.toByteArray())
                appendLine("# Write file: $path")
                appendLine("mkdir -p \$(dirname ${path.shellEscape()})")
                appendLine("echo '$base64Content' | base64 -d > ${path.shellEscape()}")
                appendLine()
            }

            appendLine("# === Systemd Service ===")
            appendLine("cat > /etc/systemd/system/lightning-server.service << 'EOF'")
            appendLine("[Unit]")
            appendLine("Description=Lightning Server")
            appendLine("After=network.target")
            appendLine()
            appendLine("[Service]")
            appendLine("Type=simple")
            appendLine("User=ec2-user")
            appendLine("WorkingDirectory=/opt/app")
            // JVM args and serverCommand are validated in validateUserDataInputs()
            appendLine("ExecStart=/usr/bin/java ${jvmArgs.joinToString(" ")} -jar /opt/app/server.jar ${serverCommand}")
            appendLine("Restart=always")
            appendLine("RestartSec=10")
            appendLine("StandardOutput=journal")
            appendLine("StandardError=journal")
            appendLine()
            appendLine("# Environment")
            appendLine("Environment=SETTINGS_FILE=/opt/app/settings.json")
            appendLine("Environment=SQS_SCHEDULE_QUEUE_URL=\${aws_sqs_queue.scheduled_tasks.url}")

            // Additional systemd environment - keys and values are validated
            for ((key, value) in systemdEnvironment) {
                // Escape values that contain special characters for systemd
                appendLine("Environment=$key=${value.systemdEscape()}")
            }

            appendLine()
            appendLine("[Install]")
            appendLine("WantedBy=multi-user.target")
            appendLine("EOF")
            appendLine()

            appendLine("# === CloudWatch Agent Configuration ===")
            appendLine("# NOTE: Application logs go to systemd journal (StandardOutput=journal), so we")
            appendLine("# configure CloudWatch to collect from journald, not /var/log/messages.")
            appendLine("cat > /opt/aws/amazon-cloudwatch-agent/etc/amazon-cloudwatch-agent.json << 'EOF'")
            appendLine("""{
  "logs": {
    "logs_collected": {
      "journald": {
        "log_group_name": "/ec2/$projectPrefix/application",
        "log_stream_name": "{instance_id}/journal",
        "collect_list": [
          {
            "unit": "lightning-server.service",
            "priority": "info"
          }
        ]
      },
      "files": {
        "collect_list": [
          {
            "file_path": "/var/log/user-data.log",
            "log_group_name": "/ec2/$projectPrefix/application",
            "log_stream_name": "{instance_id}/user-data"
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
    },
    "append_dimensions": {
      "AutoScalingGroupName": "\$${"\${aws:AutoScalingGroupName}"}",
      "InstanceId": "\$${"\${aws:InstanceId}"}"
    }
  }
}""")
            appendLine("EOF")
            appendLine()

            // Custom user-data scripts
            if (userDataScripts.isNotEmpty()) {
                appendLine("# === Custom User-Data Scripts ===")
                for (script in userDataScripts) {
                    appendLine(script)
                    appendLine()
                }
            }

            appendLine("# === Start Services ===")
            appendLine("echo \"[INFO] Starting services...\"")
            appendLine("systemctl daemon-reload")
            appendLine()
            appendLine("# Start CloudWatch agent first so we capture application startup logs")
            appendLine("systemctl enable amazon-cloudwatch-agent")
            appendLine("systemctl start amazon-cloudwatch-agent")
            appendLine()
            appendLine("# Verify CloudWatch agent started (non-fatal if it fails)")
            appendLine("if ! systemctl is-active --quiet amazon-cloudwatch-agent; then")
            appendLine("    echo \"[WARN] CloudWatch agent failed to start. Logs may not be collected.\"")
            appendLine("    echo \"[WARN] Check: journalctl -u amazon-cloudwatch-agent\"")
            appendLine("fi")
            appendLine()
            appendLine("# Start the application")
            appendLine("systemctl enable lightning-server")
            appendLine("systemctl start lightning-server")
            appendLine()
            appendLine("# Verify application started")
            appendLine("sleep 5  # Give the JVM a moment to start")
            appendLine("if ! systemctl is-active --quiet lightning-server; then")
            appendLine("    echo \"[ERROR] Application failed to start!\"")
            appendLine("    echo \"[ERROR] Check: journalctl -u lightning-server\"")
            appendLine("    journalctl -u lightning-server --no-pager -n 50")
            appendLine("    # Don't exit - let the ASG health check handle this")
            appendLine("fi")
            appendLine()
            appendLine("BOOT_END=\$(date +%s)")
            appendLine("BOOT_DURATION=\$((BOOT_END - BOOT_START))")
            appendLine("echo \"[INFO] User data script completed in \${BOOT_DURATION} seconds\"")
        }
    }

    private fun createVpc(): TerraformAwsVpcInfo {
        val cidr = "$ipPrefix.0.0/16"
        emit("vpc") {
            "module.vpc" {
                "source" - "terraform-aws-modules/vpc/aws"
                "version" - "5.1.2"

                "name" - projectPrefix
                "cidr" - cidr

                "azs" - availabilityZones
                "private_subnets" - listOf("$ipPrefix.1.0/24", "$ipPrefix.2.0/24", "$ipPrefix.3.0/24")
                "public_subnets" - listOf("$ipPrefix.101.0/24", "$ipPrefix.102.0/24", "$ipPrefix.103.0/24")

                // OPERATIONAL NOTE: Single NAT gateway saves ~$30-45/mo but is a single point of failure.
                // If the NAT gateway AZ goes down, instances in other AZs lose outbound internet access.
                // For production workloads requiring high availability, override this in a subclass:
                //   single_nat_gateway = false
                //   one_nat_gateway_per_az = true
                "enable_nat_gateway" - true
                "single_nat_gateway" - true
                "enable_vpn_gateway" - false
                "enable_dns_hostnames" - true
                "enable_dns_support" - true

                "tags" {
                    "Name" - "$projectPrefix-vpc"
                }
            }

            // Internal security group for VPC resources
            "resource.aws_security_group.internal" {
                "name" - "$projectPrefix-internal"
                "description" - "Allow internal VPC traffic"
                "vpc_id" - expression("module.vpc.vpc_id")
            }
            "resource.aws_vpc_security_group_ingress_rule.internal_all" {
                "for_each" - expression("toset(module.vpc.private_subnets_cidr_blocks)")
                "security_group_id" - expression("aws_security_group.internal.id")
                "cidr_ipv4" - expression("each.key")
                "ip_protocol" - -1
            }
            "resource.aws_vpc_security_group_egress_rule.internal_all" {
                "for_each" - expression("toset(module.vpc.private_subnets_cidr_blocks)")
                "security_group_id" - expression("aws_security_group.internal.id")
                "cidr_ipv4" - expression("each.key")
                "ip_protocol" - -1
            }
        }

        return TerraformAwsVpcInfo(
            id = "module.vpc.vpc_id",
            securityGroup = "aws_security_group.internal.id",
            privateSubnets = "module.vpc.private_subnets",
            publicSubnets = "module.vpc.public_subnets",
            applicationSubnets = "module.vpc.private_subnets",
            natGatewayIps = "module.vpc.nat_public_ips",
            cidr = cidr
        )
    }

    private fun domainZoneId(domainZone: String): String {
        return TerraformJsonObject.expression("data.aws_route53_zone.main.zone_id")
    }

    /**
     * Validates all user-provided inputs that will be used in the user-data script
     * to prevent shell injection and path traversal attacks.
     */
    private fun validateUserDataInputs() {
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

        // Validate instance file paths (must be absolute, no traversal)
        for ((path, _) in instanceFiles) {
            require(path.startsWith("/")) {
                "Instance file path '$path' must be absolute (start with /)"
            }
            require(!path.contains("..")) {
                "Instance file path '$path' must not contain path traversal (..)"
            }
            // Disallow writing to sensitive system directories
            val forbiddenPrefixes = listOf("/etc/passwd", "/etc/shadow", "/etc/sudoers", "/root/.ssh")
            require(forbiddenPrefixes.none { path.startsWith(it) }) {
                "Instance file path '$path' targets a sensitive system location"
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
            "\"" + this.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
        } else {
            this
        }
    }
}
