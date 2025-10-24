# Deploy to AWS Serverless

Last updated January 2025 (`version-5`)

Lightning Server can automatically generate and deploy serverless infrastructure to AWS using Terraform. This allows you to deploy your application to AWS Lambda with API Gateway, complete with database, cache, file storage, and other services.

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

    // Add service implementations you'll use
    implementation("com.lightningkite.services:database-mongodb:$lightningServerVersion")
    implementation("com.lightningkite.services:cache-dynamodb:$lightningServerVersion")
    implementation("com.lightningkite.services:files-s3:$lightningServerVersion")
    implementation("com.lightningkite.services:email-ses:$lightningServerVersion")
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
import com.lightningkite.services.email.javasmtp.awsSesSmtp
import com.lightningkite.services.sms.SMS
import com.lightningkite.services.terraform.*
import com.lightningkite.toEmailAddress
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

        // Email: SES via SMTP
        email.awsSesSmtp("noreply@yourapp.com".toEmailAddress())

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

Run the deployment main function to generate Terraform files:

```bash
./gradlew run --args="ProductionDeployMain"
```

This will:
1. Generate Terraform configuration in the `terraformRoot` directory
2. Create a `.env` file with required variables
3. Set up the AWS infrastructure definition

## Step 5: Review Generated Terraform

Review the generated Terraform files in your `terraformRoot` directory:

```
terraform/production/
├── main.tf          # Main infrastructure definition
├── variables.tf     # Variable declarations
├── outputs.tf       # Output values
└── .env            # Environment variables
```

## Step 6: Deploy to AWS

Initialize and apply Terraform:

```bash
cd terraform/production
terraform init
terraform plan
terraform apply
```

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
    publicRead = false
)
```

### Email Options

#### SES via SMTP

```kotlin
email.awsSesSmtp("noreply@yourapp.com".toEmailAddress())
```

**Note**: You must verify your email address or domain in AWS SES before sending emails.

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
3. Regenerate Terraform: `./gradlew run --args="ProductionDeployMain"`
4. Apply changes: `cd terraform/production && terraform apply`

## Managing Secrets

Sensitive values are stored in the `.env` file in your Terraform directory. **Never commit this file to version control.**

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
3. **Enable Compression**: For API responses
4. **Use Reserved Capacity**: For predictable workloads

## Troubleshooting

### Lambda Timeout

Increase timeout in deployment configuration:

```kotlin
override val lambdaTimeout: Duration = 30.seconds
```

### Memory Issues

Increase Lambda memory:

```kotlin
override val lambdaMemoryMb: Int = 512
```

### Cold Starts

For critical endpoints, consider:
- Provisioned concurrency
- Keeping Lambdas warm with scheduled pings

NEXT: [Deploy to VM](deploy-vm.md)
