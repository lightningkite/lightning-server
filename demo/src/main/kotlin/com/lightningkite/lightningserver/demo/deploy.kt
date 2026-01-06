package com.lightningkite.lightningserver.demo

import ai.koog.prompt.executor.clients.bedrock.BedrockModels
import com.lightningkite.lightningserver.cors.CorsSettings
import com.lightningkite.lightningserver.definition.loggingSettings
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.definition.telemetrySettings
import com.lightningkite.lightningserver.engine.awsserverless.AwsAdapter
import com.lightningkite.lightningserver.terraform.awsserverless.TerraformAwsServerlessDomainBuilder
import com.lightningkite.lightningserver.terraform.awsserverless.otelDatadog
import com.lightningkite.lightningserver.terraform.generated
import com.lightningkite.services.LoggingSettings
import com.lightningkite.services.cache.dynamodb.awsDynamoDb
import com.lightningkite.services.database.mongodb.mongodbAtlasFree
import com.lightningkite.services.email.javasmtp.awsSesSmtp
import com.lightningkite.services.files.s3.awsS3Bucket
import com.lightningkite.services.otel.OpenTelemetrySettings
import com.lightningkite.services.sms.SMS
import com.lightningkite.services.terraform.byVariable
import com.lightningkite.services.terraform.direct
import com.lightningkite.toEmailAddress
import com.lightningkite.EmailAddress
import com.lightningkite.lightningserver.terraform.*
import com.lightningkite.services.ai.koog.LLMClientAndModel
import com.lightningkite.services.ai.koog.awsBedrock
import com.lightningkite.services.email.EmailInboundService
import com.lightningkite.services.email.javasmtp.awsSesDomain
import com.lightningkite.services.email.javasmtp.awsSesSmtpLegacy
import com.lightningkite.services.email.ses.awsSesInbound
import com.lightningkite.services.phonecall.PhoneCallService
import com.lightningkite.services.pubsub.PubSub
import com.lightningkite.services.pubsub.aws.dynamoDb
import com.lightningkite.services.pubsub.redis.awsElasticacheRedisServerless
import com.lightningkite.services.sms.SmsInboundService
import com.lightningkite.services.voiceagent.VoiceAgentService
import io.github.oshai.kotlinlogging.Level
import software.amazon.awssdk.regions.Region
import java.io.File
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds


object LkEnv : TerraformAwsServerlessDomainBuilder<Server>(Server) {
    override val handler: KClass<out AwsAdapter> = AwsHandler::class

    override val useCloudFrontForWebSocket: Boolean get() = true

    // Extended timeout for voice calls - max Lambda timeout is 15 minutes
    override val timeout = 15.minutes

    override val projectPrefix: String = "LightningServerDemo"
    override val terraformRoot: File = File("demo/terraform/lk")

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

    override val secretsSource: SecretSource = AwsSecretSource("lightning-server-demo", Region.US_WEST_2)

    override fun Server.settings() {
        database.mongodbAtlasFree(orgId = "6323a65c43d66b56a2ea5aea", zoneName = "Zone 1")
        files.awsS3Bucket(signedUrlDuration = 1.days)
        cache.awsDynamoDb()
        secretBasis.generated()
        loggingSettings.direct(LoggingSettings(default = LoggingSettings.ContextSettings(
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
        )))
        telemetrySettings.direct(null)
        cors.direct(CorsSettings(
            limitToDomains = listOf("*"),
            limitToHeaders = listOf("*"),
            limitToMethods = listOf("*"),
            exposedHeaders = listOf("*"),
            allowCredentials = true,
            cacheLength = 5.seconds,
            forbidOnMatchFail = false
        ))
        newSecret.byVariable()
        llm.awsBedrock(BedrockModels.AnthropicClaude4_5Haiku.id, region.id())
//        llm.awsBedrock(BedrockModels.MoonshotKimiK2Thinking.id, region.id())
        awsSesDomain("email", "joseph@lightningkite.com".toEmailAddress())
        email.awsSesSmtp("email")
        emailInbound.awsSesInbound("email", "https://lightningserver.cs.lightningkite.com/assistant-channels/email/webhook")
        sms.byVariable()
        smsInbound.byVariable()
        pubsub.dynamoDb()  // Using DynamoDB for lower Lambda latency
//        pubsub.awsApiGatewayWebSocket()  // WebSocket-based PubSub (higher latency in Lambda)
//        pubsub.direct(PubSub.Settings())
        phoneCall.byVariable()
//        phoneCall.direct(PhoneCallService.Settings())
        voiceAgent.byVariable()
//        voiceAgent.direct(VoiceAgentService.Settings())
    }
}

object LkEnvDeploy {
    @JvmStatic
    fun main(vararg args: String) {
        ProcessBuilder("./gradlew", "demo:lambda").inheritIO().start().waitFor()
        LkEnv.deploy(autoApprove = true)
    }
}

object LkEnvEdit {
    @JvmStatic
    fun main(vararg args: String) = LkEnv.editVars()
}

object LkEnvDestroy {
    @JvmStatic
    fun main(vararg args: String) = LkEnv.terraform("destroy", "--auto-approve")
}
