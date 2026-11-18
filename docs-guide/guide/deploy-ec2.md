# Deploying to EC2

`deploy-aws-ec2` generates the Terraform for running your server as an ordinary long-running JVM on EC2: no cold
starts, no request time limit, and WebSockets held directly by the server.  Choose it over
[Lambda](aws-deployment.md) when traffic is steady enough that always-on instances cost less than paying per
request, or when you need long-lived connections or long requests.

Two builders share one base (`TerraformAwsEc2BuilderBase`):

| Builder | Shape | Use it when |
|---|---|---|
| `TerraformAwsSingleEc2Builder` | One instance with an Elastic IP.  On-box nginx gets a Let's Encrypt certificate (`nginx-module-acme`), terminates TLS, and proxies to the app. | Cheapest; low or predictable traffic; a short outage during a deploy or reboot is acceptable. |
| `TerraformAwsScalingEc2Builder` | An Application Load Balancer (ACM certificate) in front of an Auto Scaling Group in private subnets, booting from a golden AMI. | You need several instances, zero-downtime rolling deploys, and scaling. |

How settings map to resources, secrets, and customizing the output are covered in [Terraform](terraform.md).

## Before you begin

On the deploying machine: Terraform 1.x or OpenTofu, the AWS CLI v2, `openssl`, `bash`, and `sha256sum`.  In AWS: an
S3 bucket for Terraform state and a Route 53 hosted zone for the domain.  Both builders require a domain.

## Dependencies

```kotlin
// build.gradle.kts
plugins {
    application
}

dependencies {
    implementation("com.lightningkite.lightningserver:deploy-aws-ec2:$lightningServerVersion")
    implementation("com.lightningkite.lightningserver:engine-ktor:$lightningServerVersion")
    // One implementation per backend your deployment uses:
    implementation("com.lightningkite.services:database-mongodb:$serviceAbstractionsVersion")
    implementation("com.lightningkite.services:files-s3:$serviceAbstractionsVersion")
    implementation("com.lightningkite.services:cache-redis:$serviceAbstractionsVersion")
    implementation("com.lightningkite.services:pubsub-redis:$serviceAbstractionsVersion")
}

application {
    mainClass.set("com.example.MainKt")
    applicationName = "server"
}
```

The instances run the Gradle application distribution.  `applicationName = "server"` makes `./gradlew distZip`
produce `build/distributions/server.zip`, the path the deployment uploads, holding the `server/bin/server` launcher
the instances start.

## Complete example

The imports for everything below:

<!-- sample: com/lightningkite/lightningserver/guide/samples/DeployEc2Samples.kt#imports -->
```kotlin
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.definition.loggingSettings
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.definition.telemetrySettings
import com.lightningkite.lightningserver.engine.ktor.KtorEngine
import com.lightningkite.lightningserver.engine.local.engineCache
import com.lightningkite.lightningserver.engine.local.enginePubSub
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.settings.loadFromFile
import com.lightningkite.lightningserver.terraform.aws.ec2.TerraformAwsScalingEc2Builder
import com.lightningkite.lightningserver.terraform.aws.ec2.TerraformAwsSingleEc2Builder
import com.lightningkite.lightningserver.terraform.aws.ec2.otelXRay
import com.lightningkite.lightningserver.terraform.generated
import com.lightningkite.lightningserver.typed.MetaEndpoints
import com.lightningkite.services.LoggingSettings
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.cache.redis.RedisCache
import com.lightningkite.services.cache.redis.awsElasticacheValkey
import com.lightningkite.services.cache.redis.redis
import com.lightningkite.services.data.toEmailAddress
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.mongodb.MongoDatabase
import com.lightningkite.services.database.mongodb.awsDocumentDb
import com.lightningkite.services.database.mongodb.mongodbAtlasFree
import com.lightningkite.services.files.ExternalFileSystem
import com.lightningkite.services.files.s3.S3ExternalFileSystem
import com.lightningkite.services.files.s3.awsS3Bucket
import com.lightningkite.services.kfile.KFile
import com.lightningkite.services.pubsub.PubSub
import com.lightningkite.services.pubsub.redis.RedisPubSub
import com.lightningkite.services.pubsub.redis.redis
import com.lightningkite.services.telemetry.TelemetryBackend
import com.lightningkite.services.terraform.AwsVpc
import com.lightningkite.services.terraform.KmsKeySource
import com.lightningkite.services.terraform.direct
import io.ktor.server.netty.Netty
import software.amazon.awssdk.regions.Region
import kotlin.time.Duration.Companion.days
```

### 1. The server

