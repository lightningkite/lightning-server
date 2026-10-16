# Deploy to AWS EC2

The `deploy-aws-ec2` module generates Terraform for running your server on EC2, as an alternative to the serverless
Lambda deployment in [Deploy to AWS](deploy-aws.md).

## When to Use EC2 vs Lambda

**Choose EC2 when:**

- Lambda cold starts are unacceptable
- You have long-running requests or WebSocket-heavy traffic
- Your traffic is high and predictable, so always-on instances are cheaper

**Choose Lambda when:**

- Traffic is spiky or low, so paying per request is cheaper
- You want no servers to manage at all

## Two Builders

| Builder | Shape | Use it when |
|---------|-------|-------------|
| `TerraformAwsSingleEc2Builder` | One EC2 instance with an Elastic IP; on-box Angie terminates TLS via Let's Encrypt and reverse-proxies to the app. | Cheapest option; low or predictable traffic; no horizontal scaling needed. |
| `TerraformAwsScalingEc2Builder` | An Application Load Balancer (ACM TLS) in front of an Auto Scaling Group in private subnets, booting from a golden AMI. | You need horizontal scaling, zero-downtime rolling deploys, and load balancing. |

The module's [README](../deploy-aws-ec2/README.md) describes the architecture and failure handling of each in detail.

## Prerequisites

- An AWS account, with credentials available to the deployment (an AWS profile, access keys, or the secret source)
- A Route53 hosted zone for your domain
- An S3 bucket for Terraform state
- Terraform (or OpenTofu) installed

## Step 1: Add the Dependency

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.lightningkite.lightningserver:deploy-aws-ec2:$lightningServerVersion")
}
```

## Step 2: Package Your Server

The instances run your server from a Gradle application distribution. Apply the `application` plugin and name the
distribution `server`, so that `./gradlew distZip` produces `build/distributions/server.zip`:

```kotlin
// build.gradle.kts
plugins {
    application
}

application {
    mainClass.set("com.yourcompany.MainKt")
    applicationName = "server"
}
```

Your main function must accept two commands: `serve`, which starts the server, and `predeploy`, which runs the pre-deploy
tasks once before a deploy cuts over. Override `serverCommand`, `preDeployCommand`, or `distributionZipPath` on the
builder if yours differ.

```kotlin
fun main(vararg args: String) {
    when (args.firstOrNull()) {
        "predeploy" -> runPreDeploy()
        else -> serve()
    }
}
```

## Step 3: Create a Deployment Configuration

```kotlin
import com.lightningkite.lightningserver.terraform.aws.ec2.*
import com.lightningkite.services.terraform.AwsVpc
import software.amazon.awssdk.regions.Region

object Production : TerraformAwsScalingEc2Builder<Server>(Server) {
    // AWS
    override val region = Region.US_WEST_2
    override val storageBucket = "your-terraform-state-bucket"

    // Domain
    override val domainZone = "yourapp.com"
    override val domain = "api.yourapp.com"

    // Application
    override val displayName = "YourApp Production"
    override val debug = false
    override val emergencyContact = "ops@yourapp.com".toEmailAddress()

    // Instances
    override val instanceType = "t4g.medium"
    override val instanceArchitecture = CPUArchitecture.Arm

    // The scaling builder needs private subnets with NAT egress
    override val applicationVpc = terraformManagedVPC(
        ipPrefix = "10.0",
        availabilityZones = listOf("us-west-2a", "us-west-2b", "us-west-2c"),
        natGateway = AwsVpc.NatGateway.Single,
    )

    override fun Server.settings() {
        database.awsDocumentDb(kmsKey = KmsKeySource.AwsManaged, instanceClass = "db.t4g.medium")
        cache.awsElasticacheRedis()  // A distributed cache is required; see Scheduled Tasks
        files.awsS3Bucket(signedUrlDuration = 1.days)
        secretBasis.generated()
    }
}

