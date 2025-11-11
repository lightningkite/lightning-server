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
import software.amazon.awssdk.regions.Region
import java.io.File
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.days


object DemoEnv : TerraformAwsServerlessDomainBuilder<Server>(Server) {
    override val domain = "example.demo.ivieleague.com"
    override val domainZone = "ivieleague.com"
    override val terraformRoot: File = File("demo/terraform/example-new")

    override val handler: KClass<out AwsAdapter> = AwsHandler::class

    override val storageBucket = "ivieleague-deployment-states"
    override val storageBucketPath = "demo/example"
    override val displayName = "Demo Example"
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

object DemoEnvDeploy {
    @JvmStatic
    fun main(vararg args: String) = DemoEnv.deploy(autoApprove = true)
}

object DemoEnvEdit {
    @JvmStatic
    fun main(vararg args: String) = DemoEnv.editVars()
}

object DemoEnvDestroy {
    @JvmStatic
    fun main(vararg args: String) = DemoEnv.terraform("destroy", "--auto-approve")
}