<!-- sample: com/lightningkite/lightningserver/guide/samples/DeployEc2Samples.kt#server -->
```kotlin
object Server : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val cache = setting("cache", Cache.Settings())
    val files = setting("files", ExternalFileSystem.Settings())

    init {
        MongoDatabase
        RedisCache
        RedisPubSub
        S3ExternalFileSystem
    }

    // The load balancer and the redeploy script check GET /meta/online.
    val meta = path.path("meta") include MetaEndpoints("com.example", cache)
}
```

Referencing each backend in `init {}` registers its URL scheme; the Terraform functions refuse to run without it.
The deployment checks liveness at `healthCheckPath` (default `/meta/online`), so mount `MetaEndpoints` at `meta`
or override `healthCheckPath`.

### 2. The main function

<!-- sample: com/lightningkite/lightningserver/guide/samples/DeployEc2Samples.kt#main -->
```kotlin
fun main(vararg args: String) {
    val engine = KtorEngine(Server.build())
    engine.settings.loadFromFile(KFile("settings.json"), engine.internalSerializersModule)
    when (args.firstOrNull()) {
        "predeploy" -> engine.runPreDeploy()
        else -> engine.start(Netty)
    }
}
```

The instances call it two ways: `server serve` to run, and `server predeploy` once per deploy to run pre-deploy
tasks before the new version takes traffic.  Override `serverCommand` or `preDeployCommand` if yours differ.

`settings.json` must be loaded from the working directory.  On the instance it is the encrypted settings bundle;
`loadFromFile` decrypts it in memory with the key the service receives in `LIGHTNING_SERVER_SETTINGS_DECRYPTION`.
Use `KtorEngine` or `NettyEngine`: the deployment sets their `ktorRunConfig` / `nettyRunConfig` host, port 8080, and
`realIpHeader` for the proxy in front.

### 3a. A single instance

<!-- sample: com/lightningkite/lightningserver/guide/samples/DeployEc2Samples.kt#single -->
```kotlin
object Production : TerraformAwsSingleEc2Builder<Server>(Server) {
    override val displayName = "Blog API"
    override val region: Region = Region.US_WEST_2
    override val storageBucket = "my-company-terraform-state"
    override val domainZone = "example.com"
    override val domain = "api.example.com"
    override val debug = false
    override val emergencyContact = "ops@example.com".toEmailAddress()

    override val instanceType = "t4g.small"
    override val instanceArchitecture = CPUArchitecture.Arm
    override val applicationVpc: AwsVpc.EC2Safe = AwsVpc.Default

    override fun Server.settings() {
        database.mongodbAtlasFree(orgId = "your-atlas-org-id", zoneName = "Zone 1")
        cache.direct(Cache.Settings())
        files.awsS3Bucket(signedUrlDuration = 1.days)
        secretBasis.generated()
        loggingSettings.direct(LoggingSettings())
        telemetrySettings.direct(TelemetryBackend.Settings())
        engineCache.direct(Cache.Settings())
        enginePubSub.direct(PubSub.Settings())
    }
}
```

`settings()` must fulfill every non-optional server setting plus `secretBasis`, `loggingSettings`,
`telemetrySettings`, `engineCache`, and `enginePubSub`.  On one instance the in-memory cache and pub/sub
(`Cache.Settings()`, `PubSub.Settings()`) are enough.  `instanceArchitecture` must match `instanceType`'s CPU (`t4g`,
`c7g`, ... are `Arm`; `t3`, `c6i`, ... are `X86`); there are too many instance types to infer it.

`AwsVpc.Default` uses the account's default VPC.  Because the instance has a fixed Elastic IP, `mongodbAtlasFree`
limits the Atlas IP access list to that address.

### 3b. An auto-scaled fleet

<!-- sample: com/lightningkite/lightningserver/guide/samples/DeployEc2Samples.kt#scaling -->
```kotlin
object ProductionFleet : TerraformAwsScalingEc2Builder<Server>(Server) {
    override val displayName = "Blog API Fleet"
    override val region: Region = Region.US_WEST_2
    override val storageBucket = "my-company-terraform-state"
    override val domainZone = "example.com"
    override val domain = "api.example.com"
    override val debug = false
    override val emergencyContact = "ops@example.com".toEmailAddress()

    override val instanceType = "t4g.medium"
    override val instanceArchitecture = CPUArchitecture.Arm
    override val applicationVpc = terraformManagedVPC(
        ipPrefix = "10.0",
        availabilityZones = listOf("us-west-2a", "us-west-2b", "us-west-2c"),
        natGateway = AwsVpc.NatGateway.Single,
    )
    override val minSize = 2
    override val maxSize = 8

    override fun Server.settings() {
        database.awsDocumentDb(kmsKey = KmsKeySource.AwsManaged, instanceClass = "db.t4g.medium")
        // One Valkey cluster backs the app cache and the engine's schedule locks and websocket fan-out.
        val valkey = awsElasticacheValkey("valkey")
        cache.redis(valkey)
        engineCache.redis(valkey)
        enginePubSub.redis(valkey)
        files.awsS3Bucket(signedUrlDuration = 1.days)
        secretBasis.generated()
        loggingSettings.direct(LoggingSettings())
        telemetrySettings.otelXRay()
    }
}
```

