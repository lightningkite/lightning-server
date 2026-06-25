> # ⚠️ UNREVIEWED FIRST DRAFT — DO NOT PUBLISH
> Auto-generated first pass. Code samples are modeled on the current source but have **NOT** been compiled, drift-checked, or reviewed. This page is intentionally **not** in the site nav. Before publishing: port samples into the drift-checked `docs-guide/src/samples` module, run the fresh-eyes judge, and delete this banner.

# Deploying to EC2

Lightning Server's `deploy-aws-ec2` module generates complete Terraform
infrastructure for running your server on AWS EC2.  Unlike the Lambda path,
EC2 gives you persistent, long-running JVM processes: no cold starts, no
15-minute timeout, full WebSocket support, and predictable costs at sustained
load.

> **Illustrative snippets.** The deployment objects, `settings()` overrides,
> Terraform calls, and shell output in this page require AWS credentials and
> running infrastructure.  They are verified against
> `deploy-aws-ec2/src/main/kotlin/` but cannot be exercised in a unit test.

---

## When to Choose EC2

|  | EC2 | Lambda |
|---|---|---|
| Cold starts | None | Present (mitigated by SnapStart) |
| Request duration limit | No limit | 15 minutes |
| WebSockets | Full, persistent connections | Session-based via API Gateway |
| Cost model | Per-instance-hour | Per-invocation + GB-second |
| Scaling speed | Minutes (AMI bake amortised) | Seconds |
| Infra complexity | Higher | Lower |

**Prefer EC2 when** your workload has sustained traffic (always-on instances
are cheaper than per-request billing), needs long-running connections
(WebSockets, streaming), or has tasks that run longer than Lambda's 15-minute
limit.

**Prefer Lambda when** you want zero infrastructure management, traffic is
highly variable (pay only when invoked), or you are just starting out.

---

## Two Deployment Shapes

The module provides two concrete builders:

| Class | Use case |
|---|---|
| `TerraformAwsSingleEc2Builder` | One instance, Elastic IP, Angie reverse proxy with Let's Encrypt. Simplest / cheapest; no HA. |
| `TerraformAwsScalingEc2Builder` | Auto Scaling Group behind an Application Load Balancer, golden AMI via EC2 Image Builder. Production-grade HA. |

The rest of this page covers both.  Most properties are shared via
`TerraformAwsEc2BuilderBase`; only the HA-specific options are called out
separately.

---

## Prerequisites

- **Terraform ≥ 1.0** or **OpenTofu** on your PATH.
- **AWS CLI** configured (`aws configure` or `AWS_PROFILE` env var).
- **Route53 hosted zone** for your domain.
- **S3 bucket** for Terraform state.
- **MongoDB Atlas organisation ID** if you use `mongodbAtlasFree()`.

---

## Step 1: Add the Dependency

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.lightningkite.lightningserver:deploy-aws-ec2:version-5-SNAPSHOT")

    // An engine for local development (same engine runs on EC2)
    implementation("com.lightningkite.lightningserver:engine-ktor:version-5-SNAPSHOT")

    // Service implementations you'll use
    implementation("com.lightningkite.services:database-mongodb:$servicesVersion")
    implementation("com.lightningkite.services:cache-dynamodb:$servicesVersion")
    implementation("com.lightningkite.services:files-s3:$servicesVersion")
}
```

The `deploy-aws-ec2` artifact carries significant transitive weight (AWS SDK v2,
Terraform JSON generation).  Keep it in a separate `:deploy` Gradle submodule
if compile times in your main module matter.

---

## Step 2: Write a `main()` for EC2

The same `main()` you use locally runs on EC2.  Lightning Server reads
`settings.json` from disk; on EC2 that file is fetched from S3, decrypted, and
written by the `lightning-server-redeploy` script at boot.

```kotlin
// Illustrative — verified against demo/src/main/kotlin/.../main.kt.
// src/main/kotlin/com/example/api/Main.kt
import com.lightningkite.lightningserver.engine.ktor.KtorEngine
import com.lightningkite.lightningserver.settings.loadFromFile
import com.lightningkite.services.kfile.KFile
import io.ktor.server.netty.Netty

