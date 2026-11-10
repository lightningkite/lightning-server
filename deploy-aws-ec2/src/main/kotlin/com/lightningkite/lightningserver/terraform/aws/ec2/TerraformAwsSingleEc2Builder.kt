package com.lightningkite.lightningserver.terraform.aws.ec2

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.services.Untested
import com.lightningkite.services.terraform.*
import org.intellij.lang.annotations.Language


/**
 * Terraform builder for deploying Lightning Server to a single AWS EC2 instance.
 *
 * This is the simplest, cheapest deployment: one instance with an Elastic IP, running the
 * application behind an on-box nginx reverse proxy that terminates TLS with a Let's Encrypt
 * certificate it obtains and renews itself through `nginx-module-acme`. Route53 A/AAAA records
 * point straight at the instance, and
 * code/settings updates are pushed in-place to that one instance via SSM Run Command.
 *
 * For a horizontally-scaled, load-balanced deployment see [TerraformAwsScalingEc2Builder].
 *
 * Example usage:
 * ```kotlin
 * object MyDeployment : TerraformAwsSingleEc2Builder<Server>(Server) {
 *     override val storageBucket = "my-terraform-state"
 *     override val region = Region.US_WEST_2
 *     override val displayName = "My Server"
 *     override val domainZone = "example.com"
 *     override val domain = "api.example.com"
 *     override val debug = false
 *     override val emergencyContact = EmailAddress("ops@example.com")
 *     override val instanceType = "t4g.medium"
 *     override val instanceArchitecture = CPUArchitecture.Arm
 *     override val applicationVpc = AwsVpc.Default
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
public abstract class TerraformAwsSingleEc2Builder<S : ServerBuilder>(
    builder: S,
) : TerraformAwsEc2BuilderBase<S>(builder), TerraformEmitterKnownIpAddresses {

    abstract override val applicationVpc: AwsVpc.EC2Safe

    override val applicationIpAddresses: String = TerraformJsonObject.expression("[aws_eip.main.public_ip]")

    public fun terraformManagedVPC(
        ipPrefix: String,
        availabilityZones: List<String>,
        natGateway: AwsVpc.NatGateway,
    ): AwsVpc.VpcInfo = VpcInfoTerraformManaged(
        ipPrefix = ipPrefix,
        availabilityZones = availabilityZones,
        natGateway = natGateway,
        id = TerraformJsonObject.expression("module.vpc.vpc_id"),
        securityGroup = TerraformJsonObject.expression("aws_security_group.internal.id"),
        privateSubnets = TerraformJsonObject.expression("module.vpc.private_subnets"),
        publicSubnets = TerraformJsonObject.expression("module.vpc.public_subnets"),
        applicationRouteTables = TerraformJsonObject.expression("module.vpc.public_route_table_ids"),
        natGatewayIps = TerraformJsonObject.expression("module.vpc.nat_public_ips")
    )

    /**
     * Uses a VPC that already exists and is not managed by this Terraform.
     *
     * @param instanceSubnet The subnet the EC2 instance is placed in.
     * @param instanceRouteTable The route table of [instanceSubnet]. Services that need routes (such as VPC peering) add them here.
     */
    public fun existingVPC(
        id: String,
        cidr: String,
        securityGroup: String,
        privateSubnets: List<String>,
        publicSubnets: List<String>,
        instanceSubnet: String,
        instanceRouteTable: String,
        natGatewayIps: List<String> = emptyList(),
    ): AwsVpc.VpcInfo = VpcInfoExisting(
        id = id,
        cidr = cidr,
        securityGroup = securityGroup,
        privateSubnets = privateSubnets.toTerraformList(),
        publicSubnets = publicSubnets.toTerraformList(),
        applicationRouteTables = listOf(instanceRouteTable).toTerraformList(),
        natGatewayIps = natGatewayIps.toTerraformList(),
        instanceSubnet = instanceSubnet,
    )

    // === SSH access (optional, for debugging) ===

    // sshAllowed with the related IP enabling is what will allow an ssh connection.
    public open val sshAllowedV4CIDR: List<String> get() =
        emptyList() // Example: 0.0.0.0/0, would allow everyone in the world to attempt to ssh in
    public open val sshAllowedV6CIDR: List<String> get() =
        emptyList() // Example: ::/0, would allow everyone in the world to attempt to ssh in


    /** Direct application only accessible internally; we force using nginx. */
    override val appBindsAllNetworkInterfaces: Boolean get() = false

    override fun registerProviders() {
        require(TerraformProviderImport.tls)
    }

    override fun emitDeploymentSpecific() {
        emitSshKeyPair()
        emitNetworkResources()
        (applicationVpc as? VpcInfoTerraformManaged)?.also {
            emitVpc(it, enableIPv6)
        }
        emitEIPResources()
        emitEc2Resources()
        emitDnsResources()
        emitMonitoringResources()
    }

    /** A generated SSH key pair, available for break-glass debugging access to the instance. */
    private fun emitSshKeyPair() {
        if (sshAllowedV4CIDR.isEmpty() && sshAllowedV6CIDR.isEmpty()) return
        emit("deployment") {
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
                    // Redeploy whenever a new settings.enc was uploaded: settings changes and every re-encryption
                    // (cipher change, rotated password). Encrypted-at-rest instances decrypt settings.enc on each
                    // start, so they must not keep a stale copy.
                    "settings_upload" - expression("null_resource.upload_settings.id")
                }

                "depends_on" - listOf(
                    "null_resource.upload_jar",
                    "null_resource.upload_settings",
                    "aws_instance.main",
                    "aws_iam_role_policy_attachment.ssm",
                    "aws_iam_role_policy_attachment.servicesAccess",
                    "aws_eip_association.main",
                    "aws_ssm_parameter.settings_password",
                )
                "provisioner" {
                    "local-exec" {
                        "command" - $$"bash ${path.module}/redeploy.sh ${aws_instance.main.id} $$applicationRegion"
                    }
                }
            }
        }

        emit("ec2") {
            // Get latest base AMI for the distro
            baseAmiData()

            // EC2 Instance
            "resource.aws_instance.main" {
                "ami" - expression("data.aws_ami.base.id")
                "instance_type" - instanceType
                "iam_instance_profile" - expression("aws_iam_instance_profile.ec2.name")

                if (sshAllowedV4CIDR.isNotEmpty() || sshAllowedV6CIDR.isNotEmpty()) {
                    "key_name" - expression("aws_key_pair.ec2.key_name")
                }

                "vpc_security_group_ids" - listOfNotNull(
                    expression("aws_security_group.ec2.id"),
                    (applicationVpc as? AwsVpc.VpcInfo)?.securityGroup,
                )
                when (val vpcInfo = applicationVpc) {
                    AwsVpc.Default -> {
                        "subnet_id" - expression("data.aws_subnets.main.ids[0]")
                    }

                    is AwsVpc.VpcInfo -> {
                        "subnet_id" - ((vpcInfo as? VpcInfoExisting)?.instanceSubnet
                            ?: expression("${vpcInfo.publicSubnets.removePrefix("\${").removeSuffix("}")}[0]"))
                    }
                }

                if (enableIPv6) {
                    "ipv6_address_count" - 1
                    "enable_primary_ipv6" - true
                }

                // Enforce IMDSv2 to mitigate SSRF-based credential theft.
                "metadata_options" {
                    "http_tokens" - "required"
                    "http_endpoint" - "enabled"
                    "http_put_response_hop_limit" - 1
                }

                "user_data" - expression("local.ec2_base_init")
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
                    "Name" - deploymentTag
                }

                // A new base AMI release must not replace the only instance during an unrelated apply.
                // ignore_changes does not apply on replacement, so any replacement (an init script
                // change, or `terraform apply -replace`) still moves to the latest AMI.
                "lifecycle" {
                    "ignore_changes" - listOf("ami")
                }

                "depends_on" - (listOf(
                    "null_resource.upload_jar",
                    "null_resource.upload_settings",
                    "aws_s3_object.init",
                    "aws_ssm_parameter.settings_password",
                    "aws_iam_role_policy_attachment.servicesAccess",
                    "aws_iam_role_policy_attachment.ssm",
                    "aws_iam_role_policy_attachment.cloudwatch_agent",
                    "aws_s3_bucket_server_side_encryption_configuration.deployment",
                ) + instanceSecretDependencies)
            }

            "resource.aws_s3_object.init" {
                "bucket" - expression("aws_s3_bucket.deployment.id")
                "key" - $$"init/component-${local.ec2_init_version}.sh"
                "content" - expression("local.ec2_init")
                "content_type" - "application/x-sh"
            }

            // User data script
            "locals" {
                emitExtra("ec2_init_base.sh", generateEC2BaseInit())
                emitExtra("ec2_init.sh", generateEC2Init())

                val baseReplacements = listOf(
                    "deployment_bucket = aws_s3_bucket.deployment.id",
                    "deployment_script_key = aws_s3_object.init.key",
                )
                    .joinToString(", ")
                "ec2_base_init" - $$"""${templatefile("${path.module}/ec2_init_base.sh", { $$baseReplacements })}"""

                val replacements = listOf(
                    "deployment_bucket = aws_s3_bucket.deployment.id",
                    *provisioningFragments.flatMap { it.second.entries.map { "${it.key} = ${it.value}" } }
                        .toTypedArray()
                )
                    .joinToString(", ")
                "ec2_init" - $$"""${templatefile("${path.module}/ec2_init.sh", { $$replacements })}"""

                "ec2_init_version" - expression(
                    "max(1, parseint(substr(sha256(jsonencode([local.ec2_init])), 0, 7), 16))"
                )
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
                    "records" - listOf(expression("aws_instance.main.ipv6_addresses[0]"))
                }


            if (wsDomain != domain) {
                // A Record pointing to EC2 for wsDomain
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
                        "records" - listOf(expression("aws_instance.main.ipv6_addresses[0]"))
                    }
            }
        }
    }

    private fun emitMonitoringResources() {
        emit("monitoring") {
            // CloudWatch Log Group for application logs
            "resource.aws_cloudwatch_log_group.application" {
                "name" - projectLogName
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
                    "InstanceId" - expression("aws_instance.main.id")
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
                    "InstanceId" - expression("aws_instance.main.id")
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
                    "InstanceId" - expression("aws_instance.main.id")
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
                    "InstanceId" - expression("aws_instance.main.id")
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

    private fun emitEIPResources() {
        emit("eip") {
            "resource.aws_eip.main" {
                "domain" - "vpc"
                "tags" {
                    "Name" - deploymentTag
                }
            }

            "resource.aws_eip_association.main" {
                "instance_id" - expression("aws_instance.main.id")
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
                        "description" - "$deploymentTag single-instance access"
                        "vpc_id" - expression("data.aws_vpc.default.id")
                    }
                }

                is AwsVpc.VpcInfo -> {

                    "resource.aws_security_group.ec2" {
                        "name" - "$projectPrefix-ec2"
                        "description" - "$deploymentTag single-instance access"
                        "vpc_id" - vpcInfo.id
                    }
                }
            }

            "resource.aws_vpc_security_group_egress_rule.access_outside" {
                "security_group_id" - expression("aws_security_group.ec2.id")
                "ip_protocol" - -1
                "cidr_ipv4" - "0.0.0.0/0"
            }

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

    private fun generateEC2BaseInit(): String {

        // language="Shell Script"
        return buildString {
            appendLine(
                $$"""#!/bin/bash
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

export BOOT_START=$(date +%s)

# === Logging Setup ===
WORKING_DIR="/var/lib/$$projectPrefix-init"
mkdir -p -m 0700 "$WORKING_DIR"
cd "$WORKING_DIR"

mkdir -p /var/log/$$projectPrefix
touch /var/log/$$projectPrefix/ec2_init.log
exec > >(tee /var/log/$$projectPrefix/ec2_init.log | logger -t ec2_init -s) 2>&1

# === Bootstrap packages ===
# Just enough to fetch ec2_init.sh; the full system update and package install happen there.
echo "[INFO] Installing bootstrap packages..."
$${
                    when (distro) {
                        Distro.Ubuntu2404, Distro.Ubuntu2604 -> """
apt-get -o DPkg::Lock::Timeout=$aptLockTimeoutSeconds update -y
apt-get -o DPkg::Lock::Timeout=$aptLockTimeoutSeconds install -y curl ca-certificates unzip
""".trim()
                        // curl (as curl-minimal) and ca-certificates ship in the AMI.
                        Distro.AmazonLinux2023 -> "dnf install -y unzip"
                    }
                }
"""
            )

            awsCli()

            appendLine(
                $$"""

download_with_retry() {
    local src="$1" dst="$2" max=5 attempt=1
    while [ $attempt -le $max ]; do
        echo "[INFO] Downloading $src (attempt $attempt/$max)"
        if aws s3 cp "$src" "$dst" --region "$$applicationRegion" --no-progress; then return 0; fi
        sleep $((attempt * 5))
        attempt=$((attempt + 1))
    done
    echo "[ERROR] Failed to download $src after $max attempts"
    return 1
}

echo "[INFO] Downloading EC2 Init script at $(date)"
download_with_retry "s3://${deployment_bucket}/${deployment_script_key}" "ec2_init.sh"
chmod +x ec2_init.sh

echo "[INFO] Running EC2 Init script at $(date)"
./ec2_init.sh
rm ec2_init.sh
"""
            )
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
export DEBIAN_FRONTEND=noninteractive

echo "[INFO] EC2 Init script started on ${distro.name} at $(date)"
"""
            )

            // Patching happens deliberately via os-update, never unattended on the live instance.
            disableUnattendedUpgrades()

            systemPackages()

            // Before the app is ever started, so a memory-tight instance has its safety net from
            // the very first boot.
            swap()

            cloudwatchAgent(emitter.applicationRegion)

            nginxInstall()

            ssm()

            systemD()

            runProvisioningFragments()

            val localHealthUrl = "http://localhost:8080${healthCheckPath}"

            // The single instance fills the bucket name via terraform templatefile() and the
            // region is a compile-time constant. The app listens on appPort behind nginx.
            instanceRedeployScript(
                $$"""BUCKET="${deployment_bucket}"
REGION="$$applicationRegion"""",
                localHealthUrl = localHealthUrl,
            )

            instanceUpdateScript(localHealthUrl = localHealthUrl)

            instanceFiles()

            runCustomInstallScripts()


            // language="Shell Script"
            appendLine(
                $$"""

echo "[INFO] Reloading systemd configuration..."
systemctl daemon-reload

systemctl enable $$projectPrefix

# First-time deploy uses the same script that subsequent SSM-driven redeploys
# will use, so there's exactly one code path that knows how to deploy the server.
echo "[INFO] Running first-time application deploy..."
/usr/local/bin/lightning-server-redeploy

# === Start Services ===
echo "[INFO] Starting services..."
systemctl enable nginx
systemctl restart nginx
"""
            )

            // Start on-box agents (e.g. the OTel collector) now that the instance role is available;
            // their cloud-init `enable` alone would not start them until the next boot.
            startProvisioningServices()

            // language="Shell Script"
            appendLine(
                $$"""
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

    /** Install + configure the CloudWatch agent in one step. */
    private fun StringBuilder.cloudwatchAgent(applicationRegion: String) {
        cloudwatchAgentInstall(applicationRegion)
        cloudwatchAgentConfig()
    }

    /**
     * Install the CloudWatch agent (a downloaded .deb on Ubuntu, dnf on Amazon Linux). The scaling builder installs the agent via the
     * AWS-managed `amazon-cloudwatch-agent-linux` component instead, so it only needs [cloudwatchAgentConfig].
     */
    private fun StringBuilder.cloudwatchAgentInstall(applicationRegion: String) {
        if (distro == Distro.AmazonLinux2023) {
            // Packaged in the AL2023 repositories.
            appendLine(
                """
# === Install CloudWatch Agent ===
echo "[INFO] Installing CloudWatch Agent at $(date)"
dnf install -y amazon-cloudwatch-agent
"""
            )
            return
        }
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

    /**
     * Download + install the AWS CLI v2. The scaling builder installs it via the AWS-managed
     * `aws-cli-version-2-linux` component instead.
     */
    // language="Shell Script"
    private fun StringBuilder.awsCli() {
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

    /** The `server` blocks for [domain]: an HTTP-to-HTTPS redirect and the TLS proxy to the app. */
    @Language("TEXT")
    private fun nginxServers(domain: String, includeUpgrade: Boolean): String = $$"""
# Drop requests for any other host name or a bare IP
server {
    listen 80 default_server;
    listen [::]:80 default_server;
    listen 443 ssl default_server;
    listen [::]:443 ssl default_server;
    ssl_reject_handshake on;
    return 444;
}

# HTTP: redirect to HTTPS (nginx-module-acme answers ACME challenges before this)
server {
    listen 80;
    listen [::]:80;
    server_name $$domain;
    return 308 https://$host$request_uri;
}

server {
    server_name $$domain;
    listen 443 ssl;
    listen [::]:443 ssl;
    http2 on;

    acme_certificate letsencrypt;
    ssl_certificate     $acme_certificate;
    ssl_certificate_key $acme_certificate_key;
    # Variable certificate paths are otherwise re-read on every handshake.
    ssl_certificate_cache max=2;

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

    /** Installs nginx and nginx-module-acme from nginx.org's repository, then writes the proxy config. */
    private fun StringBuilder.nginxInstall() {
        val repository = when (distro) {
            // nginx.org's packages over Ubuntu's own nginx, which nginx-module-acme is not built against.
            Distro.Ubuntu2404, Distro.Ubuntu2604 -> $$"""
install -d -m 0755 /etc/apt/keyrings
curl -fsSL -o /etc/apt/keyrings/nginx.asc https://nginx.org/keys/nginx_signing.key
echo "deb [signed-by=/etc/apt/keyrings/nginx.asc] https://nginx.org/packages/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") nginx" \
    > /etc/apt/sources.list.d/nginx.list
printf 'Package: *\nPin: origin nginx.org\nPin: release o=nginx\nPin-Priority: 900\n' > /etc/apt/preferences.d/99nginx
apt-get -o DPkg::Lock::Timeout=$$aptLockTimeoutSeconds update -y
apt-get -o DPkg::Lock::Timeout=$$aptLockTimeoutSeconds install -y nginx nginx-module-acme"""

            // priority=9 prefers nginx.org's packages over AL2023's own nginx, which ships no ACME module.
            Distro.AmazonLinux2023 -> """
cat > /etc/yum.repos.d/nginx.repo << 'NGINX_REPO_EOF'
[nginx-stable]
name=nginx stable repo
baseurl=https://nginx.org/packages/amzn/2023/${'$'}basearch/
gpgcheck=1
enabled=1
gpgkey=https://nginx.org/keys/nginx_signing.key
module_hotfixes=true
priority=9
NGINX_REPO_EOF
dnf install -y nginx nginx-module-acme"""
        }

        // language="Shell Script"
        appendLine(
            $$"""
# === Install nginx + ACME module ===
echo "[INFO] Installing nginx $(date)"$$repository

# === nginx config (nginx-module-acme handles cert issuance + renewal) ===
echo "[INFO] Creating server.conf for nginx $(date)"
# The ACME module is dynamic, and load_module is only valid at the top level of nginx.conf.
grep -q 'ngx_http_acme_module' /etc/nginx/nginx.conf \
    || sed -i '1i load_module modules/ngx_http_acme_module.so;' /etc/nginx/nginx.conf
rm -f /etc/nginx/conf.d/default.conf
cat > /etc/nginx/conf.d/server.conf << 'NGX_EOF'
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

acme_issuer letsencrypt {
    uri https://acme-v02.api.letsencrypt.org/directory;
    state_path /var/cache/nginx/acme-letsencrypt;
    accept_terms_of_service;
}
$${nginxServers(domain, wsDomain == domain)}
$${if (wsDomain != domain) nginxServers(wsDomain, true) else ""}
NGX_EOF
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

# Wait for cloud-init (ec2_init.sh) to finish so the redeploy scripts are
# guaranteed to be installed. cloud-init returns 2 for a completed-but-
# degraded boot; accept that outcome only when both required scripts exist.
log "Waiting for cloud-init to complete on $INSTANCE_ID..."
WAIT_CMD_ID=$(aws ssm send-command \
    --instance-ids "$INSTANCE_ID" \
    --document-name "AWS-RunShellScript" \
    --comment "terraform redeploy: wait for cloud-init" \
    --parameters 'commands=["set +e; cloud-init status --wait; cloud_init_exit=$?; set -e; if [ \"$cloud_init_exit\" -eq 0 ]; then exit 0; fi; if [ \"$cloud_init_exit\" -eq 2 ] && [ -x /usr/local/bin/lightning-server-redeploy ] && [ -x /usr/local/bin/lightning-server-predeploy ]; then echo \"cloud-init completed degraded; required deployment scripts are present\"; exit 0; fi; echo \"cloud-init did not complete provisioning (exit $cloud_init_exit)\" >&2; exit \"$cloud_init_exit\""],executionTimeout=900' \
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

# Run pre-deploy tasks first (new build in a scratch dir; the running server is untouched). If they
# fail, abort without redeploying so the current version keeps serving.
log "Sending SSM command to run /usr/local/bin/lightning-server-predeploy..."
PRE_CMD_ID=$(aws ssm send-command \
    --instance-ids "$INSTANCE_ID" \
    --document-name "AWS-RunShellScript" \
    --comment "terraform pre-deploy" \
    --parameters 'commands=/usr/local/bin/lightning-server-predeploy,executionTimeout=600' \
    --region "$REGION" \
    --query 'Command.CommandId' \
    --output text)
log "Pre-deploy command ID: $PRE_CMD_ID — polling for completion..."
for i in $(seq 1 180); do
    status=$(aws ssm get-command-invocation \
        --command-id "$PRE_CMD_ID" \
        --instance-id "$INSTANCE_ID" \
        --region "$REGION" \
        --query 'Status' \
        --output text 2>/dev/null || echo "Pending")
    case "$status" in
        Success)
            log "Pre-deploy succeeded"
            break
            ;;
        Cancelled|Failed|TimedOut)
            err "Pre-deploy finished with status: $status — aborting without redeploy"
            aws ssm get-command-invocation \
                --command-id "$PRE_CMD_ID" \
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
        err "Pre-deploy did not finish within polling window"
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
}