The fleet needs a VPC with private subnets and NAT egress: `terraformManagedVPC(...)` creates one, and
`existingVPC(id, cidr, securityGroup, privateSubnets, publicSubnets, privateRouteTables)` uses yours.
`AwsVpc.Default` is not accepted.

With several instances, the engine's own services must be shared:

- `engineCache` holds the per-schedule locks, so each scheduled tick runs on one instance.
- `enginePubSub` carries WebSocket messages between instances.

Point them at a distributed backend (Redis/Valkey here; DynamoDB or Memcached also work for the cache).
`write()` rejects an `engine-cache` that is the in-memory `ram` (or `ram-unsafe`) cache, since schedule locks
live there.

### 4. Entry points

As with [Lambda](aws-deployment.md#4-entry-points), give the deployment object `main` functions or `JavaExec` tasks
for `write()`, `editVars()`, and `deploy()`, running in the module directory and forwarding standard input for the
prompts.

## Deploying

```bash
./gradlew :server:distZip
./gradlew :server:deployProduction   # JavaExec task calling Production.deploy()
```

`deploy()` writes the Terraform, runs `terraform init` and `terraform apply`, shows the plan, and waits for `yes`.
It asks for AWS credentials and other secrets the first time; run `editVars()` to set them up ahead of time.

The deployment reads the zip from `<terraformRoot>/../../build/distributions/server.zip`.  As with Lambda, keep the
default `terraformRoot` and run from the module directory, set `terraformRoot` to `<module>/terraform/<name>`, or
set `distributionZipPath`.

### What a deploy does

1. Uploads `server.zip` and the encrypted settings (`settings.enc`) to the deployment bucket, but only when they
   changed.  The settings key goes to SSM Parameter Store.
2. Runs `server predeploy` once, with the new build in a scratch directory, while the old version keeps serving
   (10-minute limit, 512 MB heap).  A failure stops the deploy.
3. Rolls the new build out through SSM Run Command:
    - **Single instance:** the instance downloads the build, restarts the service, and checks `healthCheckPath`
      locally.  If the service doesn't come up healthy, it restores the previous build and settings and the apply
      fails.
    - **Fleet:** instances are updated `redeployBatchSize` at a time (default 1).  Each is drained from the load
      balancer, redeployed, checked, and returned to service only when the load balancer reports it healthy.  A
      failure rolls that instance back, halts the rollout, and fails the apply; the rest of the fleet keeps the old
      version.  Set `LS_REDEPLOY_BATCH` when running the apply to roll out faster in an emergency.

Changes to the image itself (distro, packages, install scripts, `baseImageSalt`) rebuild the golden AMI and roll the
fleet onto it with an Auto Scaling instance refresh, keeping `instanceRefreshMinHealthyPercent` (90%) in service.

## What gets created

Both builders:

| Resource | Purpose |
|---|---|
| `aws_s3_bucket.deployment` | Build and encrypted settings; private, encrypted, versioned |
| `aws_iam_role.ec2` + instance profile | Instance permissions; `aws_iam_policy.servicesAccess` collects every service's statements |
| `aws_ssm_parameter.settings_password` | Settings key, as a SecureString |
| `aws_cloudwatch_log_group.application` | `<prefix>-main-log`, one stream per instance and log file |
| `aws_sns_topic.emergency` | Alarm emails to `emergencyContact` |
| Route 53 records | `domain` (and `wsDomain` if different) |

Single instance: `aws_instance.main` (IMDSv2 required, encrypted root volume), `aws_eip.main`, a security group open
on 80 and 443, and alarms for status checks, CPU, memory, and disk.

Fleet: a VPC (with `terraformManagedVPC`), `aws_lb.app` with HTTPS (ACM certificate, TLS 1.3 policy) and an
HTTP-to-HTTPS redirect, an EC2 Image Builder pipeline for the golden AMI, `aws_launch_template.app`,
`aws_autoscaling_group.app` with target-tracking CPU scaling, an access-log bucket, and alarms for unhealthy hosts,
5xx responses, and CPU.  Instances have no public IP and accept traffic only from the load balancer.

## Configuration

Common to both builders:

| Property | Default | Notes |
|---|---|---|
| `instanceType`, `instanceArchitecture` | (required) | |
| `distro` | `Distro.Ubuntu2404` | Also `Ubuntu2604`, `AmazonLinux2023`.  Changing it replaces the instance or re-bakes the image. |
| `javaVersion` | `JavaVersion.V_17` | `V_8` through `V_25` |
| `jvmArgs` | none | Becomes `JAVA_OPTS` in the service unit |
| `volumeSizeGiB` | 20 | Root volume |
| `swapSize` | `null` | e.g. `1.gibibytes` for small instances |
| `maxBodySize` | 10 MiB | Request body limit at nginx (single instance) |
| `additionalPackages` | none | Package names for the chosen distro |
| `systemdEnvironment` | none | Extra environment variables for the service |
| `serviceSandboxing` | `true` | Read-only filesystem except `/var/lib/<prefix>`, `/var/cache/<prefix>`, `/var/log/<prefix>`, and `serviceWritablePaths` |
| `logRetentionDays` | 30 | |
| `healthCheckPath` | `/meta/online` | A liveness path, so a slow database doesn't take instances out of service |
| `customerManagedKey` | `false` | One KMS key for EBS, S3, logs, SNS, and the settings key |
| `wsDomain` | `domain` | |
| `serverCommand`, `preDeployCommand` | `serve`, `predeploy` | |
| `distributionZipPath` | `null` | Explicit path to the zip |

Single instance only: `sshAllowedV4CIDR` / `sshAllowedV6CIDR` (empty: no SSH).  Setting either opens port 22 to those
ranges and writes a generated key to `<terraformRoot>/build/<prefix>-key.pem`.

Fleet only:

| Property | Default | Notes |
|---|---|---|
| `minSize`, `maxSize`, `desiredCapacity` | 2, 6, 2 | |
| `scalingCpuTargetPercent` | 50 | Target-tracking policy |
| `scalingRequestsPerTarget` | `null` | Adds a second policy on requests per instance |
| `healthCheckGracePeriodSeconds` | 300 | Raise for slow-starting servers |
| `redeployBatchSize` | 1 | |
| `albIdleTimeoutSeconds` | 4000 | High for long-lived WebSockets |
| `dropInvalidHeaderFields` | `true` | AWS counts underscores in header names as invalid |
| `wafRules` | none | Rules for an attached WAFv2 web ACL; none means no WAF |
| `baseImageSalt` | `"1"` | Bump to re-bake the AMI from the latest base image |
| `maxInstanceLifetimeSeconds` | `null` | Force instances to rotate (at least 86400) |
| `hardeningComponents` | none | e.g. `listOf(stigBuildLinux(StigLevel.Low))` |

Files and scripts for the instances go in `instanceFilesRaw` / `instanceFiles` and `customInstallScriptsRaw`; see
[Terraform](terraform.md#customizing-the-output).

## Telemetry

The collector runs on the instance and the server sends to it, so the server never holds the backend credential:

```kotlin
telemetrySettings.otelXRay()
telemetrySettings.otelGrafanaCloud(grafanaCloudInstanceId = "123456", zone = "prod-us-east-0")
```

These are the `com.lightningkite.lightningserver.terraform.aws.ec2` versions.  Grafana Cloud needs the
`grafana_cloud_api_key` variable, which `editVars()` asks for.

## Operating

Logs reach CloudWatch (`<prefix>-main-log`) and stay on the instance in `/var/log/<prefix>/`: `server.log`,
`redeploy.log`, `predeploy.log`, `os-update.log`.

```bash
aws logs tail blog-api-main-log --follow
aws ssm start-session --target i-0123456789abcdef0
```

Shell access is through SSM Session Manager; the fleet has no SSH at all.

**OS updates.**  Automatic upgrades are disabled, since they restart things at unplanned times.  Patch a running
instance with `sudo os-update` (or `sudo os-update <package> ...`), which checks the server afterwards.  The single
instance ignores new base AMIs; move it deliberately with `terraform apply -replace=aws_instance.main`.  The fleet
moves when you bump `baseImageSalt`; drive it from a date (`get() = "2026-11"`) to re-bake on a schedule.

## Troubleshooting

- **Instances keep getting replaced (fleet).**  They never pass the load balancer check.  Check `server.log`,
  confirm `healthCheckPath` answers, and raise `healthCheckGracePeriodSeconds` for slow startups.
- **The apply fails during rollout.**  The output includes the failing instance's error; see `redeploy.log` and
  `server.log` on it.  It is already back on the previous version.
- **`write()` throws about a distributed cache.**  The fleet's `engine-cache` resolved to `ram`.  Use Redis, Memcached, or
  DynamoDB.
- **State lock left after an interrupted deploy.**  `terraform("force-unlock", "<lock-id>")`.

## Next

- [Terraform](terraform.md): settings, secrets, and customizing the generated output.
- [Deploying to a VM](deploy-vm.md): the same server on machines you manage yourself.
- [Schedules](schedules.md): how scheduled tasks coordinate across instances.
- [Observability](observability.md)
