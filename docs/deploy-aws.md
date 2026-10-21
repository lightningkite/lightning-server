# Deploy to AWS Serverless

Lightning Server can automatically generate and deploy serverless infrastructure to AWS using Terraform. This allows you
to deploy your application to AWS Lambda with API Gateway, complete with database, cache, file storage, and other
services.

## Prerequisites

1. **AWS Account**: You need an AWS account with appropriate permissions
2. **Terraform**: Install Terraform CLI (https://www.terraform.io/downloads)
3. **AWS CLI**: Install and configure AWS CLI with your credentials
4. **Domain (Optional)**: A domain name for your API, managed in Route53

## Step 1: Add AWS Serverless Dependencies

Add the AWS serverless engine to your project:

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.lightningkite.lightningserver:engine-aws-serverless:$lightningServerVersion")

    // Add service implementations you'll use; these are versioned with service-abstractions
    implementation("com.lightningkite.services:database-mongodb:$serviceAbstractionsVersion")
    implementation("com.lightningkite.services:cache-dynamodb:$serviceAbstractionsVersion")
    implementation("com.lightningkite.services:files-s3:$serviceAbstractionsVersion")
    implementation("com.lightningkite.services:email-javasmtp:$serviceAbstractionsVersion")
}
```

## Step 2: Create an AWS Handler

Create a handler class that AWS Lambda will use:

```kotlin
// src/main/kotlin/AwsHandler.kt
import com.lightningkite.lightningserver.engine.awsserverless.AwsAdapter

class AwsHandler : AwsAdapter(Server)
```

## Step 3: Create a Deployment Configuration

Create a deployment configuration object:

```kotlin
// src/main/kotlin/deploy.kt
import com.lightningkite.lightningserver.terraform.awsserverless.*
import com.lightningkite.lightningserver.terraform.generated
import com.lightningkite.services.database.mongodb.mongodbAtlasFree
import com.lightningkite.services.cache.dynamodb.awsDynamoDb
import com.lightningkite.services.files.s3.awsS3Bucket
import com.lightningkite.services.email.javasmtp.awsSesDomainConfiguration
import com.lightningkite.services.email.javasmtp.awsSesSmtp
import com.lightningkite.services.sms.SMS
import com.lightningkite.services.terraform.*
import com.lightningkite.services.data.toEmailAddress
import software.amazon.awssdk.regions.Region
import java.io.File
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.days

object ProductionDeploy : TerraformAwsServerlessDomainBuilder<Server>(Server) {
    // Domain configuration (optional)
    override val domain = "api.yourapp.com"
    override val domainZone = "yourapp.com"

    // Terraform state storage
    override val storageBucket = "your-terraform-state-bucket"
    override val storageBucketPath = "production/terraform-state"
    override val terraformRoot = File("terraform/production")

    // AWS Lambda handler
    override val handler: KClass<out AwsAdapter> = AwsHandler::class

    // Project metadata
    override val displayName = "YourApp Production"
    override val debug = false
    override val emergencyContact = "admin@yourapp.com".toEmailAddress()

    // AWS region
    override val region = Region.US_WEST_2!!

    // Configure services
    override fun Server.settings() {
        // Database: MongoDB Atlas free tier
        database.mongodbAtlasFree(
            orgId = "your-mongodb-org-id",
            zoneName = "Zone 1"
        )

        // Cache: DynamoDB
        cache.awsDynamoDb()

        // File storage: S3
        files.awsS3Bucket(signedUrlDuration = 1.days)

        // Email: SES via SMTP, sending from the deployment's domain
        email.awsSesSmtp(awsSesDomainConfiguration("email", "admin@yourapp.com".toEmailAddress()))

        // SMS: Configure if needed
        sms.direct(SMS.Settings())

        // Generate secure secret basis
        secretBasis.generated()

        // Other settings
        cors.direct(CorsSettings())
        loggingSettings.direct(LoggingSettings())
    }
}

// Main functions for deployment
object ProductionDeployMain {
    @JvmStatic
    fun main(vararg args: String) = ProductionDeploy.deploy()
}