fun main() {
    val built = Server.build()
    KtorEngine(built).apply {
        settings.loadFromFile(KFile("settings.json"), internalSerializersModule)
        start(Netty)
    }
}
```

`NettyEngine` works equally well; replace the import and change `start(Netty)`
to `start()`.

---

## Step 3: Build a Distribution Zip

The EC2 deployment uploads a zip produced by the `application` plugin's
`distZip` task.  The Terraform `data.external.jar_hash` block and
`null_resource.upload_jar` reference `build/distributions/server.zip` by
default.

```kotlin
// build.gradle.kts
application {
    mainClass.set("com.example.api.MainKt")
}
```

```bash
./gradlew :your-module:distZip
# Output: build/distributions/your-module.zip
```

If your zip lands at a different path, set `distributionZipPath` on the builder
(see configuration below).

---

## Step 4: Create a Deployment Object

### Single-Instance

```kotlin
// Illustrative — requires AWS credentials and terraform at apply time.
// src/main/kotlin/com/example/api/deploy.kt
import com.lightningkite.lightningserver.terraform.aws.ec2.*
import com.lightningkite.services.data.toEmailAddress
import com.lightningkite.services.database.mongodb.mongodbAtlasFree
import com.lightningkite.services.cache.dynamodb.awsDynamoDb
import com.lightningkite.services.files.s3.awsS3Bucket
import com.lightningkite.services.terraform.AwsVpc
import software.amazon.awssdk.regions.Region

object SingleInstance : TerraformAwsSingleEc2Builder<Server>(Server) {
    override val storageBucket   = "my-terraform-state"
    override val region          = Region.US_EAST_1
    override val displayName     = "My API"
    override val domainZone      = "example.com"
    override val domain          = "api.example.com"
    override val debug           = false
    override val emergencyContact = "ops@example.com".toEmailAddress()

    // Instance sizing — must match the CPU family
    override val instanceType        = "t4g.medium"   // Graviton (ARM)
    override val instanceArchitecture = CPUArchitecture.Arm

    // Network: use the AWS default VPC (easiest to start with)
    override val applicationVpc = AwsVpc.Default

    override fun Server.settings() {
        database.mongodbAtlasFree(orgId = "your-atlas-org-id", zoneName = "Zone 1")
        cache.awsDynamoDb()
        files.awsS3Bucket()
        secretBasis.generated()
    }
}

// Entry points for write / deploy
object SingleInstanceWrite  { @JvmStatic fun main(vararg args: String) = SingleInstance.write() }
object SingleInstanceDeploy { @JvmStatic fun main(vararg args: String) = SingleInstance.deploy() }
```

### Scaling (Auto Scaling Group + ALB)

```kotlin
// Illustrative.
object Production : TerraformAwsScalingEc2Builder<Server>(Server) {
    override val storageBucket   = "my-terraform-state"
    override val region          = Region.US_EAST_1
    override val displayName     = "My API Production"
    override val domainZone      = "example.com"
    override val domain          = "api.example.com"
    override val debug           = false
    override val emergencyContact = "ops@example.com".toEmailAddress()
    override val instanceType        = "t4g.medium"
    override val instanceArchitecture = CPUArchitecture.Arm

    // VPC managed by Terraform (3 AZs, single NAT gateway)
    override val applicationVpc = terraformManagedVPC(
        ipPrefix           = "10.0",
        availabilityZones  = listOf("us-east-1a", "us-east-1b", "us-east-1c"),
        natGateway         = AwsVpc.NatGateway.Single,
    )

    // Optional overrides (defaults shown)
    // override val minSize                = 2
    // override val maxSize                = 6
    // override val desiredCapacity        = 2
    // override val scalingCpuTargetPercent = 50
    // override val healthCheckPath        = "/meta/online"  // auto-detected from MetaEndpoints

