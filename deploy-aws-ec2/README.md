# deploy-aws-ec2

AWS EC2 deployment module for Lightning Server with Auto Scaling and Application Load Balancing.

This module generates Terraform configuration for deploying Lightning Server applications to traditional EC2 instances, as an alternative to the serverless Lambda deployment in `engine-aws-serverless`.

## Module Structure

```
deploy-aws-ec2/
├── build.gradle.kts                    # Module dependencies
├── README.md                           # This file
└── src/main/kotlin/com/lightningkite/lightningserver/
    ├── terraform/awsec2/
    │   ├── TerraformAwsEc2Builder.kt   # Main Terraform generator
    │   ├── otel-collector-terraform.kt # OpenTelemetry extensions
    │   └── extensions.kt               # Helper extensions
    └── sqs/
        └── SqsScheduleHandler.kt       # SQS-based scheduled task handler
```

## Architecture Overview

### Why EC2 Instead of Lambda?

Lambda is excellent for many workloads, but EC2 provides benefits for certain scenarios:

| Concern | Lambda | EC2 |
|---------|--------|-----|
| **Cold starts** | 1-10+ seconds for JVM | None after boot |
| **Execution limit** | 15 minutes max | Unlimited |
| **Memory** | Up to 10GB | Instance-dependent |
| **WebSockets** | Limited (API Gateway WS) | Full support |
| **Cost at scale** | Pay per invocation | Predictable monthly cost |
| **Network** | NAT required for VPC | Full VPC control |

This module targets workloads where cold starts are unacceptable, long-running processes are needed, or predictable traffic makes reserved capacity cost-effective.

### Infrastructure Design

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                                    VPC                                       │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │                         Public Subnets (3 AZs)                        │   │
│  │    ┌─────────────────────────────────────────────────────────────┐   │   │
│  │    │              Application Load Balancer (ALB)                 │   │   │
│  │    │         HTTPS:443 → HTTP:8080 (TLS 1.3 termination)         │   │   │
│  │    └─────────────────────────────────────────────────────────────┘   │   │
│  │                              │                                        │   │
│  │    ┌─────────────────────────┴─────────────────────┐                 │   │
│  │    │              NAT Gateway                       │                 │   │
│  │    └─────────────────────────┬─────────────────────┘                 │   │
│  └──────────────────────────────┼───────────────────────────────────────┘   │
│                                 │                                            │
│  ┌──────────────────────────────┼───────────────────────────────────────┐   │
│  │                         Private Subnets (3 AZs)                       │   │
│  │    ┌─────────────────────────▼─────────────────────────────────┐     │   │
│  │    │              Auto Scaling Group (ASG)                      │     │   │
│  │    │  ┌─────────┐  ┌─────────┐  ┌─────────┐                    │     │   │
│  │    │  │  EC2    │  │  EC2    │  │  EC2    │   (min: 1-N)       │     │   │
│  │    │  │ :8080   │  │ :8080   │  │ :8080   │                    │     │   │
│  │    │  └────┬────┘  └────┬────┘  └────┬────┘                    │     │   │
│  │    │       │            │            │                          │     │   │
│  │    │       └────────────┼────────────┘                          │     │   │
│  │    │                    │                                        │     │   │
│  │    │            ┌───────▼───────┐                               │     │   │
│  │    │            │  SQS Queue    │  (scheduled tasks)            │     │   │
│  │    │            └───────────────┘                               │     │   │
│  │    └────────────────────────────────────────────────────────────┘     │   │
│  └───────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘

External Services:
  ├── EventBridge (schedules → SQS)
  ├── CloudWatch (logs, metrics, alarms)
  ├── S3 (deployment artifacts)
  ├── Route53 (DNS)
  └── ACM (TLS certificates)
