# deploy-aws-ec2

Terraform generators for deploying a Lightning Server application to AWS EC2, as an alternative
to the serverless Lambda deployment in `engine-aws-serverless`.

Two deployment styles are provided:

| Builder | Shape | Use it when |
|---------|-------|-------------|
| `TerraformAwsSingleEc2Builder` | One EC2 instance with an Elastic IP; on-box Angie terminates TLS via Let's Encrypt and reverse-proxies to the app. | Cheapest option; low/predictable traffic; no horizontal scaling needed. |
| `TerraformAwsScalingEc2Builder` | Application Load Balancer (ACM TLS) in front of an Auto Scaling Group of instances in private subnets, booting from a golden AMI. | You need horizontal scaling, zero-downtime rolling deploys, and load balancing. |

Both share a common base (`TerraformAwsEc2BuilderBase`) for the artifact bucket, IAM, encrypted
settings pipeline, the on-instance deploy script, and the user-data fragments.

## Module structure

```
deploy-aws-ec2/src/main/kotlin/com/lightningkite/lightningserver/terraform/awsec2/
├── TerraformAwsEc2BuilderBase.kt      # Shared config, deployment resources, user-data fragments
├── TerraformAwsSingleEc2Builder.kt    # Single instance + Angie/Let's Encrypt + Elastic IP
├── TerraformAwsScalingEc2Builder.kt   # ALB + ACM + Auto Scaling Group + golden AMI
└── extensions.kt                      # IAM path helper
```

## Single-instance builder

One instance behind on-box Angie (nginx fork) which terminates TLS with the built-in Let's
Encrypt ACME client and proxies to the app on `:8080`. Route53 A/AAAA records point straight at
the instance's Elastic IP. Code/settings updates are pushed in-place to that one instance via
SSM Run Command (`redeploy.sh` → on-instance `lightning-server-redeploy`).

```kotlin
object MyDeployment : TerraformAwsSingleEc2Builder<Server>(Server) {
    override val storageBucket = "my-terraform-state"
    override val region = Region.US_WEST_2
    override val displayName = "My Server"
    override val domainZone = "example.com"
    override val domain = "api.example.com"
    override val debug = false
    override val emergencyContact = "ops@example.com".toEmailAddress()
    override val instanceType = "t4g.medium"
    override val instanceArchitecture = CPUArchitecture.Arm
    override val applicationVpc = AwsVpc.Default

    override fun Server.settings() {
        database.need.mongoDbAtlas(...)
        cache.need.awsElasticacheMemcached(...)
        files.need.awsS3(...)
    }
}
```

## Scaling (ALB + Auto Scaling Group) builder

### Architecture

```
                Route53 (alias A/AAAA)
                          │
                          ▼
        ┌─────────────────────────────────┐
        │  Application Load Balancer (ALB) │  HTTPS:443 (ACM, TLS 1.3) → app
        │  public subnets · HTTP→HTTPS     │  HTTP:80 → 301 redirect
        └─────────────────────────────────┘
                          │  (only the ALB SG may reach the app port)
                          ▼
        ┌─────────────────────────────────┐
        │      Auto Scaling Group          │  private subnets · no public IP · SSM only
        │   ┌──────┐  ┌──────┐  ┌──────┐   │  golden AMI (EC2 Image Builder)
        │   │ app  │  │ app  │  │ app  │   │  IMDSv2 required · target-tracking CPU scaling
        │   └──────┘  └──────┘  └──────┘   │
        └─────────────────────────────────┘
                          │ outbound via NAT / S3 gateway endpoint
```

### Key design points

- **TLS at the ALB:** an ACM certificate (DNS-validated through Route53, auto-renewing)
  terminates TLS at the load balancer using a TLS 1.3 policy; HTTP is redirected to HTTPS. No
  on-box reverse proxy. WebSockets are supported natively (high `idle_timeout`).
- **Private instances:** instances run in private subnets with no public IP and no SSH key.
  The instance security group accepts traffic only from the ALB security group on the app port;
  administrative access is via SSM Session Manager.
- **IMDSv2 required:** the launch template sets `http_tokens = "required"` to mitigate
  SSRF-based credential theft. (The single-instance builder does the same on its instance.)
- **Golden AMI for fast scale-out:** EC2 Image Builder bakes the JVM, agents, the on-instance
  redeploy script, and the systemd unit into an AMI, so a scaling instance boots in well under a
  minute. The application JAR and encrypted settings are **not** baked — they are fetched from S3
  at boot — so ordinary app deploys never re-bake the AMI. The AMI re-bakes only when the install
  script changes (its content hash drives the Image Builder component version) or when
  `baseImageSalt` is bumped.
