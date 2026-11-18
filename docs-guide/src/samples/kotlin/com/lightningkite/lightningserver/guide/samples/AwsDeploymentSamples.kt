// Own package so the chapter can use plain names (Server, AwsHandler, Production).
package com.lightningkite.lightningserver.guide.samples.awsdeployment

// region imports
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.definition.loggingSettings
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.definition.telemetrySettings
import com.lightningkite.lightningserver.engine.awsserverless.AwsAdapter
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.terraform.awsserverless.TerraformAwsServerlessDomainBuilder
import com.lightningkite.lightningserver.terraform.awsserverless.otelXRay
import com.lightningkite.lightningserver.terraform.generated
import com.lightningkite.services.LoggingSettings
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.cache.dynamodb.DynamoDbCache
import com.lightningkite.services.cache.dynamodb.awsDynamoDb
import com.lightningkite.services.data.DataSize.Companion.gibibytes
import com.lightningkite.services.data.toEmailAddress
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.mongodb.MongoDatabase
import com.lightningkite.services.database.mongodb.mongodbAtlasFree
import com.lightningkite.services.files.ExternalFileSystem
import com.lightningkite.services.files.s3.S3ExternalFileSystem
import com.lightningkite.services.files.s3.awsS3Bucket
import com.lightningkite.services.telemetry.TelemetryBackend
import com.lightningkite.services.terraform.AwsVpc
import com.lightningkite.services.terraform.direct
import software.amazon.awssdk.regions.Region
import java.io.File
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
// endregion

// region server
object Server : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val cache = setting("cache", Cache.Settings())
    val files = setting("files", ExternalFileSystem.Settings())

    init {
        // Referencing a backend registers its URL scheme; the Terraform functions below check for it.
        MongoDatabase
        DynamoDbCache
        S3ExternalFileSystem
    }

    val root = path.get bind HttpHandler { HttpResponse.plainText("OK") }
}
// endregion

// region handler
class AwsHandler : AwsAdapter(Server.build())
// endregion

// region deployment
object Production : TerraformAwsServerlessDomainBuilder<Server>(Server) {
    override val handler: KClass<out AwsAdapter> = AwsHandler::class
    override val displayName = "My API"
    override val region: Region = Region.US_WEST_2
    override val storageBucket = "my-company-terraform-state"
    override val domainZone = "example.com"
    override val domain = "api.example.com"
    override val debug = false
    override val emergencyContact = "ops@example.com".toEmailAddress()
    override val applicationVpc: AwsVpc = AwsVpc.None

    override fun Server.settings() {
        database.mongodbAtlasFree(orgId = "your-atlas-org-id", zoneName = "Zone 1")
        cache.awsDynamoDb()
        files.awsS3Bucket(signedUrlDuration = 1.days)
        secretBasis.generated()
        loggingSettings.direct(LoggingSettings())
        telemetrySettings.direct(TelemetryBackend.Settings())
    }
}
// endregion

// region entry-points
object ProductionWrite {
    @JvmStatic
    fun main(vararg args: String) = Production.write()
}

object ProductionEditVars {
    @JvmStatic
    fun main(vararg args: String) = Production.editVars()
}

object ProductionDeploy {
    @JvmStatic
    fun main(vararg args: String) = Production.deploy()
}
// endregion

// region tuning
object Staging : TerraformAwsServerlessDomainBuilder<Server>(Server) {
    override val handler: KClass<out AwsAdapter> = AwsHandler::class
    override val displayName = "My API Staging"
    override val region: Region = Region.US_WEST_2
    override val storageBucket = "my-company-terraform-state"
    override val domainZone = "example.com"
    override val domain = "staging-api.example.com"
    override val debug = true
    override val emergencyContact = "ops@example.com".toEmailAddress()
    override val applicationVpc: AwsVpc = AwsVpc.None

    // The server module is server/, but this deploy runs from the repository root.
    override val terraformRoot = File("server/terraform/staging")
    override val timeout = 60.seconds
    override val memory = 2.gibibytes
    override val monthlyBudgetUsd = 25.0

    override fun Server.settings() {
        database.mongodbAtlasFree(orgId = "your-atlas-org-id", zoneName = "Zone 1")
        cache.awsDynamoDb()
        files.awsS3Bucket(signedUrlDuration = 1.days)
        secretBasis.generated()
        loggingSettings.direct(LoggingSettings())
        telemetrySettings.otelXRay()
    }
}
// endregion
