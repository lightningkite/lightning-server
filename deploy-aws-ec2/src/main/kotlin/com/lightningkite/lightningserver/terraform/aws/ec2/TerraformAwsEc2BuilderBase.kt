package com.lightningkite.lightningserver.terraform.aws.ec2

import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.terraform.*
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
import kotlin.collections.iterator

/**
 * Shared base for the EC2 Terraform builders ([TerraformAwsSingleEc2Builder] and
 * [TerraformAwsScalingEc2Builder]).
 *
 * This holds everything that is identical regardless of whether the application runs on a
 * single instance or behind a load balancer in an Auto Scaling Group:
 *
 * - Identity, storage, secrets, domain, and application configuration.
 * - The shared deployment resources: hardened S3 artifact bucket, the EC2 IAM role/instance
 *   profile, the encrypted-settings pipeline, and the JAR upload.
 * - The reusable user-data fragments (CloudWatch agent, AWS CLI, systemd unit, on-instance
 *   redeploy script, instance files, SSM agent) and the input validation/escaping helpers.
 *
 * Subclasses implement [emitDeploymentSpecific] to add their own networking, compute, DNS,
 * TLS, redeploy, and monitoring resources.
 *
 * @param S The ServerBuilder type being deployed
 */
public abstract class TerraformAwsEc2BuilderBase<S : ServerBuilder>(
    override val builder: S,
) : BaseTerraformEmitter<S>(), TerraformEmitterAws, TerraformEmitterAwsDomain {

    // === Identity & Storage ===

    /** S3 bucket for Terraform state storage. */
    public abstract val storageBucket: String

    /** AWS region for deployment. */
    public abstract val region: Region

    /** Human-readable display name for the deployment. */
    public abstract val displayName: String

    /** Port the application listens on (the ALB forwards here). */
    public open val appPort: Int get() = 8080

    /** Whether the application is hosted publicly for direct access.  True when a different machine is proxying. */
    public abstract val appBindsAllNetworkInterfaces: Boolean

    public override val deploymentTag: String get() = displayName
    public override val projectPrefix: String
        get() = displayName.lowercase().replace(" ", "-").filter { it.isLetterOrDigit() || it == '-' }
    public open val storageBucketPath: String get() = projectPrefix
    public open val storageEncryptionEnabled: Boolean get() = true

    /**
     * When true, a dedicated customer-managed KMS key is created and used to encrypt the mutable-domain
     * resources (EBS, S3 buckets, CloudWatch logs, SNS, the SSM settings password). Off by default — the
     * resources are still encrypted at rest with AWS-managed keys, so existing deployments are unaffected.
     * Note: DocumentDB's key is chosen separately (its key is immutable at creation); see `awsDocumentDb`.
     */
    public open val customerManagedKey: Boolean get() = false

    /**
     * Whether to create the `AWSServiceRoleForAutoScaling` service-linked role. The customer-managed key
     * policy names that role as a principal, and KMS rejects a policy whose principal doesn't yet exist — so
     * on a brand-new account (one that has never launched an Auto Scaling group) key creation would fail.
     * Defaults to false because the role already exists in any account that has used Auto Scaling before;
     * set to true for a first-ever deployment in a fresh account. Ignored unless [customerManagedKey] is on.
     */
    public open val createAutoScalingServiceLinkedRole: Boolean get() = false

    /** ARN expression of the shared customer-managed key, or null when [customerManagedKey] is off. */
    public val sharedKmsKeyArn: String? get() = if (customerManagedKey) expression("aws_kms_key.main.arn") else null

    final override val encryptionKey: KmsKeySource
        get() = sharedKmsKeyArn?.let { KmsKeySource.Existing(it) } ?: KmsKeySource.AwsManaged

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

    /** Explicit path to JAR file. If null, uses build output from the standard distribution task. */
    public open val distributionZipPath: String? get() = null

    /** JVM arguments for the application. */
    public open val jvmArgs: List<String> get() = listOf("-Xmx512m")

    /** Command to start the server (passed to main class). */
    public open val serverCommand: String get() = "serve"

    /** Whether this is a debug deployment. */
    public abstract val debug: Boolean

    /** Emergency contact email for alerts. */
    public abstract val emergencyContact: EmailAddress

    // === Instance Configuration ===

    public enum class CPUArchitecture {
        X86,
        Arm,
    }

    /** EC2 instance type. eg: t2.medium, t4g.medium */
    public abstract val instanceType: String

    /**
     *  The CPU architecture of the instance. This is entirely dependent on the instanceType.
     *  There are too many Instance types in AWS to keep track of, so we force the end user to set this value.
     *  */
    public abstract val instanceArchitecture: CPUArchitecture

    /** EBS volume size in GiB. */
    public open val volumeSizeGiB: Int get() = 20

    /** Max body size for any request. */
    public open val maxBodySize: DataSize = 10.mebibytes

    /** IP Stack Configuration. */
    public open val enableIPv4: Boolean = true
    public open val enableIPv6: Boolean = true

    // === Logging Configuration ===

    /** CloudWatch log retention in days. */
    public open val logRetentionDays: Int get() = 30

    // === Customization Hooks ===

    /** Additional packages to install on EC2 instances. */
    public open val additionalPackages: List<String> = emptyList()

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

    /**
     * Shell fragments run during instance provisioning — in the cloud-init `user_data` for the
     * single-instance builder, and during the golden-AMI bake for the scaling builder — after the
     * base tooling and the application's systemd unit are in place. Populated by on-box agent
     * helpers such as the OpenTelemetry collector ([otelGrafanaCloud]). The content is generated by
     * library code (not raw user input), so it is emitted verbatim; any externally-supplied values
     * folded into it must be validated by the helper that adds the fragment.
     */
    internal val provisioningFragments: MutableList<String> = mutableListOf()

    /**
     * systemd units installed by [provisioningFragments]. The single-instance builder starts these
     * immediately after the first deploy (its cloud-init `enable` would otherwise not start them
     * until the next boot). The scaling builder relies on the `enable` symlink baked into the AMI,
     * so the ASG starts them automatically when an instance boots.
     */
    internal val provisioningServices: MutableList<String> = mutableListOf()

    /**
     * Terraform resource addresses the compute (the single instance / the Auto Scaling Group) must
     * be created after, so anything an instance reads at boot exists first. Populated by
     * [emitInstanceSecret] with the SSM parameters on-box agents fetch at startup; both builders
     * fold this into their compute `depends_on`.
     */
    internal val instanceSecretDependencies: MutableList<String> = mutableListOf()

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

    /**
     * The VPC the application runs in. Both builders require an [AwsVpc.EC2Safe] VPC; the
     * scaling builder narrows this further to a [AwsVpc.VpcInfo] (private subnets + NAT).
     */
    abstract override val applicationVpc: AwsVpc.EC2Safe

    /**
     * The path of the server's lightweight liveness endpoint, discovered by scanning the built
     * server for a GET endpoint whose last path segment is "online" (which is what
     * `MetaEndpoints` registers at `/meta/online`). Null if the server exposes no such endpoint.
     *
     * This is the right target for a load-balancer/health probe: it is pure liveness ("the
     * process is accepting connections") and does not check downstream services, so a slow
     * dependency cannot cascade into the probe failing.
     */
    protected val detectedOnlinePath: String? by lazy {
        builder.build().endpoints.entries
            .firstOrNull { (path, group) ->
                HttpMethod.GET in group.http && path.toString().substringAfterLast('/') == "online"
            }
            ?.let { (path, _) -> path.toString() }
    }

    /**
     * Emits the networking, compute, DNS, TLS, redeploy, and monitoring resources specific to
     * this deployment style. Called by [prepareForWrite] after the shared main config and deployment
     * resources have been emitted.
     */
    protected abstract fun emitDeploymentSpecific()

    /**
     * Registers any extra Terraform providers this deployment style needs. Called before the
     * main config is emitted so the providers appear in `required_providers`.
     */
    protected open fun registerProviders() {}

    /**
     * Validates deployment-style-specific configuration. Called after the server's settings have
     * been resolved (so [settings] is populated) and before any Terraform is emitted, allowing
     * subclasses to fail fast on invalid combinations.
     */
    protected open fun validateConfiguration() {}

    override fun prepareForWrite() {
        if (!enableIPv6 && !enableIPv4) throw IllegalStateException("At least one IP stack must be enabled.")

        if (projectPrefix.any { !it.isLetterOrDigit() && !(it == '-' || it == '_') })
            throw IllegalArgumentException("The projectPrefix has illegal characters in it. It can only contain: Letters, Digits, '-', and '_'.")

        super.prepareForWrite()
        validateConfiguration()
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
        registerProviders()

        // Fulfill general settings
        fulfillSetting(generalSettings.name, buildJsonObject {
            put("projectName", displayName)
            put("publicUrl", "https://$domain")
            put("wsUrl", "wss://$wsDomain")
            put("debug", debug)
            put("emergencyContact", emergencyContact.raw)
        })

        // Force certain ktor settings
        fulfillSetting("ktorRunConfig", buildJsonObject {
            settings["ktorRunConfig"]?.let { it as? JsonObject }?.entries?.forEach { put(it.key, it.value) }
            put("host", if (appBindsAllNetworkInterfaces) "0.0.0.0" else "127.0.0.1")
            put("port", appPort)
            put("realIpHeader", "X-Forwarded-For")
        })

        // Force certain netty settings
        fulfillSetting("nettyRunConfig", buildJsonObject {
            settings["nettyRunConfig"]?.let { it as? JsonObject }?.entries?.forEach { put(it.key, it.value) }
            put("host", if(appBindsAllNetworkInterfaces) "0.0.0.0" else "127.0.0.1")
            put("port", appPort)
            put("realIpHeader", "X-Forwarded-For")
        })

        emitMainTerraformConfig()
        emitDeploymentResources()
        emitDeploymentSpecific()
    }

    protected fun emitMainTerraformConfig() {
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

    /**
     * Emits the shared deployment artifacts: the hardened S3 bucket, the EC2 IAM
     * role/instance-profile with the services-access policy, the encrypted-settings pipeline,
     * and the JAR upload. The SSM-decryptable settings password lives in SSM Parameter Store.
     */
    protected open fun emitDeploymentResources() {
        val emitter = this@TerraformAwsEc2BuilderBase

        if (customerManagedKey) {
            emit("kms") {
                "data.aws_caller_identity.current" {}
                if (createAutoScalingServiceLinkedRole) {
                    "resource.aws_iam_service_linked_role.autoscaling" {
                        "aws_service_name" - "autoscaling.amazonaws.com"
                    }
                }
                "resource.aws_kms_key.main" {
                    "description" - "Customer-managed key for $displayName"
                    "enable_key_rotation" - true
                    "policy" - kmsKeyPolicyJson()
                    // Ensure the SLR named in the key policy exists before the policy is validated.
                    if (createAutoScalingServiceLinkedRole) {
                        "depends_on" - listOf("aws_iam_service_linked_role.autoscaling")
                    }
                }
                "resource.aws_kms_alias.main" {
                    "name" - "alias/$projectPrefix"
                    "target_key_id" - expression("aws_kms_key.main.id")
                }
            }
            // Instances read CMK-encrypted S3 objects (deployment + files buckets) and the SecureString param.
            policyStatements += AwsPolicyStatement(
                action = listOf("kms:Decrypt", "kms:GenerateDataKey*", "kms:DescribeKey"),
                resource = listOf(expression("aws_kms_key.main.arn"))
            )
        }

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
                        sharedKmsKeyArn?.let {
                            "sse_algorithm" - "aws:kms"
                            "kms_master_key_id" - it
                        } ?: run { "sse_algorithm" - "AES256" }
                    }
                    if (sharedKmsKeyArn != null) "bucket_key_enabled" - true
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
                if (sharedKmsKeyArn != null) "key_id" - expression("aws_kms_key.main.arn")
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
                    "command" - $$"openssl enc -aes-256-cbc -pbkdf2 -iter 100000 -md sha256 -in \"${local_sensitive_file.settings_raw.filename}\" -out \"${path.module}/build/settings.enc\" -pass env:SETTINGS_PASS"
                    "environment" {
                        "SETTINGS_PASS" - expression("random_password.settings.result")
                    }
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
        }
    }

    // === Shared user-data fragments ===

    /** Render the registered [provisioningFragments] into the provisioning script. */
    protected fun StringBuilder.runProvisioningFragments() {
        if (provisioningFragments.isEmpty()) return
        appendLine("# === On-box agent provisioning ===")
        for (fragment in provisioningFragments) {
            appendLine(fragment)
            appendLine()
        }
    }

    /**
     * Start the [provisioningServices] now. Used by the single-instance builder after the first
     * deploy; the scaling builder leaves these to start at boot from their baked `enable` symlink.
     */
    protected fun StringBuilder.startProvisioningServices() {
        if (provisioningServices.isEmpty()) return
        appendLine("systemctl daemon-reload")
        for (service in provisioningServices) {
            appendLine("systemctl restart ${service.shellEscape()} || true")
        }
    }

    protected fun StringBuilder.instanceFiles() {
        appendLine("# === Copying in additional files ===")
        appendLine("""echo "[INFO] Copying in additional files at $(date)"""")
        appendLine("""mkdir -p ${FileType.Config.basePath}""")

        fun writeOutput(path: String, type: FileType, base64Content: String) {
            appendLine(
                """# Write file: $path
[ ! -e ${path.shellEscape()} ] && echo $base64Content | base64 -d > ${path.shellEscape()} || true"""
            )
            if (type == FileType.Script) {
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

    protected fun StringBuilder.ssm() {
        appendLine(
            $$"""
# === SSM Agent ===
echo "[INFO] Ensuring SSM agent is installed and running at $(date)"
# Required so terraform can drive in-place jar/settings redeploys via
# `aws ssm send-command`. Ubuntu 24.04 ships amazon-ssm-agent as a snap; install if missing,
# then enable + start so newly-created instances register with SSM.
if ! snap list amazon-ssm-agent >/dev/null 2>&1; then
    snap install amazon-ssm-agent --classic
    systemctl enable snap.amazon-ssm-agent.amazon-ssm-agent.service
    systemctl start snap.amazon-ssm-agent.amazon-ssm-agent.service
fi
"""
        )
    }

    /** Install + configure the CloudWatch agent in one step (used by the boot-time single-instance path). */
    protected fun StringBuilder.cloudwatchAgent(applicationRegion: String) {
        cloudwatchAgentInstall(applicationRegion)
        cloudwatchAgentConfig()
    }

    /**
     * Download + install the CloudWatch agent .deb. The Image Builder path installs the agent via the
     * AWS-managed `amazon-cloudwatch-agent-linux` component instead, so it only needs [cloudwatchAgentConfig].
     */
    protected fun StringBuilder.cloudwatchAgentInstall(applicationRegion: String) {
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
"""
        )
    }

    /** Write the CloudWatch agent config (log groups + metrics) and enable the service. */
    protected fun StringBuilder.cloudwatchAgentConfig() {
        appendLine(
            $$"""
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

    protected fun StringBuilder.awsCli() {
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

    protected fun StringBuilder.systemD() {
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

    /**
     * Installs `/usr/local/bin/lightning-server-redeploy`, the on-instance script that fetches
     * the JAR + encrypted settings from S3, decrypts the settings, and restarts the service.
     *
     * If the freshly-downloaded version fails to come up, it rolls back to the previous
     * `server-old` / `settings.json.old` (which it always keeps) and restarts, so a bad deploy
     * self-heals the instance. It still exits non-zero so the driving redeploy halts and the
     * operator is alerted.
     *
     * [bucketRegionResolution] is the shell snippet that sets `BUCKET` and `REGION`. The single
     * instance supplies them via a terraform `templatefile()` substitution; the scaling builder
     * bakes this script into the AMI and sources them from `/etc/lightning-server/deploy.env`
     * written at boot.
     *
     * [localHealthUrl], if provided, is curled after the service restarts; if it never returns
     * success the script treats the deploy as failed and rolls back. This catches a build that
     * starts cleanly (passes `systemctl is-active`) but is actually broken (returns 5xx).
     */
    protected fun StringBuilder.instanceRedeployScript(
        bucketRegionResolution: String,
        localHealthUrl: String? = null,
    ) {
        val healthCheckBlock = if (localHealthUrl != null) $$"""
log "Waiting for liveness at $$localHealthUrl"
healthy=0
for i in $(seq 1 20); do
    if curl -fsS -o /dev/null --max-time 5 "$$localHealthUrl"; then healthy=1; break; fi
    sleep 3
done
if [ "$healthy" -ne 1 ]; then
    err "Liveness endpoint $$localHealthUrl never returned success"
    tail -n 100 /var/log/$$projectPrefix/server.log >&2 || true
    rollback
    exit 1
fi""" else ""
        appendLine(
            $$"""
# === Lightning Server Redeploy Script ===
echo "[INFO] Creating Lightning Server Redeploy Script"
cat > /usr/local/bin/lightning-server-redeploy << 'REDEPLOY_EOF'
#!/bin/bash
set -euo pipefail

log() { echo "[lightning-server-redeploy] $*"; }
err() { echo "[lightning-server-redeploy] ERROR: $*" >&2; }

$$bucketRegionResolution
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

# Roll back to the previously-working server + settings, then restart. Used when the new
# version fails to become active so the instance self-heals instead of staying down.
rollback() {
    err "Rolling back to previous version"
    [ -d "$APP_DIR/server-old" ] && { rm -rf "$APP_DIR/server"; mv "$APP_DIR/server-old" "$APP_DIR/server"; }
    [ -f "$APP_DIR/settings.json.old" ] && mv -f "$APP_DIR/settings.json.old" "$APP_DIR/settings.json"
    chown -R ubuntu:ubuntu "$APP_DIR"
    systemctl restart $$projectPrefix || true
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
    rollback
    exit 1
fi
$$healthCheckBlock
log "Done"
REDEPLOY_EOF

chmod +x /usr/local/bin/lightning-server-redeploy
echo "[INFO] Creating Lightning Server Redeploy Script - DONE"
"""
        )
    }

    /**
     * Validates all user-provided inputs that will be used in the instance scripts
     * to prevent shell injection and path traversal attacks.
     */
    protected fun validateCustomInputs() {
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
     * Key policy for the shared customer-managed key. Grants: the account root full control (so IAM policies
     * on principals like the instance role can grant use); the Auto Scaling service-linked role (required or
     * the ASG cannot launch instances with CMK-encrypted EBS); the CloudWatch Logs service (to encrypt the log
     * group); and the SNS service (to encrypt topic messages).
     */
    protected open fun kmsKeyPolicyJson(): String {
        val account = expression("data.aws_caller_identity.current.account_id")
        return Json.encodeToString(buildJsonObject {
            put("Version", "2012-10-17")
            putJsonArray("Statement") {
                addJsonObject {
                    put("Sid", "EnableRootAndIam")
                    put("Effect", "Allow")
                    put("Principal", buildJsonObject { put("AWS", "arn:aws:iam::$account:root") })
                    put("Action", "kms:*")
                    put("Resource", "*")
                }
                addJsonObject {
                    put("Sid", "AllowAutoScalingServiceLinkedRole")
                    put("Effect", "Allow")
                    put("Principal", buildJsonObject {
                        put("AWS", "arn:aws:iam::$account:role/aws-service-role/autoscaling.amazonaws.com/AWSServiceRoleForAutoScaling")
                    })
                    putJsonArray("Action") {
                        listOf("kms:Encrypt", "kms:Decrypt", "kms:ReEncrypt*", "kms:GenerateDataKey*", "kms:DescribeKey", "kms:CreateGrant")
                            .forEach { add(it) }
                    }
                    put("Resource", "*")
                }
                addJsonObject {
                    put("Sid", "AllowCloudWatchLogs")
                    put("Effect", "Allow")
                    put("Principal", buildJsonObject { put("Service", "logs.$applicationRegion.amazonaws.com") })
                    putJsonArray("Action") {
                        listOf("kms:Encrypt", "kms:Decrypt", "kms:ReEncrypt*", "kms:GenerateDataKey*", "kms:DescribeKey")
                            .forEach { add(it) }
                    }
                    put("Resource", "*")
                    put("Condition", buildJsonObject {
                        put("ArnLike", buildJsonObject {
                            put("kms:EncryptionContext:aws:logs:arn", "arn:aws:logs:$applicationRegion:$account:log-group:*")
                        })
                    })
                }
                addJsonObject {
                    put("Sid", "AllowSns")
                    put("Effect", "Allow")
                    put("Principal", buildJsonObject { put("Service", "sns.amazonaws.com") })
                    putJsonArray("Action") { listOf("kms:Decrypt", "kms:GenerateDataKey*").forEach { add(it) } }
                    put("Resource", "*")
                }
            }
        })
    }

    /**
     * Escapes a string for safe use in shell commands by wrapping in single quotes
     * and escaping any existing single quotes.
     */
    protected fun String.shellEscape(): String =
        "'" + this.replace("'", "'\\''") + "'"

    /**
     * Escapes a string for safe use in systemd Environment= directives.
     * Values with spaces or special characters should be quoted.
     */
    protected fun String.systemdEscape(): String =
        if (this.any { it in " \t\n\"'\\$`" }) {
            '"' + this.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + '"'
        } else {
            this
        }
}

/**
 * Emits a Terraform-managed VPC with public + private subnets and an S3 gateway endpoint.
 * Shared by both EC2 builders.
 */
internal fun TerraformEmitterAws.emitVpc(
    info: VpcInfoTerraformManaged,
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
                "public_subnet_ipv6_prefixes" - List(info.availabilityZones.size) { index -> index }
                "private_subnet_assign_ipv6_address_on_creation" - true
                "private_subnet_ipv6_prefixes" - List(info.availabilityZones.size) { index -> index + 3 }
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
            // Associate with both tiers so the gateway endpoint routes correctly whether the
            // instances live in public subnets (single instance) or private (scaling group).
            "route_table_ids" - expression("concat(module.vpc.private_route_table_ids, module.vpc.public_route_table_ids)")
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