- **Health check = liveness, autodetected:** the ALB target health check defaults to the server's
  `/meta/online` liveness endpoint (autodetected from the built server — see `detectedOnlinePath`),
  not the deep `/meta/health`, so a slow downstream service can't make the ALB drain the whole
  fleet. Override `healthCheckPath` if needed.
- **Rolling, validated deploys:** on a JAR/settings change, `redeploy-fleet.sh` updates the fleet
  in batches of `redeployBatchSize` (default 1). It suspends the ASG processes that would fight it
  (`HealthCheck`/`ReplaceUnhealthy` so it can't kill a drained instance, plus
  `AddToLoadBalancer`/`AZRebalance`), then for each instance: drain from the ALB, redeploy via SSM,
  and return to service only once the ALB reports it healthy. The on-instance script validates the
  new build against the local `/meta/online` endpoint and **self-rolls-back** if it fails to start
  *or* comes up unhealthy. Any failure halts the rollout and fails the `terraform apply`. An EXIT
  trap always resumes the suspended processes and re-registers every instance, so even an abrupt
  termination leaves the fleet serving. Set `LS_REDEPLOY_BATCH` at apply time for an emergency
  faster rollout.
- **Scaling on CPU (plus optional request count):** a target-tracking policy holds average CPU at
  `scalingCpuTargetPercent` — the right primary signal here because downstream/I/O work parks
  coroutines cheaply and costs little CPU, so we don't scale in response to it. Optionally set
  `scalingRequestsPerTarget` to add a *second, simultaneous* policy on ALB requests-per-instance
  (the ASG scales out on the max of the two, in only when both agree), which catches I/O-bound
  concurrency saturation that CPU misses. Memory is intentionally an alarm, not a scaling signal
  (the JVM heap is fixed via `-Xmx`, so RAM is near-constant under load).
- **OS patching:** the bake runs `apt-get upgrade` from the latest Ubuntu base, so re-baking ships
  a fully patched image. Drive `baseImageSalt` from a date (e.g. `get() = "2026-06"`) to rebuild on
  a schedule, and set `maxInstanceLifetimeSeconds` to force the fleet to rotate onto new AMIs.
- **Scheduled tasks need no extra infrastructure:** Lightning Server's long-running engines
  coordinate scheduled tasks across the fleet through the **shared cache** (a distributed lock per
  schedule). Therefore the scaling builder **requires a distributed cache** (Redis / Memcached /
  DynamoDB) and rejects a per-instance RAM cache at build time. No SQS queue is involved.

### Failure recovery

- A new/scaled instance that never passes the ALB health check is terminated and replaced by the
  ASG automatically (a CloudWatch alarm on unhealthy-host count notifies the emergency contact).
- AMI/user-data changes roll out via ASG instance refresh with `auto_rollback = true`.
- A failed in-place redeploy halts the rollout, self-heals the instance to the previous version,
  and surfaces the error to `terraform apply`.
- A failed AMI bake fails the apply while the ASG keeps running the previously-built AMI.

### Usage

```kotlin
object MyScaledDeployment : TerraformAwsScalingEc2Builder<Server>(Server) {
    override val storageBucket = "my-terraform-state"
    override val region = Region.US_WEST_2
    override val displayName = "My Server"
    override val domainZone = "example.com"
    override val domain = "api.example.com"
    override val debug = false
    override val emergencyContact = "ops@example.com".toEmailAddress()
    override val instanceType = "t4g.medium"
    override val instanceArchitecture = CPUArchitecture.Arm

    // Must provide private subnets + NAT egress (Default VPC is not sufficient).
    override val applicationVpc = VpcInfoTFManaged(
        ipPrefix = "10.0",
        availabilityZones = listOf("us-west-2a", "us-west-2b", "us-west-2c"),
        natGateway = AwsVpc.NatGateway.Single,
    )

    override val minSize = 2
    override val maxSize = 8
    override val desiredCapacity = 2
    // healthCheckPath autodetects /meta/online; override only if your liveness path differs.
    override val scalingRequestsPerTarget = 400   // optional: scale on requests/instance too

    override fun Server.settings() {
        database.need.mongoDbAtlas(...)
        cache.need.awsElasticacheRedis(...)    // distributed cache REQUIRED
        files.need.awsS3(...)
    }
}
```

## Testing changes

```bash
# Compile
./gradlew :deploy-aws-ec2:compileKotlin

# Generation tests (build both builders to build/test-terraform and assert resources)
./gradlew :deploy-aws-ec2:test
```

To validate the generated Terraform against provider schemas (requires Terraform-registry
network access, which the CI sandbox does not have):

```bash
cd <generated terraform dir>
terraform init -backend=false
terraform validate
```

Full `plan` / `apply` additionally requires AWS credentials and is out of scope for automated
testing.
