package com.lightningkite.lightningserver.terraform.awsec2

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.terraform.*
import com.lightningkite.services.Untested
import com.lightningkite.services.kfile.KFile
import com.lightningkite.services.terraform.*
import com.lightningkite.services.terraform.TerraformJsonObject.Companion.expression


/**
 * Terraform builder for deploying Lightning Server to a single AWS EC2 instance.
 *
 * This is the simplest, cheapest deployment: one instance with an Elastic IP, running the
 * application behind an on-box Angie (nginx fork) reverse proxy that terminates TLS via the
 * Let's Encrypt ACME client. Route53 A/AAAA records point straight at the instance, and
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
@Untested
public abstract class TerraformAwsSingleEc2Builder<S : ServerBuilder>(
    builder: S,
) : TerraformAwsEc2BuilderBase<S>(builder) {

    abstract override val applicationVpc: AwsVpc.EC2Safe

    // === SSH access (optional, for debugging) ===

    // sshAllowed with the related IP enabling is what will allow an ssh connection.
    public open val sshAllowedV4CIDR: List<String> =
        emptyList() // Example: 0.0.0.0/0, would allow everyone in the world to attempt to ssh in
    public open val sshAllowedV6CIDR: List<String> =
        emptyList() // Example: ::/0, would allow everyone in the world to attempt to ssh in

    // === Customization Hooks ===

    /** Additional ec2_init scripts to run after base setup. */
    public open val ec2InitScriptsRaw: List<String> = emptyList()
    public open val ec2InitScripts: List<KFile> = emptyList()

    /** Direct application only accessible internally; we force using Angie. */
    override val appExposedPublicly: Boolean get() = false

    override fun registerProviders() {
        require(TerraformProviderImport.tls)
    }

    override fun emitDeploymentSpecific() {
        emitSshKeyPair()
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

    /** A generated SSH key pair, available for break-glass debugging access to the instance. */
    private fun emitSshKeyPair() {
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
            // Get latest Ubuntu 24.04 AMI
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

                // Enforce IMDSv2 to mitigate SSRF-based credential theft.
                "metadata_options" {
                    "http_tokens" - "required"
                    "http_endpoint" - "enabled"
                    "http_put_response_hop_limit" - 1
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

            // The single instance fills the bucket name via terraform templatefile() and the
            // region is a compile-time constant. The app listens on appPort behind Angie.
            instanceRedeployScript(
                $$"""BUCKET="${deployment_bucket}"
REGION="$$applicationRegion"""",
                localHealthUrl = "http://localhost:$appPort${detectedOnlinePath ?: "/meta/online"}",
            )

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
    server 127.0.0.1:$${appPort};
    keepalive 32;
    keepalive_timeout 45s;
    keepalive_requests 1000;
}

acme_client letsencrypt https://acme-v02.api.letsencrypt.org/directory;
$${outputServer(domain, wsDomain == domain)}
$${if (wsDomain != domain) outputServer(wsDomain, true) else ""}
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
}
