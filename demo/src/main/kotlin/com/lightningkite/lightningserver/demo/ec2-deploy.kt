package com.lightningkite.lightningserver.demo

//import com.lightningkite.services.database.cassandra.awsKeyspaces
import com.lightningkite.lightningserver.cors.CorsSettings
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.engine.ktor.ktorRunConfig
import com.lightningkite.lightningserver.engine.local.engineCache
import com.lightningkite.lightningserver.engine.local.enginePubSub
import com.lightningkite.lightningserver.terraform.*
import com.lightningkite.lightningserver.terraform.aws.ec2.TerraformAwsScalingEc2Builder
import com.lightningkite.lightningserver.terraform.aws.ec2.otelGrafanaCloud
import com.lightningkite.lightningserver.terraform.awsserverless.*
import com.lightningkite.services.LoggingSettings
import com.lightningkite.services.cache.dynamodb.awsDynamoDb
import com.lightningkite.services.cache.redis.awsElasticacheRedis
import com.lightningkite.services.cache.redis.awsElasticacheRedisServerless
import com.lightningkite.services.cache.redis.redis
import com.lightningkite.services.data.EmailAddress
import com.lightningkite.services.data.toEmailAddress
import com.lightningkite.services.database.mongodb.awsDocumentDb
import com.lightningkite.services.email.javasmtp.awsSesDomainConfiguration
import com.lightningkite.services.email.javasmtp.awsSesSmtp
import com.lightningkite.services.email.ses.awsSesInbound
import com.lightningkite.services.files.s3.awsS3Bucket
import com.lightningkite.services.pubsub.aws.dynamoDb
import com.lightningkite.services.pubsub.redis.redis
import com.lightningkite.services.terraform.AwsVpc
import com.lightningkite.services.terraform.KmsKeySource
import com.lightningkite.services.terraform.byVariable
import com.lightningkite.services.terraform.direct
import io.github.oshai.kotlinlogging.Level
import software.amazon.awssdk.regions.Region
import java.io.File
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds


object TestEc2Scaling : TerraformAwsScalingEc2Builder<Server>(Server) {
    override val displayName: String = "Lightning Server Demo"
    override val projectPrefix: String = "LightningServerEC2SDemo"
    override val debug: Boolean = true

    override val region: Region = Region.US_WEST_2
    override val instanceArchitecture: CPUArchitecture = CPUArchitecture.Arm
    override val instanceType: String = "t4g.micro"

    override val terraformRoot: File = File("demo/terraform/lkec2")

    override val storageBucket: String = "lightningkite-terraform"
    override val storageEncryptionEnabled: Boolean = true

    override val domainZone: String = "cs.lightningkite.com"
    override val domain: String = "lightningserver2.cs.lightningkite.com"
    override val emergencyContact: EmailAddress = "joseph@lightningkite.com".toEmailAddress()

    override val applicationVpc: AwsVpc.VpcInfo = terraformManagedVPC(
        ipPrefix = "10.7",
        availabilityZones = listOf(
            "${region.id()}a",
            "${region.id()}b",
            "${region.id()}c"
        ),
        natGateway = AwsVpc.NatGateway.Single
    )

    override val secretsSource: SecretSource =
        AwsSecretSource(profile = "lk", idPrefix = "lightning-server-demo", region = Region.US_WEST_2)

    override fun Server.settings() {
        database.awsDocumentDb(
            // AwsManaged keeps the cluster on the AWS-managed key for now; switch to
            // KmsKeySource.CreateDedicated to give it a dedicated CMK (recreates the cluster).
            kmsKey = KmsKeySource.AwsManaged,
            instanceClass = "db.t4g.medium",
            instanceCount = 1,
            engineVersion = "5.0",
            backupRetentionPeriod = 1,
            tls = true,
            // Test deployment: skip the final snapshot so teardown is clean.
            skipFinalSnapshot = true,
        )
        files.awsS3Bucket(signedUrlDuration = 1.days)
        secretBasis.generated()
        loggingSettings.direct(
            LoggingSettings(
                default = LoggingSettings.ContextSettings(
                    level = Level.INFO,
                    filePattern = null,
                    toConsole = true,
                    additive = false,
                ), logger = mapOf(
                    "org.mongodb" to LoggingSettings.ContextSettings(
                        level = Level.WARN,
                        filePattern = null,
                        toConsole = true,
                        additive = false,
                    )
                )
            )
        )
        // Run an on-box Grafana Alloy collector and ship telemetry to Grafana Cloud. Replace the
        // instance id with your Grafana Cloud OTLP username/stack id, and supply the
        // `grafana_cloud_api_key` variable (write-scoped access-policy token) via editVars().
        telemetrySettings.otelGrafanaCloud(grafanaCloudInstanceId = "000000")
        cors.direct(
            CorsSettings(
                limitToDomains = listOf("*"),
                limitToHeaders = listOf("*"),
                limitToMethods = listOf("*"),
                exposedHeaders = listOf("*"),
                allowCredentials = true,
                cacheLength = 5.seconds,
                forbidOnMatchFail = false
            )
        )
        newSecret.byVariable()
        val sesConfig = awsSesDomainConfiguration("email", "joseph@lightningkite.com".toEmailAddress())
        email.awsSesSmtp(sesConfig)
        emailInbound.awsSesInbound(
            sesConfig,
            "https://lightningserver.cs.lightningkite.com/assistant-channels/email/webhook"
        )
        sms.byVariable()
        smsInbound.byVariable()
        phoneCall.byVariable()
        voiceAgent.byVariable()

        val redis = awsElasticacheRedis("redis")
        cache.redis(redis)
        pubsub.redis(redis)
        enginePubSub.redis(redis)
        engineCache.redis(redis)
        githubOauth.byVariable()
    }

    object DoDeploy {
        @JvmStatic
        fun main(vararg args: String) {
            ProcessBuilder("./gradlew", "demo:distZip").inheritIO().start().waitFor()
            deploy()
        }
    }

    object DoEdit {
        @JvmStatic
        fun main(vararg args: String) = editVars()
    }

    object DoDestroy {
        @JvmStatic
        fun main(vararg args: String) = terraform("destroy", "--auto-approve")
    }

    object DoWrite {
        @JvmStatic
        fun main(vararg args: String) = write()
    }

    object DoValidate {
        @JvmStatic
        fun main(vararg args: String) {
            write()
            terraform("validate")
        }
    }

}
