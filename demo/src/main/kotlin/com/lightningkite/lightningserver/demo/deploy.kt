package com.lightningkite.lightningserver.demo

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
import software.amazon.awssdk.regions.Region
import java.io.File
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.days


object LkEnv : TerraformAwsServerlessDomainBuilder<Server>(Server) {
    override val handler: KClass<out AwsAdapter> = AwsHandler::class


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
        email.awsSesSmtp("joseph@lightningkite.com".toEmailAddress())
        sms.direct(SMS.Settings())
        files.awsS3Bucket(signedUrlDuration = 1.days)
        cache.awsDynamoDb()
        secretBasis.generated()
        loggingSettings.direct(LoggingSettings())
        telemetrySettings.direct(null)
        cors.direct(CorsSettings())
        newSecret.byVariable()
    }
}

object LkEnvDeploy {
    @JvmStatic
    fun main(vararg args: String) = LkEnv.deploy(autoApprove = true)
}

object LkEnvEdit {
    @JvmStatic
    fun main(vararg args: String) = LkEnv.editVars()
}

object LkEnvDestroy {
    @JvmStatic
    fun main(vararg args: String) = LkEnv.terraform("destroy", "--auto-approve")
}



object JosephPersonalEnv : TerraformAwsServerlessDomainBuilder<Server>(Server) {
    override val domain = "example.demo.ivieleague.com"
    override val domainZone = "ivieleague.com"
    override val terraformRoot: File = File("demo/terraform/josephpersonal")

    override val handler: KClass<out AwsAdapter> = AwsHandler::class

    override val storageBucket = "ivieleague-deployment-states"
    override val storageBucketPath = "demo/example"
    override val displayName = "JosephPersonal Example"
    override val debug = true
    override val emergencyContact = "josephivie@gmail.com".toEmailAddress()
    override val storageEncryptionEnabled: Boolean get() = false

    override val region = Region.US_WEST_2!!
    override fun Server.settings() {
        database.mongodbAtlasFree(orgId = "6323a65c43d66b56a2ea5aea", zoneName = "Zone 1")
        email.awsSesSmtp("josephivie@gmail.com".toEmailAddress())
        sms.direct(SMS.Settings())
        files.awsS3Bucket(signedUrlDuration = 1.days)
        cache.awsDynamoDb()
        secretBasis.generated()
        loggingSettings.direct(LoggingSettings())
        telemetrySettings.otelDatadog()
        cors.direct(CorsSettings())
        newSecret.byVariable()
    }
}

object JosephPersonalEnvDeploy {
    @JvmStatic
    fun main(vararg args: String) = JosephPersonalEnv.deploy(autoApprove = true)
}

object JosephPersonalEnvEdit {
    @JvmStatic
    fun main(vararg args: String) = JosephPersonalEnv.editVars()
}

object JosephPersonalEnvDestroy {
    @JvmStatic
    fun main(vararg args: String) = JosephPersonalEnv.terraform("destroy", "--auto-approve")
}