object ProductionDeploy {
    @JvmStatic
    fun main(vararg args: String) = Production.deploy()
}
```

With `TerraformAwsSingleEc2Builder`, `applicationVpc` may be `AwsVpc.Default`. To use a VPC you already have, use
`existingVPC(...)` instead of `terraformManagedVPC(...)`.

## Step 4: Build and Deploy

```bash
./gradlew distZip
./gradlew run --args="ProductionDeploy"
```

`deploy()` writes the Terraform configuration, runs `terraform init`, then runs `terraform apply`, which shows the plan
and waits for you to type `yes`. The distribution zip and encrypted settings are uploaded to S3, and the instances are
updated in place.

## What Gets Created

For the scaling builder:

- An Application Load Balancer with an ACM certificate, redirecting HTTP to HTTPS
- An Auto Scaling Group of instances in private subnets, reachable only from the load balancer
- A golden AMI built by EC2 Image Builder with the JVM and agents installed
- Target-tracking scaling policies
- Route53 records for your domain
- CloudWatch logs and alarms that notify the emergency contact
- IAM roles and an S3 bucket for artifacts

## Configuration Options

### Instance Types

```kotlin
override val instanceType = "t4g.medium"   // General purpose, ARM
override val instanceArchitecture = CPUArchitecture.Arm
```

For x86 instance types such as `t3.medium` or `c6i.large`, use `CPUArchitecture.X86`.

### Scaling

```kotlin
override val minSize = 2                        // Minimum for high availability
override val maxSize = 8                        // Cost protection
override val desiredCapacity = 2                // Starting point
override val scalingCpuTargetPercent = 50       // Hold average CPU here
override val scalingRequestsPerTarget = 400     // Optional: also scale on requests per instance
```

### Health Checks

The load balancer checks `/meta/online`, a liveness endpoint, rather than the deep `/meta/health`, so a slow downstream
service cannot make it drain the whole fleet. Override `healthCheckPath` if your liveness path differs, and
`healthCheckGracePeriodSeconds` to give slow-starting instances longer.

### Rolling Deploys

A code or settings change updates the fleet in batches of `redeployBatchSize` (default 1). Each instance is drained,
redeployed, checked against its own `/meta/online`, and returned to service only once healthy. An instance that fails
to start rolls itself back, and the failure halts the rollout and fails `terraform apply`.

### JVM and Packages

```kotlin
override val javaVersion = JavaVersion.V_21
override val jvmArgs = listOf("-XX:+UseZGC")
override val additionalPackages = listOf("imagemagick")
override val systemdEnvironment = mapOf("MY_FLAG" to "on")
```

## Telemetry

The module can run an OpenTelemetry collector on each instance and point the server's telemetry at it:

```kotlin
override fun Server.settings() {
    // ...
    telemetrySettings.otelXRay()                                        // AWS X-Ray
    // or
    telemetrySettings.otelGrafanaCloud(grafanaCloudInstanceId = "123456")  // Grafana Cloud
}
```

For Grafana Cloud, supply the `grafana_cloud_api_key` variable with `editVars()`.

## Scheduled Tasks

Scheduled tasks need no extra infrastructure: the long-running engines coordinate them across the fleet with a lock in
the shared cache, so each tick runs on one instance. This is why the scaling builder requires a distributed cache
(Redis, Memcached, or DynamoDB) and rejects a per-instance RAM cache.

## Updating Deployments

```bash
./gradlew distZip
./gradlew run --args="ProductionDeploy"
```

Only the changed artifacts are uploaded, and the fleet is updated with a rolling deploy. The AMI is rebuilt only when its
install script changes or `baseImageSalt` is bumped; drive `baseImageSalt` from a date to pick up OS patches on a
schedule.

## Monitoring and Debugging

### Application Logs

Logs go to CloudWatch Logs, kept for `logRetentionDays` (default 30):

```bash
aws logs tail /your-log-group --follow
```

### Shell Access

Instances have no SSH key and no public IP; use SSM Session Manager:

```bash
aws ssm start-session --target <instance-id>
```

## Cost Optimization

- Use ARM (Graviton) instances, which are cheaper for the same performance
- Use `TerraformAwsSingleEc2Builder` when you don't need scaling
- Set `maxSize` as a cost ceiling
- Use a single NAT gateway (`AwsVpc.NatGateway.Single`) outside production

## Troubleshooting

### Instances Keep Getting Replaced

An instance that never passes the load balancer health check is replaced. Check the instance's logs for startup
failures, make sure the server answers `/meta/online`, and raise `healthCheckGracePeriodSeconds` if startup is slow.

### Deploy Fails During Rollout

The failing instance rolls itself back and the rollout stops, leaving the rest of the fleet on the previous version. The
`terraform apply` output includes the instance's error.

NEXT: [Deploy to VM](deploy-vm.md)