    override fun Server.settings() {
        database.mongodbAtlasFree(orgId = "your-atlas-org-id", zoneName = "Zone 1")
        cache.awsDynamoDb()   // distributed cache required for fleet schedule coordination
        files.awsS3Bucket()
        secretBasis.generated()
    }
}
```

> **Important for scaling deployments:** The fleet relies on a *distributed*
> cache (DynamoDB, Redis, Memcached) so that scheduled tasks coordinate across
> instances — only one instance runs each schedule.  Configuring a RAM cache
> (the local default) throws `IllegalStateException` at `write()` time.

---

## Step 5: Set Up Secrets

Before the first deploy, configure AWS credentials and any other variables:

```kotlin
// Standalone main or Gradle exec task
object ProductionVars { @JvmStatic fun main(vararg args: String) = Production.editVars() }
```

```bash
./gradlew :your-module:run --main=com.example.api.ProductionVarsKt
```

`editVars()` opens an interactive terminal prompt for each undeclared secret
(AWS credentials, MongoDB Atlas keys, etc.) and stores them in an
`EncryptedFileSecretSource` on disk (AES-256, PBKDF2 key derivation, 100,000
iterations).  On CI, set environment variables with the `LS_SECRET_` prefix
instead (see `EnvironmentSecretSource` in [Terraform Deep-Dive](terraform.md)).

---

## Step 6: Generate and Apply Terraform

```bash
# 1. Build the distribution zip
./gradlew :your-module:distZip

# 2. (Optional) Review the generated Terraform without deploying
./gradlew :your-module:run --main=com.example.api.SingleInstanceWriteKt
# Files land in terraform/<project-prefix>/

