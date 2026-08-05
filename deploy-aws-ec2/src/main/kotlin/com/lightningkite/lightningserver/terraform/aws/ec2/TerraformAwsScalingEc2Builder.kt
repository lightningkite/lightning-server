package com.lightningkite.lightningserver.terraform.aws.ec2

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.services.Untested
import com.lightningkite.services.terraform.*
import com.lightningkite.services.terraform.TerraformJsonObject.Companion.expression
import kotlinx.serialization.json.*
import kotlin.math.absoluteValue

/**
 * Terraform builder for deploying Lightning Server to a horizontally-scaled, load-balanced
 * fleet of EC2 instances.
 *
 * Architecture:
 * - An **Application Load Balancer** in public subnets terminates TLS using an ACM certificate
 *   (DNS-validated via Route53) and forwards HTTP to the application on [appPort].
 * - The application runs on instances in **private subnets** inside an **Auto Scaling Group**,
 *   reachable only from the ALB. There is no public IP and no SSH key — administrative access is
 *   via SSM Session Manager.
 * - Instances boot from a **golden AMI** baked by EC2 Image Builder (JVM, agents, the redeploy
 *   script, and the systemd unit are pre-installed), so scale-out is fast. The application JAR
 *   and encrypted settings are fetched from S3 at boot, never baked, so ordinary app deploys do
 *   not re-bake the AMI.
 * - Code/settings updates roll out **one instance at a time with health validation** via SSM
 *   (see `redeploy-fleet.sh`): each instance is drained from the ALB, redeployed, and only
 *   returned to service once healthy before the next is touched.
 *
 * **Requirement:** because the fleet has multiple instances, scheduled tasks are coordinated
 * through the shared cache (see Lightning Server's schedule locking). The configured cache must
 * therefore be a *distributed* cache (Redis / Memcached / DynamoDB); a per-instance RAM cache is
 * rejected in [validateConfiguration].
 *
 * The VPC must be an [AwsVpc.VpcInfo] (Terraform-managed or pre-existing) that provides private
 * subnets with NAT egress — the [AwsVpc.Default] VPC is not sufficient.
 *
 * @param S The ServerBuilder type being deployed
 */
