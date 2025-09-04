package com.lightningkite.lightningserver.demo

import com.lightningkite.kotlinercli.cli
import com.lightningkite.lightningserver.definition.exceptionSettings
import com.lightningkite.lightningserver.definition.metricsSettings
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.engine.ktor.KtorEngine
import com.lightningkite.lightningserver.settings.loadFromFile
import com.lightningkite.lightningserver.terraform.awsserverless.TerraformAwsServerlessDomainBuilder
import com.lightningkite.lightningserver.terraform.generated
import com.lightningkite.lightningserver.typed.sdk.writeKiteUiSdk
import com.lightningkite.services.ExceptionReporter
import com.lightningkite.services.MetricReporter
import com.lightningkite.services.cache.dynamodb.awsDynamoDb
import com.lightningkite.services.database.mongodb.mongodbAtlas
import com.lightningkite.services.database.mongodb.mongodbAtlasFree
import com.lightningkite.services.email.javasmtp.awsSesSmtp
import com.lightningkite.services.files.s3.awsS3Bucket
import com.lightningkite.services.sms.SMS
import com.lightningkite.services.terraform.byVariable
import com.lightningkite.services.terraform.direct
import com.lightningkite.toEmailAddress
import io.ktor.server.netty.Netty
import software.amazon.awssdk.regions.Region
import java.io.File
import kotlin.time.Duration.Companion.days


private fun serve() {
    println("---")
    println(Server.extensions.entries.joinToString("\n") { "${it.key}: ${it.value}" })
    val built = Server.build()
    println("--- ${System.identityHashCode(built)}")
    println(built.extensions.entries.joinToString("\n") { "${it.key}: ${it.value}" })
    println("---")
    KtorEngine(built).apply {
        settings.loadFromFile(File("settings.json"), internalSerializersModule)
        start(Netty)
    }
}

fun terraform() {
    Server
    TerraformAwsServerlessDomainBuilder(
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
//        settings(Server) {
        with(Server) {
            database.mongodbAtlasFree(orgId = "6323a65c43d66b56a2ea5aea")
            email.awsSesSmtp(emergencyContact)
            sms.direct(SMS.Settings())
            files.awsS3Bucket(signedUrlDuration = 1.days)
            cache.awsDynamoDb()
            secretBasis.generated()
            metricsSettings.direct(MetricReporter.Settings("none"))
            exceptionSettings.direct(ExceptionReporter.Settings("none"))
        }
    }.write(File("demo/terraform/example-new").also { it.mkdirs() })
}

fun sdk() {
    Server.build().writeKiteUiSdk("com.lightningkite.lightningserver.demo", File("demo/build/sdk"))
}

fun main(vararg args: String) {
    cli(
        arguments = args,
        available = listOf(::serve, ::terraform, ::sdk),
    )
}