```

## Design Decisions & Rationale

### 1. Private Subnets for EC2 Instances

**Decision**: EC2 instances run in private subnets, not public.

**Rationale**:
- Reduces attack surface - instances not directly reachable from internet
- All traffic flows through ALB, which provides DDoS protection
- Outbound traffic goes through NAT gateway for auditability
- Follows AWS Well-Architected Framework security pillar

**Trade-off**: Requires NAT gateway (~$32/month) for outbound internet access.

### 2. Single NAT Gateway (Default)

**Decision**: Use one NAT gateway instead of one per AZ.

**Rationale**:
- Saves ~$30-45/month per additional NAT gateway
- Acceptable for most workloads where brief outbound connectivity loss is tolerable
- Can be overridden for high-availability requirements

**Trade-off**: If the NAT gateway's AZ fails, instances in other AZs lose outbound internet access until it recovers.

### 3. SQS for Scheduled Tasks (Not Lambda)

**Decision**: Use SQS queue polled by EC2 instances instead of separate Lambda functions.

**Rationale**:
- **Single deployment artifact**: All code runs in one place
- **No cold starts**: Tasks start immediately on warm instances
- **Natural load distribution**: SQS visibility timeout ensures exactly-once processing
- **Simplified debugging**: All logs in one place per instance
- **Cost efficiency**: No additional Lambda invocations

**How it works**:
1. EventBridge rules trigger on schedule
2. EventBridge sends message to SQS queue
3. `SqsScheduleHandler` on each EC2 instance polls the queue
4. First instance to receive message processes it
5. SQS visibility timeout prevents duplicate processing

### 4. IMDSv2 Required

**Decision**: Instance metadata service v2 (IMDSv2) is required, not optional.

**Rationale**:
- Prevents SSRF attacks from reaching instance metadata
- IMDSv1 allows any process to query metadata with a simple HTTP request
- IMDSv2 requires a session token, preventing most SSRF exploitation
- AWS recommends IMDSv2 for all new deployments

### 5. ARM/Graviton Default Instance Type

**Decision**: Default to `t4g.medium` (ARM/Graviton) instead of `t3.medium` (x86).

**Rationale**:
- 20-40% better price/performance ratio
- Corretto JVM has excellent ARM support
- Most applications are architecture-agnostic
- AWS is investing heavily in Graviton

**Trade-off**: Some native libraries may not have ARM builds. The instance type is easily overridden.

### 6. Settings Encrypted with PBKDF2 + AES-256

**Decision**: Encrypt the settings file before uploading to S3.

**Rationale**:
- Settings contain sensitive data (database URLs, API keys)
- Even though S3 bucket has server-side encryption, this adds defense-in-depth
- EC2 instance only has the decryption password in user-data (which is ephemeral)
- PBKDF2 with 100,000 iterations makes brute-force impractical

**How it works**:
1. Terraform encrypts settings with `openssl enc -aes-256-cbc -pbkdf2 -iter 100000`
2. Encrypted file uploaded to S3
3. EC2 instance downloads and decrypts during boot
4. Encrypted file deleted after successful decryption
5. Decrypted settings file is chmod 600

### 7. Rolling Updates (Default Deployment Strategy)

**Decision**: Use ASG instance refresh with rolling updates.

**Rationale**:
- Zero-downtime deployments
- Simple to understand and debug
- No additional infrastructure (CodeDeploy, etc.)
- Configurable min/max healthy percentages

**Trade-off**: Slower than blue-green for large fleets. Blue-green option available but requires more setup.

### 8. Systemd for Process Management

**Decision**: Run the application as a systemd service.

**Rationale**:
- Native to Amazon Linux 2023
- Automatic restart on crash (`Restart=always`)
- Proper logging integration with journald
- Clean shutdown handling
- Dependency ordering (`After=network.target`)

### 9. Journal Logs → CloudWatch

**Decision**: Collect logs from systemd journal, not log files.

**Rationale**:
- Application uses `StandardOutput=journal`
- Journal provides structured metadata (timestamps, priority, unit)
- CloudWatch agent has native journald support
- Avoids file rotation complexity

**Implementation detail**: CloudWatch agent configured with `journald` collector filtering on `lightning-server.service` unit.

## File-by-File Explanation

### TerraformAwsEc2Builder.kt

The main builder class (~1300 lines) that generates all Terraform resources.

**Key sections**:

1. **Configuration properties** (lines 81-234): User-overridable settings for instance types, scaling, health checks, etc.

2. **`finalize()`** (lines 249-285): Main entry point that calls all `emit*` methods to generate Terraform.

3. **`emitVpcResources()`** (lines 287-290): Triggers VPC module creation via lazy property.

4. **`emitSecurityGroupResources()`** (lines 292-347): Creates ALB and EC2 security groups with minimal required access.

5. **`emitAlbResources()`** (lines 349-424): Application Load Balancer, target group, HTTP→HTTPS redirect, HTTPS listener.

6. **`emitDeploymentResources()`** (lines 426-611): S3 bucket, IAM roles, encrypted settings, JAR upload.

7. **`emitAsgResources()`** (lines 613-753): Launch template, Auto Scaling Group, scaling policies.

8. **`emitSqsScheduleResources()`** (lines 755-819): SQS queue, DLQ, EventBridge rules for each scheduled task.

9. **`emitDnsResources()`** (lines 821-873): Route53 records, ACM certificate with DNS validation.

10. **`emitMonitoringResources()`** (lines 875-944): CloudWatch log groups, SNS topic, alarms.

11. **`generateUserData()`** (lines 969-1182): Bash script for instance initialization:
    - Install packages (Java, CloudWatch agent)
    - Download and decrypt application
    - Configure systemd service
    - Configure CloudWatch agent for journal collection
    - Start services with verification

12. **`validateUserDataInputs()`** (lines 1212-1256): Security validation to prevent shell injection.

### SqsScheduleHandler.kt

Handles scheduled task execution by polling an SQS queue.

**Design**:
- Long-polling (20 seconds) to minimize API calls
- Visibility timeout (5 minutes) ensures task runs on only one instance
- Automatic retry via SQS (message becomes visible again on failure)
- Dead letter queue after 3 failures

**Usage in application**:
```kotlin
System.getenv("SQS_SCHEDULE_QUEUE_URL")?.let { queueUrl ->
    SqsScheduleHandler(queueUrl = queueUrl, runtime = engine).start()
}
```

### otel-collector-terraform.kt

Extension functions for OpenTelemetry configuration.

**Pattern**: Uses Kotlin context receivers to provide a fluent API:
```kotlin
override fun Server.settings() {
    otelHoneycomb(apiKey = "...")
}
```

**Available integrations**:
- `otelXRay()` - AWS X-Ray
- `otelHoneycomb(apiKey)` - Honeycomb
- `otelGrafanaCloud(endpoint, instanceId, apiKey)` - Grafana Cloud
- `otelCustomEndpoint(endpoint, headers)` - Any OTLP endpoint

### extensions.kt

Simple helper extension for IAM path generation:
```kotlin
val TerraformEmitter.projectPrefixPath: String
    get() = projectPrefix.lowercase().replace("-", "/").replace("_", "")
