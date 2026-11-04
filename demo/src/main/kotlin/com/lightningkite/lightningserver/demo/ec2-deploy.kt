package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningserver.cors.CorsSettings
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.engine.local.engineCache
import com.lightningkite.lightningserver.engine.local.enginePubSub
import com.lightningkite.lightningserver.sessions.proofs.oauth.OauthProviderCredentials
import com.lightningkite.lightningserver.terraform.*
import com.lightningkite.lightningserver.terraform.aws.ec2.TerraformAwsScalingEc2Builder
import com.lightningkite.lightningserver.terraform.aws.ec2.TerraformAwsSingleEc2Builder
import com.lightningkite.lightningserver.terraform.aws.ec2.otelGrafanaCloud
import com.lightningkite.services.LoggingSettings
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.cache.redis.awsElasticacheRedis
import com.lightningkite.services.cache.redis.awsElasticacheValkey
import com.lightningkite.services.cache.redis.redis
import com.lightningkite.services.data.DataSize
import com.lightningkite.services.data.DataSize.Companion.gibibytes
import com.lightningkite.services.data.EmailAddress
import com.lightningkite.services.data.toEmailAddress
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.mongodb.awsDocumentDb
import com.lightningkite.services.email.EmailInboundService
import com.lightningkite.services.email.EmailService
import com.lightningkite.services.email.javasmtp.awsSesDomainConfiguration
import com.lightningkite.services.email.javasmtp.awsSesSmtp
import com.lightningkite.services.email.ses.awsSesInbound
import com.lightningkite.services.files.s3.awsS3Bucket
import com.lightningkite.services.notifications.NotificationService
import com.lightningkite.services.phonecall.PhoneCallService
import com.lightningkite.services.pubsub.PubSub
import com.lightningkite.services.pubsub.redis.redis
import com.lightningkite.services.sms.SMS
import com.lightningkite.services.sms.SmsInboundService
import com.lightningkite.services.telemetry.TelemetryBackend
import com.lightningkite.services.terraform.*
import com.lightningkite.services.voiceagent.VoiceAgentService
import io.github.oshai.kotlinlogging.Level
import software.amazon.awssdk.regions.Region
import java.io.File
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds


object TestEc2Scaling : TerraformAwsScalingEc2Builder<Server>(Server) {
    override val displayName: String = "Lightning Server EC2 Scaling Demo"
    override val projectPrefix: String = "ec2-scaling-demo"
    override val debug: Boolean = true

    override val region: Region = Region.US_WEST_2
    override val instanceArchitecture: CPUArchitecture = CPUArchitecture.Arm
    override val instanceType: String = "t4g.micro"

    override val terraformRoot: File = File("demo/terraform/ec2-scaling")

    override val storageBucket: String = "lightningkite-terraform"
    override val storageEncryptionEnabled: Boolean = true

    override val domainZone: String = "cs.lightningkite.com"
    override val domain: String = "scaling-demo.cs.lightningkite.com"
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

    override val distro: Distro = Distro.AmazonLinux2023
    override val swapSize: DataSize = 1.gibibytes

    override val secretsSource: SecretSource =
        AwsSecretSource(profile = "lk", idPrefix = "ec2-scaling-demo", region = Region.US_WEST_2)

    override fun Server.settings() {
//        database.awsDocumentDb(
//            // AwsManaged keeps the cluster on the AWS-managed key for now; switch to
//            // KmsKeySource.CreateDedicated to give it a dedicated CMK (recreates the cluster).
//            kmsKey = KmsKeySource.AwsManaged,
//            instanceClass = "db.t4g.medium",
//            instanceCount = 1,
//            engineVersion = "5.0",
//            backupRetentionPeriod = 1,
//            tls = true,
//            // Test deployment: skip the final snapshot so teardown is clean.
//            skipFinalSnapshot = true,
//        )
        database.direct(Database.Settings())
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
//        telemetrySettings.otelGrafanaCloud(grafanaCloudInstanceId = "000000")
        telemetrySettings.direct(TelemetryBackend.Settings())
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
//        newSecret.byVariable()
        newSecret.direct("Some Value")
        email.direct(EmailService.Settings())
        emailInbound.direct(EmailInboundService.Settings())
//        val sesConfig = awsSesDomainConfiguration("email", "joseph@lightningkite.com".toEmailAddress())
//        email.awsSesSmtp(sesConfig)
//        emailInbound.awsSesInbound(
//            sesConfig,
//            "https://lightningserver.cs.lightningkite.com/assistant-channels/email/webhook"
//        )
        sms.direct(SMS.Settings())
        smsInbound.direct(SmsInboundService.Settings())
        phoneCall.direct(PhoneCallService.Settings())
        voiceAgent.direct(VoiceAgentService.Settings())
        notifications.direct(NotificationService.Settings("console"))
//        sms.byVariable()
//        smsInbound.byVariable()
//        phoneCall.byVariable()
//        voiceAgent.byVariable()
//        notifications.byVariable()

        // Scaling requires a real cache solution, you cannot use ram
        val valkey = awsElasticacheValkey("valkey")
        cache.redis(valkey)
        pubsub.redis(valkey)
        enginePubSub.redis(valkey)
        engineCache.redis(valkey)

        githubOauth.direct(OauthProviderCredentials("", ""))
    }

