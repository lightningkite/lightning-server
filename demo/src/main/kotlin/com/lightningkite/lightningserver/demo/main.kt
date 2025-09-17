package com.lightningkite.lightningserver.demo

import com.lightningkite.kotlinercli.cli
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.engine.ktor.KtorEngine
import com.lightningkite.lightningserver.settings.loadFromFile
import com.lightningkite.lightningserver.terraform.awsserverless.TerraformAwsServerlessDomainBuilder
//import com.lightningkite.lightningserver.terraform.awsserverless.TerraformAwsServerlessDomainBuilder
import com.lightningkite.lightningserver.terraform.generated
import com.lightningkite.lightningserver.typed.sdk.FetcherSdk
import com.lightningkite.lightningserver.typed.sdk.SDK.writeSdk
import com.lightningkite.services.cache.dynamodb.awsDynamoDb
import com.lightningkite.services.data.KFile
import com.lightningkite.services.database.mongodb.mongodbAtlasFree
import com.lightningkite.services.email.javasmtp.awsSesSmtp
import com.lightningkite.services.files.s3.awsS3Bucket
import com.lightningkite.services.sms.SMS
import com.lightningkite.services.terraform.direct
import com.lightningkite.toEmailAddress
import io.ktor.server.netty.*
import software.amazon.awssdk.regions.Region
import java.io.File
import kotlin.time.Duration.Companion.days
import kotlin.time.TimeSource


private fun serve() {
    val before = TimeSource.Monotonic.markNow()
    val built = Server.build()
    println("Server built in ${before.elapsedNow()}")
    KtorEngine(built).apply {
        settings.loadFromFile(File("settings.json"), internalSerializersModule)
        start(Netty)
    }
}

fun terraform() {
    Server
    TerraformAwsServerlessDomainBuilder(
        builder = Server,
        handlerFullyQualifiedName = "com.lightningkite.lightningserver.demo.AwsHandler",

        storageBucket = "ivieleague-deployment-states",
        storageBucketPathOverride = "demo/example",
        projectPrefix = "demo-example",
        deploymentTag = "demo-example",

        displayName = "Demo Example",
        debug = true,
        emergencyContact = "josephivie@gmail.com".toEmailAddress(),

        region = Region.US_WEST_2,
        domain = "example.demo.ivieleague.com",
        domainZone = "ivieleague.com",
//        purchaseDomain = true,
    ).apply {
        settings {
            database.mongodbAtlasFree(orgId = "6323a65c43d66b56a2ea5aea", zoneName = "Zone 1")
            email.awsSesSmtp(emergencyContact)
            sms.direct(SMS.Settings())
            files.awsS3Bucket(signedUrlDuration = 1.days)
            cache.awsDynamoDb()
            secretBasis.generated()
        }
    }.write(File("demo/terraform/example-new").also { it.mkdirs() })
}

fun sdk() {
    println("Writing SDK")
    Server.writeSdk(FetcherSdk, KFile("demo/src/main/kotlin/sdk"), "com.lightningkite.lightningserver.demo")
    println("Finished")
}

fun main(vararg args: String) {
    cli(
        arguments = args,
        available = listOf(::serve, ::terraform, ::sdk),
    )
}