@Untested
public abstract class TerraformAwsScalingEc2Builder<S : ServerBuilder>(
    builder: S,
) : TerraformAwsEc2BuilderBase<S>(builder) {

    abstract override val applicationVpc: AwsVpc.VpcInfo

    public fun terraformManagedVPC(
        ipPrefix: String,
        availabilityZones: List<String>,
        natGateway: AwsVpc.NatGateway,
    ): AwsVpc.VpcInfo = VpcInfoTerraformManaged(
        ipPrefix = ipPrefix,
        availabilityZones = availabilityZones,
        natGateway = natGateway,
        id = expression("module.vpc.vpc_id"),
        securityGroup = expression("aws_security_group.internal.id"),
        privateSubnets = expression("module.vpc.private_subnets"),
        publicSubnets = expression("module.vpc.public_subnets"),
        applicationSubnet = expression("module.vpc.private_subnets[0]"),
        natGatewayIps = expression("module.vpc.nat_public_ips"),
    )

    // === Scaling configuration ===

    /** Minimum number of instances in the Auto Scaling Group. */
    public open val minSize: Int get() = 2

    /** Maximum number of instances in the Auto Scaling Group. */
    public open val maxSize: Int get() = 6

    /** Desired (starting) number of instances. */
    public open val desiredCapacity: Int get() = 2

    /** Exposed publicly for the ALB to reach */
    override val appBindsAllNetworkInterfaces: Boolean get() = true

    /**
     * Health-check path the ALB polls; must return 2xx/3xx when the app is alive. Defaults to the
     * server's autodetected `/meta/online` liveness endpoint (see [detectedOnlinePath]), falling
     * back to `/meta/online` by convention. Deliberately a *liveness* path, not the deep
     * `/meta/health`, so a slow downstream service can't make the ALB drain the whole fleet.
     */
    public open val healthCheckPath: String get() = detectedOnlinePath ?: "/meta/online"

    /** Grace period (seconds) before ASG health checks can mark a new instance unhealthy. */
    public open val healthCheckGracePeriodSeconds: Int get() = 300

    /** Target average CPU utilization (percent) for the CPU target-tracking scaling policy. */
    public open val scalingCpuTargetPercent: Int get() = 50

    /**
     * Optional second, simultaneous scaling policy targeting a number of requests per instance
     * (ALB `RequestCountPerTarget`). When set, the ASG scales out if *either* this or CPU wants
     * more capacity, and scales in only when *both* agree it is safe. This catches I/O-bound
     * saturation — high concurrency at low CPU because coroutines park cheaply on I/O — that CPU
     * alone misses. Null disables it (CPU-only). A typical value is a few hundred.
     */
    public open val scalingRequestsPerTarget: Int? get() = null

    /**
     * Optional maximum lifetime (seconds) for an instance before the ASG replaces it. Forces the
     * fleet to periodically rotate onto the latest (patched) golden AMI even without a deploy.
     * Null leaves instances running indefinitely. AWS requires at least 86400 (one day).
     */
    public open val maxInstanceLifetimeSeconds: Int? get() = null

    /** Whether to email the emergency contact when the Auto Scaling Group scales out or in. */
    public open val scaleNotificationsEnabled: Boolean get() = true

    /**
     * Whether the ALB strips HTTP headers with invalid field names. Hardening against
     * request-smuggling, but note AWS considers header names containing **underscores** invalid,
     * so `X_Custom_Header`-style headers would be dropped. Turn off if your API relies on them.
     */
    public open val dropInvalidHeaderFields: Boolean get() = true

    /**
     * How many instances the rolling redeploy updates at once (default 1 — safest). The
     * `LS_REDEPLOY_BATCH` environment variable overrides this at apply time for an emergency
     * faster rollout. Larger batches deploy faster but reduce the safety margin if the new build
     * is bad.
     */
    public open val redeployBatchSize: Int get() = 1

    /** ALB idle timeout (seconds). Kept high to support long-lived WebSocket connections. */
    public open val albIdleTimeoutSeconds: Int get() = 4000

    /** Connection-draining time (seconds) when an instance is removed from the target group. */
    public open val deregistrationDelaySeconds: Int get() = 30

    /** Minimum healthy percentage to maintain during an ASG instance refresh. */
    public open val instanceRefreshMinHealthyPercent: Int get() = 90

    /** Whether to enable ALB access logs to a dedicated S3 bucket. */
    public open val albAccessLogsEnabled: Boolean get() = true

    /** Retention (days) for ALB access logs before they expire. */
    public open val albAccessLogRetentionDays: Int get() = 90

    /**
     * Whether to attach an AWS WAFv2 web ACL (AWS managed common + known-bad-inputs rule sets) to the ALB.
     * Off by default — WAF adds hourly + per-request cost and can block legitimate traffic if rules are tuned
     * too aggressively, so it's an opt-in.
     */
    public open val wafEnabled: Boolean get() = false

    /**
     * Salt folded into the golden-AMI version. Bump this to force a re-bake even when the install
     * script itself has not changed — which is how you pick up OS security patches: a re-bake
     * starts from the latest Ubuntu base and re-runs `apt-get upgrade`. To rebuild on a schedule
     * (e.g. monthly), drive this from a date, for example `get() = "2026-06"`; changing it on
     * each apply produces a fresh, fully-patched AMI and a rolling instance refresh onto it.
     */
    public open val baseImageSalt: String get() = "1"

    /**
     * AWS-managed EC2 Image Builder components installed before the custom component. These replace
     * hand-rolled install steps with AWS-maintained ones (e.g. the AWS CLI and the CloudWatch agent).
     */
    public open val imageManagedComponents: List<ImageComponent>
        get() = listOf(ImageComponent("aws-cli-version-2-linux"), ImageComponent("amazon-cloudwatch-agent-linux"))

    /**
     * Components applied *after* everything is installed — typically OS hardening. Empty by default; enable
     * STIG with the [stigBuildLinux] shortcut, e.g. `override val hardeningComponents = listOf(stigBuildLinux(StigLevel.Low))`.
     */
    public open val hardeningComponents: List<ImageComponent>
        get() = emptyList()

    /** Seconds apt waits for the dpkg lock (held briefly at boot by Ubuntu's apt-daily) before failing. */
    public open val aptLockTimeoutSeconds: Int get() = 600

    /** A full component ARN passes through unchanged; a short name expands to the latest AWS-managed version. */
    private fun managedComponentArn(name: String): String =
        if (name.startsWith("arn:")) name else "arn:aws:imagebuilder:$applicationRegion:aws:component/$name/x.x.x"

    /** Render an [ImageComponent] as a recipe `component` block, including a `parameter` list when present. */
    private fun ImageComponent.toRecipeComponent(): JsonElement = terraformJsonObject {
        "component_arn" - managedComponentArn(arnOrName)
        if (parameters.isNotEmpty()) {
            "parameter" - parameters.entries.map<Map.Entry<String, String>, JsonElement> { (k, v) ->
                terraformJsonObject { "name" - k; "value" - v }
            }
        }
    }

    override fun validateConfiguration() {
        // The fleet relies on the shared cache to ensure scheduled tasks run once across all
        // instances. A per-instance RAM cache would run every schedule on every instance.
        val cacheJson = settings["cache"]
        val url = when (cacheJson) {
            is JsonPrimitive -> cacheJson.contentOrNull
            is JsonObject -> (cacheJson["url"] as? JsonPrimitive)?.contentOrNull
            else -> null
        }?.trim()?.lowercase()
        if (url != null && (url.isEmpty() || url == "ram" || url.startsWith("ram://"))) {
            throw IllegalStateException(
                "TerraformAwsScalingEc2Builder requires a distributed cache (e.g. redis://, " +
                        "memcached://, dynamodb://) so scheduled tasks coordinate across the fleet, " +
                        "but the configured cache is '$url'. A per-instance RAM cache would run every " +
                        "scheduled task on every instance."
            )
        }
    }

    override fun emitDeploymentSpecific() {
        (applicationVpc as? VpcInfoTerraformManaged)?.also { emitVpc(it, enableIPv6) }
        emitSecurityGroups()
        emitImageBuilder()
        emitAlb()
        emitAutoScaling()
        emitFleetRedeploy()
        emitDnsResources()
        emitMonitoringResources()
    }

    private val albIpAddressType: String
        get() = if (enableIPv6) "dualstack" else "ipv4"

    // === Security groups ===

    private fun emitSecurityGroups() {
        emit("network") {
            // ALB security group: accepts public HTTP/HTTPS, forwards to the instances.
            "resource.aws_security_group.alb" {
                "name" - "$projectPrefix-alb"
                "description" - "$displayName load balancer"
                "vpc_id" - applicationVpc.id
            }
            // Instance security group: only the ALB may reach the application port.
            "resource.aws_security_group.instance" {
                "name" - "$projectPrefix-instance"
                "description" - "$displayName application instances"
                "vpc_id" - applicationVpc.id
            }

            // ALB ingress (public) + egress to instances.
            if (enableIPv4) {
                "resource.aws_vpc_security_group_ingress_rule.alb_https" {
                    "security_group_id" - expression("aws_security_group.alb.id")
                    "ip_protocol" - "tcp"
                    "from_port" - 443
                    "to_port" - 443
                    "cidr_ipv4" - "0.0.0.0/0"
                }
                "resource.aws_vpc_security_group_ingress_rule.alb_http" {
                    "security_group_id" - expression("aws_security_group.alb.id")
                    "ip_protocol" - "tcp"
                    "from_port" - 80
                    "to_port" - 80
                    "cidr_ipv4" - "0.0.0.0/0"
                }
            }
            if (enableIPv6) {
                "resource.aws_vpc_security_group_ingress_rule.alb_https_v6" {
                    "security_group_id" - expression("aws_security_group.alb.id")
                    "ip_protocol" - "tcp"
                    "from_port" - 443
                    "to_port" - 443
                    "cidr_ipv6" - "::/0"
                }
                "resource.aws_vpc_security_group_ingress_rule.alb_http_v6" {
                    "security_group_id" - expression("aws_security_group.alb.id")
                    "ip_protocol" - "tcp"
                    "from_port" - 80
                    "to_port" - 80
                    "cidr_ipv6" - "::/0"
                }
            }
            "resource.aws_vpc_security_group_egress_rule.alb_to_instance" {
                "security_group_id" - expression("aws_security_group.alb.id")
                "referenced_security_group_id" - expression("aws_security_group.instance.id")
                "ip_protocol" - "tcp"
                "from_port" - appPort
                "to_port" - appPort
            }

            // Instance ingress from the ALB only, egress anywhere (for S3/NAT).
            "resource.aws_vpc_security_group_ingress_rule.instance_from_alb" {
                "security_group_id" - expression("aws_security_group.instance.id")
                "referenced_security_group_id" - expression("aws_security_group.alb.id")
                "ip_protocol" - "tcp"
                "from_port" - appPort
                "to_port" - appPort
            }
            "resource.aws_vpc_security_group_egress_rule.instance_outbound" {
                "security_group_id" - expression("aws_security_group.instance.id")
                "ip_protocol" - -1
                "cidr_ipv4" - "0.0.0.0/0"
            }
            if (enableIPv6) {
                "resource.aws_vpc_security_group_egress_rule.instance_outbound_v6" {
                    "security_group_id" - expression("aws_security_group.instance.id")
                    "ip_protocol" - -1
                    "cidr_ipv6" - "::/0"
                }
            }
        }
    }

    // === Golden AMI (EC2 Image Builder) ===

    /** Semver-ish version derived from the install script so the AMI re-bakes only on change. */
    private val imageVersion: String by lazy {
        // Fold the component lists in too, so changing managed/hardening components re-bakes the AMI.
        val saltInput = imageInstallScript() + baseImageSalt +
                imageManagedComponents.joinToString() +
                hardeningComponents.joinToString() +
                jvmArgs.joinToString()
        val patch = saltInput.hashCode().absoluteValue % 100000
        "1.${expression("regex(\"[0-9]{8}\", data.aws_ami.ubuntu.name)")}.$patch"
    }

    private fun emitImageBuilder() {
        emit("image") {
            // Base Ubuntu 24.04 AMI used as the Image Builder parent image.
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
                    },
                )
            }

            // IAM role/instance-profile for the transient Image Builder build instance.
            "resource.aws_iam_role.imagebuilder" {
                "name" - "$projectPrefix-imagebuilder-role"
                "assume_role_policy" - Json.encodeToString(buildJsonObject {
                    put("Version", "2012-10-17")
                    putJsonArray("Statement") {
                        addJsonObject {
                            put("Action", "sts:AssumeRole")
                            put("Effect", "Allow")
                            put("Principal", buildJsonObject { put("Service", "ec2.amazonaws.com") })
                        }
                    }
                })
            }
            "resource.aws_iam_role_policy_attachment.imagebuilder_core" {
                "role" - expression("aws_iam_role.imagebuilder.name")
                "policy_arn" - "arn:aws:iam::aws:policy/EC2InstanceProfileForImageBuilder"
            }
            "resource.aws_iam_role_policy_attachment.imagebuilder_ssm" {
                "role" - expression("aws_iam_role.imagebuilder.name")
                "policy_arn" - "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
            }
            "resource.aws_iam_instance_profile.imagebuilder" {
                "name" - "$projectPrefix-imagebuilder-profile"
                "role" - expression("aws_iam_role.imagebuilder.name")
            }

            // The install component: bakes base tooling + the redeploy script + systemd unit.
            "resource.aws_imagebuilder_component.install" {
                "name" - "$projectPrefix-install"
                "platform" - "Linux"
                "version" - imageVersion
                "data" - expression("local.image_data")
            }

            "resource.aws_imagebuilder_image_recipe.this" {
                "name" - "$projectPrefix-recipe"
                "version" - imageVersion
                "parent_image" - expression("data.aws_ami.ubuntu.id")
                // Ordered: AWS-managed installs first, then our custom component, then hardening last so it
                // locks down the fully-built image.
                "component" - buildList<JsonElement> {
                    imageManagedComponents.forEach { add(it.toRecipeComponent()) }
                    add(terraformJsonObject { "component_arn" - expression("aws_imagebuilder_component.install.arn") })
                    hardeningComponents.forEach { add(it.toRecipeComponent()) }
                }
                "block_device_mapping" {
                    "device_name" - "/dev/sda1"
                    "ebs" {
                        "volume_size" - volumeSizeGiB
                        "volume_type" - "gp3"
                        "encrypted" - true
                        "delete_on_termination" - true
                    }
                }
                "lifecycle" {
                    "create_before_destroy" - true
                }
            }

            "resource.aws_imagebuilder_infrastructure_configuration.this" {
                "name" - "$projectPrefix-infra"
                "instance_profile_name" - expression("aws_iam_instance_profile.imagebuilder.name")
                "instance_types" - listOf(instanceType)
                // Build in a private subnet (no public IP); egress for apt/curl/SSM goes through
                // NAT, and S3 through the gateway endpoint. No reason to expose the build host.
                "subnet_id" - expression("${applicationVpc.privateSubnets.trimExpression()}[0]")
                "security_group_ids" - listOf(expression("aws_security_group.instance.id"))
                "terminate_instance_on_failure" - true
                "instance_metadata_options" {
                    "http_tokens" - "required"
                    "http_put_response_hop_limit" - 2
                }
            }

            "resource.aws_imagebuilder_distribution_configuration.this" {
                "name" - "$projectPrefix-dist"
                "distribution" {
                    "region" - applicationRegion
                    "ami_distribution_configuration" {
                        "name" - $$"$$projectPrefix-{{ imagebuilder:buildDate }}"
                    }
                }
            }

            "resource.aws_imagebuilder_image.this" {
                "image_recipe_arn" - expression("aws_imagebuilder_image_recipe.this.arn")
                "infrastructure_configuration_arn" - expression("aws_imagebuilder_infrastructure_configuration.this.arn")
                "distribution_configuration_arn" - expression("aws_imagebuilder_distribution_configuration.this.arn")
                "image_tests_configuration" {
                    "image_tests_enabled" - false
                }
            }

            // Data script
            "locals" {
                emitExtra("image_data.yaml", imageComponentYaml())
                val replacements = provisioningFragments
                    .flatMap { it.second.entries.map { "${it.key} = ${it.value}" } }
                    .joinToString(", ")
                "image_data" - $$"""${templatefile("${path.module}/image_data.yaml", { $$replacements })}"""
            }
        }
    }

    /** The bash script run by the Image Builder install component to produce the golden AMI. */
    private fun imageInstallScript(): String {
        validateCustomInputs()
        return buildString {
            appendLine("#!/bin/bash")
            appendLine("set -euo pipefail")
            appendLine("export DEBIAN_FRONTEND=noninteractive")
            // A freshly-booted Ubuntu holds the dpkg lock for the first few minutes (apt-daily/
            // unattended-upgrades); wait for it instead of failing or appearing to hang. We intentionally
            // do NOT run a blanket `apt-get upgrade` — the AWS-managed `update-linux` component (or a
            // baseImageSalt bump onto the latest base AMI) is the controlled way to pick up patches.
            val apt = "apt-get -o DPkg::Lock::Timeout=$aptLockTimeoutSeconds"
            appendLine("$apt update -y")
            appendLine("$apt install -y openjdk-17-jre-headless openssl curl gnupg ca-certificates unzip")
            if (additionalPackages.isNotEmpty()) {
                appendLine("$apt install -y ${additionalPackages.joinToString(" ") { it.shellEscape() }}")
            }
            // The AWS CLI and the CloudWatch agent binary are installed by AWS-managed components
            // (see imageManagedComponents) that run before this one; we only write the agent config here.
            cloudwatchAgentConfig()
            ssm()
            systemD()
            // Bake on-box agents (e.g. the OTel collector) into the AMI. They install + `enable`
            // here but do not start; the ASG starts them at boot, where the instance role can fetch
            // any secrets they need from SSM.
            runProvisioningFragments()
            // The redeploy script reads the bucket + region from deploy.env written at boot, and
            // validates the new build against the local liveness endpoint before declaring success.
            instanceRedeployScript(
                """if [ -f /etc/lightning-server/deploy.env ]; then . /etc/lightning-server/deploy.env; fi
BUCKET="${'$'}DEPLOYMENT_BUCKET"
REGION="${'$'}AWS_REGION_NAME"""",
                localHealthUrl = "http://localhost:$appPort$healthCheckPath",
            )
            if (instanceFiles.isNotEmpty() || instanceFilesRaw.isNotEmpty()) instanceFiles()
            // Enable (but do not start) the service so it auto-starts after the boot-time deploy.
            appendLine("systemctl enable $projectPrefix || true")
            appendLine("systemctl enable amazon-cloudwatch-agent || true")
        }
    }

    /** Wraps [imageInstallScript] in an EC2 Image Builder component YAML document. */
    private fun imageComponentYaml(): String {
        val indentedScript = imageInstallScript().lines().joinToString("\n") { "              $it" }
        return """
name: $projectPrefix-install
description: Install base tooling for $displayName
schemaVersion: 1.0
phases:
  - name: build
    steps:
      - name: Install
        action: ExecuteBash
        inputs:
          commands:
            - |
$indentedScript
""".trim()
    }

    // === ALB, ACM, listeners ===

    private fun emitAlb() {
        if (albAccessLogsEnabled) emitAlbAccessLogBucket()
        emit("alb") {
            "resource.aws_acm_certificate.this" {
                "domain_name" - domain
                if (wsDomain != domain) "subject_alternative_names" - listOf(wsDomain)
                "validation_method" - "DNS"
                "lifecycle" {
                    "create_before_destroy" - true
                }
            }
            // DNS records that prove domain ownership for the certificate.
            "resource.aws_route53_record.cert_validation" {
                "for_each" - expression(
                    $$"""{
    for dvo in aws_acm_certificate.this.domain_validation_options : dvo.domain_name => {
      name   = dvo.resource_record_name
      record = dvo.resource_record_value
      type   = dvo.resource_record_type
    }
  }"""
                )
                "zone_id" - domainZoneId
                "name" - expression("each.value.name")
                "type" - expression("each.value.type")
                "ttl" - 60
                "records" - listOf(expression("each.value.record"))
                "allow_overwrite" - true
            }
            "resource.aws_acm_certificate_validation.this" {
                "certificate_arn" - expression("aws_acm_certificate.this.arn")
                "validation_record_fqdns" - expression("[for record in aws_route53_record.cert_validation : record.fqdn]")
            }

            "resource.aws_lb.app" {
                "name" - "$projectPrefix-alb"
                "load_balancer_type" - "application"
                "internal" - false
                "ip_address_type" - albIpAddressType
                "security_groups" - listOf(expression("aws_security_group.alb.id"))
                "subnets" - applicationVpc.publicSubnets
                "drop_invalid_header_fields" - dropInvalidHeaderFields
                "idle_timeout" - albIdleTimeoutSeconds
                if (albAccessLogsEnabled) {
                    "access_logs" {
                        "bucket" - expression("aws_s3_bucket.alb_logs.id")
                        "prefix" - projectPrefix
                        "enabled" - true
                    }
                    // The bucket policy must exist before the ALB validates it can write logs.
                    "depends_on" - listOf("aws_s3_bucket_policy.alb_logs")
                }
            }

            "resource.aws_lb_target_group.app" {
                "name" - "$projectPrefix-tg"
                "port" - appPort
                "protocol" - "HTTP"
                "vpc_id" - applicationVpc.id
                "target_type" - "instance"
                "deregistration_delay" - deregistrationDelaySeconds
                "health_check" {
                    "enabled" - true
                    "path" - healthCheckPath
                    "protocol" - "HTTP"
                    "matcher" - "200-399"
                    "interval" - 15
                    "timeout" - 5
                    "healthy_threshold" - 2
                    "unhealthy_threshold" - 3
                }
            }

            "resource.aws_lb_listener.https" {
                "load_balancer_arn" - expression("aws_lb.app.arn")
                "port" - 443
                "protocol" - "HTTPS"
                "ssl_policy" - "ELBSecurityPolicy-TLS13-1-2-2021-06"
                "certificate_arn" - expression("aws_acm_certificate_validation.this.certificate_arn")
                "default_action" {
                    "type" - "forward"
                    "target_group_arn" - expression("aws_lb_target_group.app.arn")
                }
            }
            "resource.aws_lb_listener.http" {
                "load_balancer_arn" - expression("aws_lb.app.arn")
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
            if (wafEnabled) emitWaf()
        }
    }

    /** Regional WAFv2 web ACL with AWS-managed rule sets, associated with the ALB. */
    private fun TerraformJsonObject.emitWaf() {
        fun managedRule(ruleName: String, priority: Int): JsonElement = terraformJsonObject {
            "name" - ruleName
            "priority" - priority
            "override_action" { "none" { } }
            "statement" {
                "managed_rule_group_statement" {
                    "name" - ruleName
                    "vendor_name" - "AWS"
                }
            }
            "visibility_config" {
                "cloudwatch_metrics_enabled" - true
                "metric_name" - "$projectPrefix-$ruleName"
                "sampled_requests_enabled" - true
            }
        }
        "resource.aws_wafv2_web_acl.main" {
            "name" - "$projectPrefix-waf"
            "scope" - "REGIONAL"
            "default_action" { "allow" { } }
            "rule" - listOf<JsonElement>(
                managedRule("AWSManagedRulesCommonRuleSet", 1),
                managedRule("AWSManagedRulesKnownBadInputsRuleSet", 2),
            )
            "visibility_config" {
                "cloudwatch_metrics_enabled" - true
                "metric_name" - "$projectPrefix-waf"
                "sampled_requests_enabled" - true
            }
        }
        "resource.aws_wafv2_web_acl_association.main" {
            "resource_arn" - expression("aws_lb.app.arn")
            "web_acl_arn" - expression("aws_wafv2_web_acl.main.arn")
        }
    }

    private fun emitAlbAccessLogBucket() {
        emit("alb") {
            "resource.aws_s3_bucket.alb_logs" {
                "bucket_prefix" - "${projectPrefix.lowercase().replace("_", "")}-alb-logs"
                "force_destroy" - true
            }
            "resource.aws_s3_bucket_public_access_block.alb_logs" {
                "bucket" - expression("aws_s3_bucket.alb_logs.id")
                "block_public_acls" - true
                "block_public_policy" - true
                "ignore_public_acls" - true
                "restrict_public_buckets" - true
            }
            "resource.aws_s3_bucket_server_side_encryption_configuration.alb_logs" {
                "bucket" - expression("aws_s3_bucket.alb_logs.id")
                "rule" {
                    "blocked_encryption_types" - listOf("SSE-C")
                    "apply_server_side_encryption_by_default" {
                        "sse_algorithm" - "AES256"
                    }
                }
            }
            "resource.aws_s3_bucket_lifecycle_configuration.alb_logs" {
                "bucket" - expression("aws_s3_bucket.alb_logs.id")
                "rule" {
                    "id" - "expire"
                    "status" - "Enabled"
                    "filter" {}
                    "expiration" {
                        "days" - albAccessLogRetentionDays
                    }
                }
            }
            // Allow the ELB log-delivery service to write access logs. Granting the service principal
            // (rather than the deprecated per-region `aws_elb_service_account`) is AWS's current method and
            // is the only one that works in regions launched after August 2022.
            "data.aws_iam_policy_document.alb_logs" {
                "statement" {
                    "actions" - listOf("s3:PutObject")
                    "resources" - listOf($$"${aws_s3_bucket.alb_logs.arn}/$$projectPrefix/*")
                    "principals" {
                        "type" - "Service"
                        "identifiers" - listOf("logdelivery.elasticloadbalancing.amazonaws.com")
                    }
                }
            }
            "resource.aws_s3_bucket_policy.alb_logs" {
                "bucket" - expression("aws_s3_bucket.alb_logs.id")
                "policy" - expression("data.aws_iam_policy_document.alb_logs.json")
            }
        }
    }

    // === Launch template + Auto Scaling Group ===

    private fun emitAutoScaling() {
        emitExtra("boot.sh", bootScript())
        emit("asg") {
            "resource.aws_launch_template.app" {
                "name_prefix" - "$projectPrefix-"
                "image_id" - expression("tolist(aws_imagebuilder_image.this.output_resources[0].amis)[0].image")
                "instance_type" - instanceType
                "update_default_version" - true
                "iam_instance_profile" {
                    "name" - expression("aws_iam_instance_profile.ec2.name")
                }
                "vpc_security_group_ids" - listOf(
                    expression("aws_security_group.instance.id"),
                    applicationVpc.securityGroup,
                )
                // Enforce IMDSv2 to mitigate SSRF-based credential theft.
                "metadata_options" {
                    "http_tokens" - "required"
                    "http_endpoint" - "enabled"
                    "http_put_response_hop_limit" - 1
                }
                "block_device_mappings" {
                    "device_name" - "/dev/sda1"
                    "ebs" {
                        "volume_size" - volumeSizeGiB
                        "volume_type" - "gp3"
                        "encrypted" - true
                        sharedKmsKeyArn?.let { "kms_key_id" - it }
                        "delete_on_termination" - true
                    }
                }
                "user_data" - expression(
                    $$"""base64encode(templatefile("${path.module}/boot.sh", { deployment_bucket = aws_s3_bucket.deployment.id, aws_region = "$$applicationRegion" }))"""
                )
                "tag_specifications" {
                    "resource_type" - "instance"
                    "tags" {
                        "Name" - displayName
                    }
                }
            }

            "resource.aws_autoscaling_group.app" {
                "name_prefix" - "$projectPrefix-"
                "min_size" - minSize
                "max_size" - maxSize
                "desired_capacity" - desiredCapacity
                maxInstanceLifetimeSeconds?.let { "max_instance_lifetime" - it }
                "vpc_zone_identifier" - applicationVpc.privateSubnets
                "health_check_type" - "ELB"
                "health_check_grace_period" - healthCheckGracePeriodSeconds
                "target_group_arns" - listOf(expression("aws_lb_target_group.app.arn"))
                "launch_template" {
                    "id" - expression("aws_launch_template.app.id")
                    "version" - expression("aws_launch_template.app.latest_version")
                }
                // Set the initial size, but never fight the scaling policy on later applies —
                // otherwise every apply would reset the count to desiredCapacity and terminate
                // any instances the policy had added.
                "lifecycle" {
                    "ignore_changes" - listOf("desired_capacity")
                }
                "instance_refresh" {
                    "strategy" - "Rolling"
                    "preferences" {
                        "min_healthy_percentage" - instanceRefreshMinHealthyPercent
                        "auto_rollback" - true
                    }
                    // Launch template changes implicitly trigger a refresh; no explicit trigger needed.
                }
                "tag" - listOf(
                    terraformJsonObject {
                        "key" - "Name"
                        "value" - displayName
                        "propagate_at_launch" - true
                    },
                    terraformJsonObject {
                        "key" - "lightning-server-asg"
                        "value" - projectPrefix
                        "propagate_at_launch" - true
                    },
                )
                "depends_on" - (listOf(
                    "null_resource.upload_jar",
                    "null_resource.upload_settings",
                    "aws_ssm_parameter.settings_password",
                ) + instanceSecretDependencies)
            }

            // Target-tracking scaling on average CPU. Catches compute-bound saturation
            // (serialization, encryption, compression).
            "resource.aws_autoscaling_policy.cpu" {
                "name" - "$projectPrefix-cpu-target"
                "autoscaling_group_name" - expression("aws_autoscaling_group.app.name")
                "policy_type" - "TargetTrackingScaling"
                "target_tracking_configuration" {
                    "predefined_metric_specification" {
                        "predefined_metric_type" - "ASGAverageCPUUtilization"
                    }
                    "target_value" - scalingCpuTargetPercent
                }
            }

            // Optional second policy on requests per instance. Runs alongside the CPU policy:
            // the ASG scales out on the max of the two and scales in only when both agree.
            scalingRequestsPerTarget?.let { target ->
                "resource.aws_autoscaling_policy.requests" {
                    "name" - "$projectPrefix-request-target"
                    "autoscaling_group_name" - expression("aws_autoscaling_group.app.name")
                    "policy_type" - "TargetTrackingScaling"
                    "target_tracking_configuration" {
                        "predefined_metric_specification" {
                            "predefined_metric_type" - "ALBRequestCountPerTarget"
                            // "<alb-arn-suffix>/<target-group-arn-suffix>" identifies which
                            // target group's request count to track.
                            "resource_label" - $$"${aws_lb.app.arn_suffix}/${aws_lb_target_group.app.arn_suffix}"
                        }
                        "target_value" - target
                    }
                }
            }
        }
    }

    /**
     * Minimal per-boot user-data: it records the deployment bucket + region for the baked
     * redeploy script, then runs that script to fetch the latest JAR + settings and start the
     * service. Everything else is already baked into the golden AMI.
     *
     * `${'$'}{deployment_bucket}` / `${'$'}{aws_region}` are filled by terraform `templatefile()`.
     */
    private fun bootScript(): String = $$"""
#!/bin/bash
set -euo pipefail
mkdir -p /etc/lightning-server
cat > /etc/lightning-server/deploy.env << 'DEPLOY_ENV_EOF'
DEPLOYMENT_BUCKET=${deployment_bucket}
AWS_REGION_NAME=${aws_region}
DEPLOY_ENV_EOF

# Fetch the latest application + settings and start the service. The baked redeploy script
# rolls back to the previous version on failure; on a brand-new instance there is nothing to
# roll back to, so a hard failure here leaves the instance unhealthy and the ASG replaces it.
/usr/local/bin/lightning-server-redeploy
systemctl enable $$projectPrefix || true
""".trim()

    // === Rolling in-place fleet redeploy ===

    private fun emitFleetRedeploy() {
        emit("redeploy") {
            emitExtra("redeploy-fleet.sh", fleetRedeployScript())
            "resource.null_resource.redeploy_app" {
                "triggers" {
                    "jar_hash" - expression("data.external.jar_hash.result.hash")
                    "settings_hash" - expression("local_sensitive_file.settings_raw.content_sha256")
                }
                "depends_on" - listOf(
                    "null_resource.upload_jar",
                    "null_resource.upload_settings",
                    "aws_autoscaling_group.app",
                    "aws_lb_target_group.app",
                    "aws_ssm_parameter.settings_password",
                )
                "provisioner" {
                    "local-exec" {
                        "command" - $$"""bash ${path.module}/redeploy-fleet.sh ${aws_autoscaling_group.app.name} $$applicationRegion ${aws_lb_target_group.app.arn}"""
                    }
                }
            }
        }
    }

    // language="Shell Script"
    private fun fleetRedeployScript(): String = $$"""
#!/usr/bin/env bash
# Rolling, in-place redeploy across the Auto Scaling Group. Updates instances in batches of
# $BATCH (default 1; override with LS_REDEPLOY_BATCH for an emergency faster rollout). For each
# instance: drain it from the ALB, run the on-instance redeploy via SSM (which validates the new
# build against the local liveness endpoint and self-rolls-back on failure), and return it to
# service only once the ALB reports it healthy. Any failure halts the rollout so it surfaces to
# `terraform apply`.
#
# The ASG is told to suspend the processes that would otherwise fight us — HealthCheck and
# ReplaceUnhealthy (so it can't terminate a drained instance) and AddToLoadBalancer/AZRebalance.
# An EXIT trap always resumes them and re-registers every in-service instance, so even an abrupt
# termination leaves the fleet serving rather than stranded.
set -euo pipefail

ASG_NAME="${1:?usage: redeploy-fleet.sh <asg-name> <region> <target-group-arn>}"
REGION="${2:?region required}"
TG_ARN="${3:?target group arn required}"
APP_PORT="$$appPort"
BATCH="${LS_REDEPLOY_BATCH:-$$redeployBatchSize}"
SUSPENDED="HealthCheck ReplaceUnhealthy AZRebalance AddToLoadBalancer"

log() { echo "[redeploy-fleet] $*"; }
err() { echo "[redeploy-fleet] ERROR: $*" >&2; }

instance_ids() {
    aws autoscaling describe-auto-scaling-groups \
        --auto-scaling-group-names "$ASG_NAME" \
        --region "$REGION" \
        --query 'AutoScalingGroups[0].Instances[?LifecycleState==`InService`].InstanceId' \
        --output text
}

# Always restore the ASG to a clean state, even on abnormal exit: resume the suspended processes
# and make sure every in-service instance is registered with the target group.
resume_and_restore() {
    log "Resuming ASG processes and re-registering all in-service instances"
    aws autoscaling resume-processes --auto-scaling-group-name "$ASG_NAME" \
        --scaling-processes $SUSPENDED --region "$REGION" || true
    local id
    for id in $(instance_ids); do
        aws elbv2 register-targets --target-group-arn "$TG_ARN" \
            --targets "Id=$id,Port=$APP_PORT" --region "$REGION" || true
    done
}
trap resume_and_restore EXIT

wait_ssm_online() {
    local id="$1"
    for i in $(seq 1 60); do
        status=$(aws ssm describe-instance-information \
            --filters "Key=InstanceIds,Values=$id" --region "$REGION" \
            --query 'InstanceInformationList[0].PingStatus' --output text 2>/dev/null || echo "None")
        [ "$status" = "Online" ] && return 0
        sleep 5
    done
    err "SSM agent never came Online for $id"
    return 1
}

run_redeploy() {
    local id="$1"
    local cmd_id
    cmd_id=$(aws ssm send-command \
        --instance-ids "$id" \
        --document-name "AWS-RunShellScript" \
        --comment "terraform rolling redeploy" \
        --parameters 'commands=/usr/local/bin/lightning-server-redeploy,executionTimeout=600' \
        --region "$REGION" --query 'Command.CommandId' --output text)
    for i in $(seq 1 180); do
        status=$(aws ssm get-command-invocation --command-id "$cmd_id" --instance-id "$id" \
            --region "$REGION" --query 'Status' --output text 2>/dev/null || echo "Pending")
        case "$status" in
            Success) return 0 ;;
            Cancelled|Failed|TimedOut)
                err "redeploy on $id finished with status: $status"
                aws ssm get-command-invocation --command-id "$cmd_id" --instance-id "$id" \
                    --region "$REGION" --query 'StandardErrorContent' --output text >&2 || true
                return 1 ;;
            *) sleep 5 ;;
        esac
    done
    err "redeploy on $id did not finish within polling window"
    return 1
}

# Run pre-deploy tasks on one instance via SSM (in a scratch dir; the live server is untouched).
# Returns non-zero on any failure so the rollout can abort before touching the fleet.
run_predeploy() {
    local id="$1"
    local cmd_id
    cmd_id=$(aws ssm send-command \
        --instance-ids "$id" \
        --document-name "AWS-RunShellScript" \
        --comment "terraform pre-deploy" \
        --parameters 'commands=/usr/local/bin/lightning-server-predeploy,executionTimeout=600' \
        --region "$REGION" --query 'Command.CommandId' --output text)
    for i in $(seq 1 180); do
        status=$(aws ssm get-command-invocation --command-id "$cmd_id" --instance-id "$id" \
            --region "$REGION" --query 'Status' --output text 2>/dev/null || echo "Pending")
        case "$status" in
            Success) return 0 ;;
            Cancelled|Failed|TimedOut)
                err "pre-deploy on $id finished with status: $status"
                aws ssm get-command-invocation --command-id "$cmd_id" --instance-id "$id" \
                    --region "$REGION" --query 'StandardErrorContent' --output text >&2 || true
                return 1 ;;
            *) sleep 5 ;;
        esac
    done
    err "pre-deploy on $id did not finish within polling window"
    return 1
}

# Drain -> redeploy -> validate healthy, for one instance. Returns non-zero on any failure.
process_instance() {
    local id="$1"
    log "=== Redeploying $id ==="
    wait_ssm_online "$id" || return 1

    log "Draining $id from the target group"
    aws elbv2 deregister-targets --target-group-arn "$TG_ARN" \
        --targets "Id=$id,Port=$APP_PORT" --region "$REGION"
    aws elbv2 wait target-deregistered --target-group-arn "$TG_ARN" \
        --targets "Id=$id,Port=$APP_PORT" --region "$REGION" || true

    if ! run_redeploy "$id"; then
        err "redeploy failed on $id (it self-heals to the previous version)"
        return 1
    fi

    log "Returning $id to service"
    aws elbv2 register-targets --target-group-arn "$TG_ARN" \
        --targets "Id=$id,Port=$APP_PORT" --region "$REGION"
    if ! aws elbv2 wait target-in-service --target-group-arn "$TG_ARN" \
        --targets "Id=$id,Port=$APP_PORT" --region "$REGION"; then
        err "$id did not become healthy after redeploy"
        return 1
    fi
    log "$id healthy"
}

log "Suspending ASG processes during redeploy: $SUSPENDED"
aws autoscaling suspend-processes --auto-scaling-group-name "$ASG_NAME" \
    --scaling-processes $SUSPENDED --region "$REGION"

IDS=$(instance_ids)
if [ -z "$IDS" ]; then
    log "No in-service instances found; nothing to redeploy."
    exit 0
fi

# Run pre-deploy tasks once, on one instance, before rolling the fleet. The whole fleet keeps
# serving the previous version while these run; a failure aborts the rollout (the EXIT trap resumes
# ASG processes and re-registers every instance, so the fleet is left untouched and serving).
FIRST_ID="${IDS%%[[:space:]]*}"
log "Running pre-deploy tasks on $FIRST_ID before rolling the fleet"
wait_ssm_online "$FIRST_ID" || exit 1
if ! run_predeploy "$FIRST_ID"; then
    err "Pre-deploy tasks failed; aborting rollout."
    exit 1
fi

# Process in batches of $BATCH, in parallel within a batch; fail the whole run if any member fails.
batch=()
flush_batch() {
    [ ${#batch[@]} -eq 0 ] && return 0
    local pids=() id p fail=0
    for id in "${batch[@]}"; do process_instance "$id" & pids+=("$!"); done
    for p in "${pids[@]}"; do wait "$p" || fail=1; done
    batch=()
    if [ "$fail" -ne 0 ]; then err "A redeploy in the batch failed; halting rollout."; exit 1; fi
}

for id in $IDS; do
    batch+=("$id")
    if [ ${#batch[@]} -ge "$BATCH" ]; then flush_batch; fi
done
flush_batch

log "Rolling redeploy complete."
""".trimIndent()

    // === DNS ===

    private fun emitDnsResources() {
        emit("dns") {
            "data.aws_route53_zone.main" {
                "name" - domainZone
            }
            emitAliasRecords(domain)
            if (wsDomain != domain) emitAliasRecords(wsDomain, suffix = "_ws")
        }
    }

    private fun TerraformJsonObject.emitAliasRecords(recordName: String, suffix: String = "") {
        if (enableIPv4) {
            "resource.aws_route53_record.alias_a$suffix" {
                "zone_id" - domainZoneId
                "name" - recordName
                "type" - "A"
                "alias" {
                    "name" - expression("aws_lb.app.dns_name")
                    "zone_id" - expression("aws_lb.app.zone_id")
                    "evaluate_target_health" - true
                }
            }
        }
        if (enableIPv6) {
            "resource.aws_route53_record.alias_aaaa$suffix" {
                "zone_id" - domainZoneId
                "name" - recordName
                "type" - "AAAA"
                "alias" {
                    "name" - expression("aws_lb.app.dns_name")
                    "zone_id" - expression("aws_lb.app.zone_id")
                    "evaluate_target_health" - true
                }
            }
        }
    }

    // === Monitoring & outputs ===

    private fun emitMonitoringResources() {
        emit("monitoring") {
            "resource.aws_cloudwatch_log_group.application" {
                "name" - "/ec2/$projectPrefix/application"
                "retention_in_days" - logRetentionDays
                sharedKmsKeyArn?.let { "kms_key_id" - it }
            }
            "resource.aws_sns_topic.emergency" {
                "name" - "${projectPrefix}_emergencies"
                sharedKmsKeyArn?.let { "kms_master_key_id" - it }
            }
            "resource.aws_sns_topic_subscription.emergency_primary" {
                "topic_arn" - expression("aws_sns_topic.emergency.arn")
                "protocol" - "email"
                "endpoint" - emergencyContact.raw
            }

            // Notify on every scale-out / scale-in so a fleet quietly resizing itself is visible.
            if (scaleNotificationsEnabled) {
                "resource.aws_autoscaling_notification.scaling" {
                    "group_names" - listOf(expression("aws_autoscaling_group.app.name"))
                    "notifications" - listOf(
                        "autoscaling:EC2_INSTANCE_LAUNCH",
                        "autoscaling:EC2_INSTANCE_TERMINATE",
                        "autoscaling:EC2_INSTANCE_LAUNCH_ERROR",
                        "autoscaling:EC2_INSTANCE_TERMINATE_ERROR",
                    )
                    "topic_arn" - expression("aws_sns_topic.emergency.arn")
                }
            }

            // No healthy targets behind the load balancer.
            "resource.aws_cloudwatch_metric_alarm.unhealthy_hosts" {
                "alarm_name" - "$projectPrefix-unhealthy-hosts"
                "alarm_description" - "One or more targets are failing ALB health checks"
                "namespace" - "AWS/ApplicationELB"
                "metric_name" - "UnHealthyHostCount"
                "statistic" - "Maximum"
                "period" - 60
                "evaluation_periods" - 3
                "threshold" - 0
                "comparison_operator" - "GreaterThanThreshold"
                "treat_missing_data" - "notBreaching"
                "dimensions" {
                    "TargetGroup" - expression("aws_lb_target_group.app.arn_suffix")
                    "LoadBalancer" - expression("aws_lb.app.arn_suffix")
                }
                "alarm_actions" - listOf(expression("aws_sns_topic.emergency.arn"))
            }

            // Elevated target 5xx responses.
            "resource.aws_cloudwatch_metric_alarm.target_5xx" {
                "alarm_name" - "$projectPrefix-target-5xx"
                "alarm_description" - "Elevated 5xx responses from application targets"
                "namespace" - "AWS/ApplicationELB"
                "metric_name" - "HTTPCode_Target_5XX_Count"
                "statistic" - "Sum"
                "period" - 300
                "evaluation_periods" - 3
                "threshold" - 25
                "comparison_operator" - "GreaterThanThreshold"
                "treat_missing_data" - "notBreaching"
                "dimensions" {
                    "LoadBalancer" - expression("aws_lb.app.arn_suffix")
                }
                "alarm_actions" - listOf(expression("aws_sns_topic.emergency.arn"))
            }

            // Fleet-wide high CPU (informational alongside the scaling policy).
            "resource.aws_cloudwatch_metric_alarm.high_cpu" {
                "alarm_name" - "$projectPrefix-high-cpu"
                "alarm_description" - "Sustained high CPU across the Auto Scaling Group"
                "namespace" - "AWS/EC2"
                "metric_name" - "CPUUtilization"
                "statistic" - "Average"
                "period" - 300
                "evaluation_periods" - 3
                "threshold" - 90
                "comparison_operator" - "GreaterThanThreshold"
                "dimensions" {
                    "AutoScalingGroupName" - expression("aws_autoscaling_group.app.name")
                }
                "alarm_actions" - listOf(expression("aws_sns_topic.emergency.arn"))
            }
        }

        emit("outputs") {
            "output.application_url" {
                "description" - "Application URL"
                "value" - "https://$domain"
            }
            "output.alb_dns_name" {
                "description" - "Public DNS name of the load balancer"
                "value" - expression("aws_lb.app.dns_name")
            }
        }
    }

    /**
     * VpcInfo subnet accessors are stored as full `${'$'}{...}` expression strings. The DSL
     * [expression] helper re-wraps, so strip the wrapper before re-wrapping to avoid `${'$'}{${'$'}{...}}`.
     */
    private fun String.trimExpression(): String =
        removePrefix("\${").removeSuffix("}")
}