object ProductionEditVars {
    @JvmStatic
    fun main(vararg args: String) = ProductionDeploy.editVars()
}
```

## Step 4: Generate Terraform Configuration

Run the deployment main function:

```bash
./gradlew run --args="ProductionDeployMain"
```

`deploy()` writes the Terraform configuration as `.tf.json` files in the `terraformRoot` directory, runs `terraform init`,
then runs `terraform apply`, which shows you the plan and waits for you to type `yes`. Credentials come from the
deployment's secret source, and you are prompted for any that are missing. Run `ProductionEditVars` first to set them up
ahead of time.

## Step 5: Review What Gets Created

Terraform will create:

- **Lambda Function**: Your server code
- **API Gateway**: HTTP API endpoint
- **IAM Roles**: Required permissions
- **DynamoDB Tables**: For caching
- **S3 Buckets**: For file storage
- **CloudWatch Logs**: For monitoring
- **Route53 Records**: For custom domain (if configured)
- **ACM Certificate**: For HTTPS (if using custom domain)

## Service Configuration Options

### Database Options

#### MongoDB Atlas (Recommended for Production)

```kotlin
database.mongodbAtlasFree(
    orgId = "your-mongodb-org-id",
    zoneName = "Zone 1"
)
```

#### MongoDB Atlas with Specific Cluster

```kotlin
database.mongodbAtlas(
    orgId = "your-mongodb-org-id",
    clusterName = "MyCluster",
    zoneName = "Zone 1"
)
```

### Cache Options

#### DynamoDB (Recommended for AWS)

```kotlin
cache.awsDynamoDb()
```

### File Storage Options

#### S3 (Recommended for AWS)

```kotlin
files.awsS3Bucket(
    signedUrlDuration = 1.days,
)
```

### Email Options

#### SES via SMTP

```kotlin
email.awsSesSmtp(awsSesDomainConfiguration("email", "admin@yourapp.com".toEmailAddress()))
```

This needs a domain deployment (`TerraformAwsServerlessDomainBuilder`): the configuration sets up the domain's SES
identity and DNS records, and the address receives DMARC reports. Reference `JavaSmtpEmailService` in your server so
the `smtp` scheme is registered.

### Monitoring and Telemetry

#### Datadog Integration

```kotlin
import com.lightningkite.lightningserver.terraform.awsserverless.otelDatadog

telemetrySettings.otelDatadog()
```

This configures OpenTelemetry to send metrics and traces to Datadog.

## Environment-Specific Deployments

Create multiple deployment configurations for different environments:

```kotlin
// Staging environment
object StagingDeploy : TerraformAwsServerlessDomainBuilder<Server>(Server) {
    override val domain = "api-staging.yourapp.com"
    override val domainZone = "yourapp.com"
    override val terraformRoot = File("terraform/staging")
    override val storageBucket = "your-terraform-state-bucket"
    override val storageBucketPath = "staging/terraform-state"
    override val handler: KClass<out AwsAdapter> = AwsHandler::class
    override val displayName = "YourApp Staging"
    override val debug = true
    override val emergencyContact = "admin@yourapp.com".toEmailAddress()
    override val region = Region.US_WEST_2!!

    override fun Server.settings() {
        // Use cheaper/simpler services for staging
        database.mongodbAtlasFree(orgId = "your-org-id", zoneName = "Zone 1")
        cache.awsDynamoDb()
        files.awsS3Bucket()
        email.awsSesSmtp("staging@yourapp.com".toEmailAddress())
        sms.direct(SMS.Settings())
        secretBasis.generated()
    }
}
```

## Updating Your Deployment

To update your deployed application:

1. Make changes to your code
2. Build the project: `./gradlew build`
3. Deploy: `./gradlew run --args="ProductionDeployMain"`, then review the plan and type `yes`

## Managing Secrets

Sensitive values are kept in the deployment's secret source, by default an encrypted file outside your project, and are
passed to Terraform as environment variables. The plan is never saved to disk, since it would hold them in plain text.

To edit secrets:

```bash
./gradlew run --args="ProductionEditVars"
```

## Monitoring and Logs

View logs in AWS CloudWatch:

```bash
aws logs tail /aws/lambda/your-function-name --follow
```

## Cost Optimization

To minimize AWS costs:

1. **Use Free Tiers**: MongoDB Atlas free tier, DynamoDB on-demand
2. **Optimize Lambda**: Adjust memory based on actual usage
3. **Enable Compression**: Install `GzipInterceptor` for API responses
4. **Use Reserved Capacity**: For predictable workloads

## Troubleshooting

### Lambda Timeout

Increase timeout in deployment configuration:

```kotlin
override val timeout: Duration = 60.seconds
```

### Memory Issues

Increase Lambda memory:

```kotlin
override val memory: DataSize = 2.gibibytes
```

### Cold Starts

SnapStart is on by default (`snapStart`), which restores Lambdas from a snapshot taken after initialization. For critical
endpoints, also consider:

- Provisioned concurrency
- Keeping Lambdas warm with scheduled pings

## Alternative: EC2 Deployment

If Lambda cold starts are unacceptable, you need long-running processes, or have predictable high traffic,
consider [Deploy to AWS EC2](deploy-aws-ec2.md) which provides traditional VM-based deployment with Auto Scaling.

NEXT: [Deploy to AWS EC2](deploy-aws-ec2.md) | [Deploy to VM](deploy-vm.md)