# 3. Deploy
./gradlew :your-module:run --main=com.example.api.SingleInstanceDeployKt
# Runs: terraform init → plan (shows what changes) → prompts → apply
```

On subsequent deploys, only the ZIP and settings change.  Terraform computes a
diff and applies only what changed.

---

## What Gets Created

### Single Instance

| Resource | Purpose |
|---|---|
| `aws_instance.ubuntu` | Ubuntu 24.04 EC2 instance |
| `aws_eip.main` | Elastic IP (static public address) |
| `aws_s3_bucket.deployment` | Holds the distribution zip and encrypted settings |
| `aws_iam_role.ec2` | EC2 instance role (S3, SSM, CloudWatch) |
| `aws_ssm_parameter.settings_password` | AES-256 key for settings decryption |
| `aws_route53_record.main` | A record → Elastic IP |
| `aws_cloudwatch_log_group.application` | App logs (30-day retention) |
| `aws_sns_topic.emergency` | Alert notifications to `emergencyContact` |
| CloudWatch alarms | System status check, high CPU, high memory, high disk |

The instance runs **Angie** (nginx fork) as a reverse proxy on port 443 with
automatic Let's Encrypt certificate issuance and renewal.  The application
listens on `127.0.0.1:8080` (not exposed publicly).

### Scaling Fleet

All of the above, plus:

| Resource | Purpose |
|---|---|
| `module.vpc` (terraform-aws-modules) | VPC, public/private subnets, NAT gateway, S3 endpoint |
| `aws_lb.app` | Application Load Balancer (public, dual-stack) |
| `aws_lb_listener.https` | HTTPS listener (TLS 1.3, TLS 1.2 minimum) |
| `aws_lb_listener.http` | HTTP → HTTPS 301 redirect |
| `aws_lb_target_group.app` | Target group → instances on `appPort` (8080) |
| `aws_acm_certificate.this` | ACM certificate (DNS-validated via Route53) |
| `aws_imagebuilder_image.this` | Golden AMI (Ubuntu 24.04 + JRE 17 + agents + scripts) |
| `aws_launch_template.app` | Instance template (IMDSv2 enforced, encrypted EBS) |
| `aws_autoscaling_group.app` | ASG with rolling instance refresh |
| `aws_autoscaling_policy.cpu` | Target-tracking CPU scaling (default 50%) |
| ALB access log bucket | ALB access logs (90-day retention) |
| CloudWatch alarms | Unhealthy hosts, target 5xx, high CPU |
| `aws_sns_topic.emergency` + scale notifications | Alerts on scale events and alarms |

---

## How Updates Are Deployed

### Single Instance

When the zip or settings hash changes, Terraform runs `redeploy.sh` as a
`local-exec` provisioner.  That script:

1. Waits for the SSM agent on the instance to be online.
2. Waits for `cloud-init` to finish (first apply only).
3. Sends an SSM Run Command to `/usr/local/bin/lightning-server-redeploy`.

The on-instance `lightning-server-redeploy` script:

1. Downloads the new zip from S3 (with retry and exponential backoff).
2. Fetches the decryption password from SSM Parameter Store.
3. Decrypts `settings.enc` → `settings.json`.
4. Restarts the systemd service.
5. If the service fails to start or the liveness endpoint (`/meta/online`)
   does not respond within 60 seconds, **rolls back** to the previous
   `server-old` and `settings.json.old` automatically.

### Scaling Fleet

When the zip or settings hash changes, Terraform runs `redeploy-fleet.sh` as a
`local-exec` provisioner.  That script performs a **rolling, in-place redeploy**
one instance at a time (batch size configurable via `redeployBatchSize` or the
`LS_REDEPLOY_BATCH` env var for emergency speed):

1. Suspends the ASG processes that would interfere (`HealthCheck`,
   `ReplaceUnhealthy`, `AZRebalance`, `AddToLoadBalancer`).
2. For each in-service instance (in batches):
   a. Drains the instance from the ALB target group.
   b. Runs `lightning-server-redeploy` via SSM Run Command.
   c. On success, re-registers the instance and waits for the ALB to report it
      healthy before moving to the next.
   d. On failure, halts the rollout (the instance self-heals to the previous
      version).
3. An EXIT trap always resumes ASG processes, so the fleet keeps serving even
   if the script is interrupted.

For AMI changes (golden AMI rebuild), the ASG performs a **rolling instance
refresh** natively: new instances boot from the updated AMI, pass the health
check, and replace old instances while maintaining `instanceRefreshMinHealthyPercent`
(default 90%).

---

## Key Configuration Options

All options are `open val` overrides on the deployment object.

### Common (both builders)

| Property | Default | Notes |
|---|---|---|
| `instanceType` | (required) | e.g. `"t4g.medium"`, `"t3.medium"` |
| `instanceArchitecture` | (required) | `CPUArchitecture.Arm` or `CPUArchitecture.X86` |
| `jvmArgs` | `["-Xmx512m"]` | JVM flags for the systemd `ExecStart` |
| `serverCommand` | `"serve"` | CLI argument passed to your `main()` |
| `volumeSizeGiB` | `20` | EBS root volume size |
| `logRetentionDays` | `30` | CloudWatch log retention |
| `additionalPackages` | `[]` | Extra `apt` packages to install at boot |
| `systemdEnvironment` | `{}` | Extra `Environment=` lines in the systemd unit |
| `distributionZipPath` | `null` | Override default zip path if not using `distZip` |
| `customerManagedKey` | `false` | Create a KMS CMK for EBS, S3, SSM, logs, SNS |

### Scaling-Specific

| Property | Default | Notes |
|---|---|---|
| `minSize` | `2` | ASG minimum |
| `maxSize` | `6` | ASG maximum |
| `desiredCapacity` | `2` | ASG starting count |
| `scalingCpuTargetPercent` | `50` | CPU target-tracking threshold |
| `scalingRequestsPerTarget` | `null` | Optional ALB request-count scaling policy |
| `healthCheckPath` | auto-detected | Defaults to `/meta/online` via `MetaEndpoints` |
| `healthCheckGracePeriodSeconds` | `300` | Time before ASG can mark a new instance unhealthy |
| `redeployBatchSize` | `1` | Instances updated in parallel per batch |
| `albIdleTimeoutSeconds` | `4000` | High default supports long-lived WebSocket connections |
| `wafEnabled` | `false` | Attach AWS WAFv2 (common + known-bad-inputs rule sets) |
| `baseImageSalt` | `"1"` | Bump to force golden AMI rebuild (picks up OS patches) |
| `maxInstanceLifetimeSeconds` | `null` | Force periodic instance rotation for patching |

---

## Observability

### Logs

Application logs (stdout/stderr from the systemd service) are streamed to
CloudWatch Logs under `/ec2/<project-prefix>/application` with separate log
streams per instance:

```bash
# Tail logs via CLI
aws logs tail /ec2/my-api-production/application --follow
```

### Debugging Access (Single Instance)

The deployment generates an RSA key pair saved to
`terraform/<project-prefix>/build/<project-prefix>-key.pem`.  SSH access
requires adding your IP CIDR to `sshAllowedV4CIDR` / `sshAllowedV6CIDR`.
Prefer **SSM Session Manager** for interactive access — no open SSH port
required:

```bash
aws ssm start-session --target i-0abc1234defgh5678
```

### Debugging Access (Scaling Fleet)

Instances in the scaling fleet live in private subnets with no public IP.  Use
SSM Session Manager:

```bash
aws ssm start-session --target i-0abc1234defgh5678
# Then inspect logs:
sudo journalctl -u my-api-production
sudo cat /var/log/my-api-production/server.log
```

### CloudWatch Alarms → SNS Email

All alarms publish to an SNS topic that sends email to `emergencyContact`.  You
will receive a subscription confirmation email on first deploy — click the link
to activate alerts.

### OpenTelemetry

Add OTel tracing with a one-liner in `settings()`:

```kotlin
// Illustrative — OTel extensions are verified against engine-aws-serverless
// and deploy-aws-ec2 source; confirm imports for your chosen backend.
override fun Server.settings() {
    // ...existing settings...
    otelXRay()                            // AWS X-Ray
    // or: otelHoneycomb(apiKey = "...")
    // or: otelGrafanaCloud(endpoint, instanceId, apiKey)
    // or: otelCustomEndpoint(endpoint, headers)
}
```

---

## Security Posture

The deployment enforces several security defaults:

- **IMDSv2 required** — blocks SSRF-based credential theft via the metadata endpoint.
- **Encrypted EBS** — root volumes encrypted at rest (AWS-managed key by default;
  set `customerManagedKey = true` for a CMK).
- **Encrypted settings** — `settings.enc` is AES-256-CBC + PBKDF2 (100,000
  iterations); the decryption key lives in SSM SecureString, never in the zip.
- **Private subnets** (scaling fleet) — instances are not directly reachable from
  the internet; only the ALB security group may reach port 8080.
- **No public IP / no SSH key** by default on the scaling fleet — SSM is the
  only administrative channel.
- **S3 deployment bucket** — public access blocked; SSE-AES256 (or CMK); versioning
  enabled with 30-day noncurrent-version expiry.

---

## Troubleshooting

### Instances Keep Getting Replaced

1. Verify `healthCheckPath` returns HTTP 200 within `healthCheckGracePeriodSeconds`.
2. Confirm the application starts within that window (check CloudWatch logs).
3. Increase `healthCheckGracePeriodSeconds` if your JVM startup is slow.

### `lightning-server-redeploy` Fails

Check the redeploy log:

```bash
aws ssm start-session --target <instance-id>
sudo cat /var/log/my-api-production/redeploy.log
```

Common causes: IAM role missing `s3:GetObject` on the deployment bucket; SSM
parameter not yet created (first apply ordering); service failing to start
(see `server.log`).

### RAM Cache Rejection

`TerraformAwsScalingEc2Builder.validateConfiguration()` throws at `write()` if
the cache setting resolves to a RAM cache URL.  Call `cache.awsDynamoDb()` (or
another distributed cache) in `settings()`.

### Terraform State Locking

If a previous `deploy()` was interrupted, the S3 backend may have a dangling
lock.  Force-unlock:

```bash
cd terraform/<project-prefix>
terraform force-unlock <lock-id>
```

---

## What's Next

- **[Terraform Deep-Dive](terraform.md)** — understanding how Lightning Server
  generates Terraform, the `settings()` → resource mapping, and secret management.
- **[Deploying to AWS Lambda](aws-deployment.md)** — the serverless alternative.
- **[Running Your Server](running.md)** — engine selection, settings loading,
  and graceful shutdown.