```

## Security Considerations

### Input Validation

All user-provided inputs that end up in the user-data script are validated:

- **Package names**: Must match `^[a-zA-Z0-9._-]+$`
- **JVM arguments**: No shell metacharacters (`` ` $ | ; & > < ``)
- **Server command**: Same as JVM arguments
- **Instance file paths**: Must be absolute, no `..`, no sensitive paths
- **Environment variable names**: Must match `^[a-zA-Z_][a-zA-Z0-9_]*$`

### Instance Files

Instance files use base64 encoding instead of heredocs to prevent injection:
```kotlin
// Bad (vulnerable to heredoc injection):
cat > /path << 'EOF'
$userContent
EOF

// Good (safe):
echo 'base64content' | base64 -d > /path
```

### S3 Bucket Hardening

The deployment bucket has:
- Public access blocked
- Server-side encryption (AES-256)
- Versioning enabled
- IAM-only access (no bucket policy)

### Network Security

- EC2 instances in private subnets (no public IP)
- Security group allows only ALB → EC2 on port 8080
- Outbound traffic through NAT gateway
- ALB accepts only HTTPS (HTTP redirects to HTTPS)
- TLS 1.3 minimum (`ELBSecurityPolicy-TLS13-1-2-2021-06`)

## Operational Notes

### Boot Timing

The user-data script logs timing information:
```
[INFO] User data script started at <timestamp>
...
[INFO] User data script completed in <N> seconds
```

Typical boot time is 3-5 minutes including package updates.

### Debugging Failed Instances

1. **Check user-data logs**:
   ```bash
   aws ssm start-session --target i-xxxxx
   sudo cat /var/log/user-data.log
   ```

2. **Check application logs**:
   ```bash
   sudo journalctl -u lightning-server -n 100
   ```

3. **Check CloudWatch** (if agent started):
   - Log group: `/ec2/<project>/application`
   - Streams: `<instance-id>/journal`, `<instance-id>/user-data`

### Common Issues

| Symptom | Likely Cause | Solution |
|---------|--------------|----------|
| Instances keep replacing | Health check fails | Check `healthCheckPath` returns 200 |
| S3 download fails | IAM not ready | Retry logic handles this (5 attempts) |
| App starts then crashes | Settings error | Check journal for startup exceptions |
| No CloudWatch logs | Agent failed | Check `/var/log/amazon-cloudwatch-agent.log` |

## Testing Changes

After modifying Terraform generation:

1. **Compile**:
   ```bash
   ./gradlew :deploy-aws-ec2:compileKotlin
   ```

2. **Generate Terraform** (in a test project):
   ```bash
   ./gradlew run --args="TestDeployMain"
   ```

3. **Validate Terraform**:
   ```bash
   cd terraform/test
   terraform init
   terraform validate
   terraform plan
   ```

4. **Test in non-production** before production deployment.

## Dependencies

```kotlin
dependencies {
    api(project(":core"))
    api(libs.serviceAbstractionsAwsClient)  // AWS SDK common
    api(libs.awsS3)                          // S3 for artifacts
    api(libs.awsSecretsManager)              // Optional secrets
    api("software.amazon.awssdk:sqs:2.40.3") // SQS for schedules
    implementation(libs.coroutinesReactive)
    implementation(libs.coroutinesJdk)
    api(libs.kotlinReflect)                  // For mainClass KClass
}
```

## Future Enhancements

Potential improvements not yet implemented:

1. **Spot instance support**: For non-production cost savings
2. **Custom AMI**: Pre-bake Java and dependencies for faster boot
3. **EFS integration**: Shared file system across instances
4. **Blue-green with CodeDeploy**: For instant rollback capability
5. **Multi-NAT gateway option**: Property to enable HA NAT
6. **Instance connect**: Alternative to SSM for SSH access
