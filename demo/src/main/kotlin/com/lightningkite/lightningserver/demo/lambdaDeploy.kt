package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningserver.cors.CorsSettings
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.engine.awsserverless.AwsAdapter
import com.lightningkite.lightningserver.sessions.proofs.oauth.OauthProviderCredentials
import com.lightningkite.lightningserver.terraform.*
import com.lightningkite.lightningserver.terraform.awsserverless.*
import com.lightningkite.services.LoggingSettings
import com.lightningkite.services.cache.dynamodb.awsDynamoDb
import com.lightningkite.services.data.EmailAddress
import com.lightningkite.services.data.toEmailAddress
import com.lightningkite.services.database.mongodb.mongodbAtlasFree
import com.lightningkite.services.email.javasmtp.awsSesDomainConfiguration
import com.lightningkite.services.email.javasmtp.awsSesSmtp
import com.lightningkite.services.email.ses.awsSesInbound
import com.lightningkite.services.files.s3.awsS3Bucket
import com.lightningkite.services.notifications.NotificationService
import com.lightningkite.services.phonecall.PhoneCallService
import com.lightningkite.services.pubsub.aws.dynamoDb
import com.lightningkite.services.sms.SMS
import com.lightningkite.services.sms.SmsInboundService
import com.lightningkite.services.terraform.AwsVpc
import com.lightningkite.services.terraform.TerraformProvider
import com.lightningkite.services.terraform.TerraformProviderImport
import com.lightningkite.services.terraform.byVariable
import com.lightningkite.services.terraform.direct
import com.lightningkite.services.voiceagent.VoiceAgentService
import io.github.oshai.kotlinlogging.Level
import kotlinx.serialization.json.JsonObject
import software.amazon.awssdk.regions.Region
import java.io.File
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class AwsHandler : AwsAdapter(Server.build())

object TestLambda : TerraformAwsServerlessDomainBuilder<Server>(Server) {
    override val handler: KClass<out AwsAdapter> = AwsHandler::class

    override val useCloudFrontForWebSocket: Boolean get() = true

    // Extended timeout for voice calls - max Lambda timeout is 15 minutes
    override val timeout = 15.minutes

    override val projectPrefix: String = "LightningServerDemo"
    override val terraformRoot: File = File("demo/terraform/lambda")

    override val storageBucket: String = "lightningkite-terraform"
    override val storageBucketPath: String = "lightningserverdemo"

    override val domainZone: String = "cs.lightningkite.com"
    override val domain: String = "lightningserver.cs.lightningkite.com"
    override val region: Region = Region.US_WEST_2
    override val emergencyContact: EmailAddress = "joseph@lightningkite.com".toEmailAddress()
    override val monthlyBudgetUsd: Double = 5.0
    override val storageEncryptionEnabled: Boolean = false

    override val displayName: String = "Lightning Server Demo"
    override val debug: Boolean = true

    override val secretsSource: SecretSource =
        AwsSecretSource(profile = "lk", idPrefix = "lightning-server-demo", region = Region.US_WEST_2)

    override val applicationVpc: AwsVpc = AwsVpc.None

    override fun Server.settings() {
        require(TerraformProviderImport.mongodbAtlas)
        require(TerraformProvider(TerraformProviderImport.mongodbAtlas, null, JsonObject(emptyMap())))

//        database.awsKeyspaces(pointInTimeRecovery = true)
        database.mongodbAtlasFree(orgId = "6323a65c43d66b56a2ea5aea", zoneName = "Zone 1")

        files.awsS3Bucket(signedUrlDuration = 1.days)
        cache.awsDynamoDb()
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
        telemetrySettings.otelGrafanaCloud(
            instanceId = "1548711",
            zone = "prod-us-west-0",
        )
        grafanaAlerts(
            grafanaCloudStackSlug = "lightningkite",
            alerts = GrafanaAlert.httpErrors(deploymentTag, errorThreshold = 5) +
                    GrafanaAlert.httpLatency(deploymentTag, p99ThresholdMs = 5000) +
                    GrafanaAlert.httpLiveness(deploymentTag),
        )
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
        val sesConfig = awsSesDomainConfiguration("email", "joseph@lightningkite.com".toEmailAddress())
        email.awsSesSmtp(sesConfig)
        emailInbound.awsSesInbound(
            sesConfig,
            "https://lightningserver.cs.lightningkite.com/assistant-channels/email/webhook"
        )
        sms.direct(SMS.Settings())
        smsInbound.direct(SmsInboundService.Settings())
        phoneCall.direct(PhoneCallService.Settings())
        voiceAgent.direct(VoiceAgentService.Settings())
        notifications.direct(NotificationService.Settings("console"))
        githubOauth.direct(OauthProviderCredentials("", ""))
//        sms.byVariable()
//        smsInbound.byVariable()
//        phoneCall.byVariable()
//        voiceAgent.byVariable()
//        githubOauth.byVariable()

        pubsub.dynamoDb()
    }

    object LambdaDeploy {
        @JvmStatic
        fun main(vararg args: String) {
            ProcessBuilder("./gradlew", "demo:lambda").inheritIO().start().waitFor()
            deploy(autoApprove = true)
        }
    }

    object LambdaEdit {
        @JvmStatic
        fun main(vararg args: String) {
            println("EDIT TIME")
            editVars()
        }
    }

    object LambdaDestroy {
        @JvmStatic
        fun main(vararg args: String) = terraform("destroy", "--auto-approve")
    }

    object LambdaWrite {
        @JvmStatic
        fun main(vararg args: String) = write()
    }

    object LambdaShell {
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

