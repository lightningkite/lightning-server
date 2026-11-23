// Own package so the chapter can use plain names (Server, main, Production).
package com.lightningkite.lightningserver.guide.samples.deployec2

// region imports
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.definition.loggingSettings
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.definition.telemetrySettings
import com.lightningkite.lightningserver.engine.ktor.KtorEngine
import com.lightningkite.lightningserver.engine.local.engineCache
import com.lightningkite.lightningserver.engine.local.enginePubSub
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.settings.loadFromFile
import com.lightningkite.lightningserver.terraform.aws.ec2.TerraformAwsScalingEc2Builder
import com.lightningkite.lightningserver.terraform.aws.ec2.TerraformAwsSingleEc2Builder
import com.lightningkite.lightningserver.terraform.aws.ec2.otelXRay
import com.lightningkite.lightningserver.terraform.generated
import com.lightningkite.lightningserver.typed.MetaEndpoints
import com.lightningkite.services.LoggingSettings
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.cache.redis.RedisCache
import com.lightningkite.services.cache.redis.awsElasticacheValkey
import com.lightningkite.services.cache.redis.redis
import com.lightningkite.services.data.toEmailAddress
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.mongodb.MongoDatabase
import com.lightningkite.services.database.mongodb.awsDocumentDb
import com.lightningkite.services.database.mongodb.mongodbAtlasFree
import com.lightningkite.services.files.ExternalFileSystem
import com.lightningkite.services.files.s3.S3ExternalFileSystem
import com.lightningkite.services.files.s3.awsS3Bucket
import com.lightningkite.services.kfile.KFile
import com.lightningkite.services.pubsub.PubSub
import com.lightningkite.services.pubsub.redis.RedisPubSub
import com.lightningkite.services.pubsub.redis.redis
import com.lightningkite.services.telemetry.TelemetryBackend
import com.lightningkite.services.terraform.AwsVpc
import com.lightningkite.services.terraform.KmsKeySource
import com.lightningkite.services.terraform.direct
import io.ktor.server.netty.Netty
import software.amazon.awssdk.regions.Region
import kotlin.time.Duration.Companion.days
// endregion

// region server
object Server : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val cache = setting("cache", Cache.Settings())
    val files = setting("files", ExternalFileSystem.Settings())

    init {
        MongoDatabase
        RedisCache
        RedisPubSub
        S3ExternalFileSystem
    }

    // The load balancer and the redeploy script check GET /meta/online.
    val meta = path.path("meta") include MetaEndpoints("com.example", cache)
}
// endregion

// region main
fun main(vararg args: String) {
    val engine = KtorEngine(Server.build())
    engine.settings.loadFromFile(KFile("settings.json"), engine.internalSerializersModule)
    when (args.firstOrNull()) {
        "predeploy" -> engine.runPreDeploy()
        else -> engine.start(Netty)
    }
}
// endregion

// region single
object Production : TerraformAwsSingleEc2Builder<Server>(Server) {
    override val displayName = "Blog API"
    override val region: Region = Region.US_WEST_2
    override val storageBucket = "my-company-terraform-state"
    override val domainZone = "example.com"
    override val domain = "api.example.com"
    override val debug = false
    override val emergencyContact = "ops@example.com".toEmailAddress()

    override val instanceType = "t4g.small"
    override val instanceArchitecture = CPUArchitecture.Arm
    override val applicationVpc: AwsVpc.EC2Safe = AwsVpc.Default

    override fun Server.settings() {
        database.mongodbAtlasFree(orgId = "your-atlas-org-id", zoneName = "Zone 1")
        cache.direct(Cache.Settings())
        files.awsS3Bucket(signedUrlDuration = 1.days)
        secretBasis.generated()
        loggingSettings.direct(LoggingSettings())
        telemetrySettings.direct(TelemetryBackend.Settings())
        engineCache.direct(Cache.Settings())
        enginePubSub.direct(PubSub.Settings())
    }
}
// endregion

// region scaling
object ProductionFleet : TerraformAwsScalingEc2Builder<Server>(Server) {
    override val displayName = "Blog API Fleet"
    override val region: Region = Region.US_WEST_2
    override val storageBucket = "my-company-terraform-state"
    override val domainZone = "example.com"
    override val domain = "api.example.com"
    override val debug = false
    override val emergencyContact = "ops@example.com".toEmailAddress()

    override val instanceType = "t4g.medium"
    override val instanceArchitecture = CPUArchitecture.Arm
    override val applicationVpc = terraformManagedVPC(
        ipPrefix = "10.0",
        availabilityZones = listOf("us-west-2a", "us-west-2b", "us-west-2c"),
        natGateway = AwsVpc.NatGateway.Single,
    )
    override val minSize = 2
    override val maxSize = 8

    override fun Server.settings() {
        database.awsDocumentDb(kmsKey = KmsKeySource.AwsManaged, instanceClass = "db.t4g.medium")
        // One Valkey cluster backs the app cache and the engine's schedule locks and websocket fan-out.
        val valkey = awsElasticacheValkey("valkey")
        cache.redis(valkey)
        engineCache.redis(valkey)
        enginePubSub.redis(valkey)
        files.awsS3Bucket(signedUrlDuration = 1.days)
        secretBasis.generated()
        loggingSettings.direct(LoggingSettings())
        telemetrySettings.otelXRay()
    }
}
// endregion