    object Ec2ScalingDeploy {
        @JvmStatic
        fun main(vararg args: String) {
            ProcessBuilder("./gradlew", "demo:distZip").inheritIO().start().waitFor()
            deploy()
        }
    }

    object Ec2ScalingEdit {
        @JvmStatic
        fun main(vararg args: String) = editVars()
    }

    object Ec2ScalingDestroy {
        @JvmStatic
        fun main(vararg args: String) = terraform("destroy", "--auto-approve")
    }

    object Ec2ScalingWrite {
        @JvmStatic
        fun main(vararg args: String) = write()
    }

    object Ec2ScalingShell {
        @JvmStatic
        fun main(vararg args: String) {
            write()
            println()
            println("Entering Terraform Shell")
            while (true) {
                val next = readlnOrNull()?.also { if (it.isBlank()) continue } ?: break
                try {
                    terraform(*next.split(' ').toTypedArray())
                } catch (_: Throwable) {
                }
            }
        }
    }

}


object TestEc2Single : TerraformAwsSingleEc2Builder<Server>(Server) {
    override val displayName: String = "Lightning Server EC2 Demo"
    override val projectPrefix: String = "ec2-demo"
    override val debug: Boolean = true

    override val region: Region = Region.US_WEST_2
    override val instanceArchitecture: CPUArchitecture = CPUArchitecture.Arm
    override val instanceType: String = "t4g.micro"

    override val terraformRoot: File = File("demo/terraform/ec2-single")

    override val storageBucket: String = "lightningkite-terraform"
    override val storageEncryptionEnabled: Boolean = true

    override val domainZone: String = "cs.lightningkite.com"
    override val domain: String = "ec2-demo.cs.lightningkite.com"
    override val emergencyContact: EmailAddress = "joseph@lightningkite.com".toEmailAddress()

    override val applicationVpc: AwsVpc.EC2Safe = AwsVpc.Default

    override val distro: Distro = Distro.AmazonLinux2023

    override val secretsSource: SecretSource =
        AwsSecretSource(profile = "lk", idPrefix = "ec2-demo", region = Region.US_WEST_2)

    override fun Server.settings() {
//        database.awsDocumentDb(
//            // AwsManaged keeps the cluster on the AWS-managed key for now; switch to
//            // KmsKeySource.CreateDedicated to give it a dedicated CMK (recreates the cluster).
//            kmsKey = KmsKeySource.AwsManaged,
//            instanceClass = "db.t4g.medium",
//            instanceCount = 1,
//            engineVersion = "5.0",
//            backupRetentionPeriod = 1,
//            tls = true,
//            // Test deployment: skip the final snapshot so teardown is clean.
//            skipFinalSnapshot = true,
//        )
        database.direct(Database.Settings())
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
//        telemetrySettings.otelGrafanaCloud(grafanaCloudInstanceId = "000000")
        telemetrySettings.direct(TelemetryBackend.Settings())
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
//        newSecret.byVariable()
        newSecret.direct("Some Value")
        email.direct(EmailService.Settings())
        emailInbound.direct(EmailInboundService.Settings())
//        val sesConfig = awsSesDomainConfiguration("email", "joseph@lightningkite.com".toEmailAddress())
//        email.awsSesSmtp(sesConfig)
//        emailInbound.awsSesInbound(
//            sesConfig,
//            "https://lightningserver.cs.lightningkite.com/assistant-channels/email/webhook"
//        )
        sms.direct(SMS.Settings())
        smsInbound.direct(SmsInboundService.Settings())
        phoneCall.direct(PhoneCallService.Settings())
        voiceAgent.direct(VoiceAgentService.Settings())
        notifications.direct(NotificationService.Settings("console"))
//        sms.byVariable()
//        smsInbound.byVariable()
//        phoneCall.byVariable()
//        voiceAgent.byVariable()
//        notifications.byVariable()

//        val redis = awsElasticacheRedis("redis")
//        cache.redis(redis)
//        pubsub.redis(redis)
//        enginePubSub.redis(redis)
//        engineCache.redis(redis)
        cache.direct(Cache.Settings())
        pubsub.direct(PubSub.Settings())
        enginePubSub.direct(PubSub.Settings())
        engineCache.direct(Cache.Settings())
        githubOauth.direct(OauthProviderCredentials("", ""))
    }

    object Ec2SingleDeploy {
        @JvmStatic
        fun main(vararg args: String) {
            ProcessBuilder("./gradlew", "demo:distZip").inheritIO().start().waitFor()
            deploy()
        }
    }

    object Ec2SingleEdit {
        @JvmStatic
        fun main(vararg args: String) = editVars()
    }

    object Ec2SingleDestroy {
        @JvmStatic
        fun main(vararg args: String) = terraform("destroy", "--auto-approve")
    }

    object Ec2SingleWrite {
        @JvmStatic
        fun main(vararg args: String) = write()
    }

    object Ec2SingleShell {
        @JvmStatic
        fun main(vararg args: String) {
            write()
            println()
            println("Entering Terraform Shell")
            while (true) {
                val next = readlnOrNull()?.also { if (it.isBlank()) continue } ?: break
                try {
                    terraform(*next.split(' ').toTypedArray())
                } catch (_: Throwable) {
                }
            }
        }
    }

}
