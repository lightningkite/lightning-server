package com.lightningkite.lightningserver.demo

import com.lightningkite.*
import com.lightningkite.kotlinercli.*
import com.lightningkite.lightningserver.cors.*
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.engine.awsserverless.*
import com.lightningkite.lightningserver.engine.jdk.*
import com.lightningkite.lightningserver.engine.ktor.*
import com.lightningkite.lightningserver.engine.netty.*
import com.lightningkite.lightningserver.settings.*
import com.lightningkite.lightningserver.terraform.*
import com.lightningkite.lightningserver.terraform.awsserverless.*
import com.lightningkite.lightningserver.typed.sdk.*
import com.lightningkite.lightningserver.typed.sdk.SDK.writeSdk
import com.lightningkite.services.*
import com.lightningkite.services.cache.dynamodb.*
import com.lightningkite.services.data.*
import com.lightningkite.services.database.mongodb.*
import com.lightningkite.services.email.javasmtp.*
import com.lightningkite.services.files.s3.*
import com.lightningkite.services.otel.*
import com.lightningkite.services.sms.*
import com.lightningkite.services.terraform.*
import io.ktor.server.netty.*
import software.amazon.awssdk.regions.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.ProcessBuilder
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPasswordField
import kotlin.reflect.*
import kotlin.time.*
import kotlin.time.Duration.Companion.days


private fun serve() {
    val before = TimeSource.Monotonic.markNow()
    val built = Server.build()
    println("Server built in ${before.elapsedNow()}")
    KtorEngine(built).apply {
        settings.loadFromFile(KFile("settings.json"), internalSerializersModule)
        start(Netty)
    }
}

private fun serveJdk() {
    val before = TimeSource.Monotonic.markNow()
    val built = Server.build()
    println("Server built in ${before.elapsedNow()}")
    JdkEngine(built).apply {
        settings.loadFromFile(KFile("settings.json"), internalSerializersModule)
        start()
    }
}

private fun serveNetty() {
    val before = TimeSource.Monotonic.markNow()
    val built = Server.build()
    println("Server built in ${before.elapsedNow()}")
    NettyEngine(built).apply {
        settings.loadFromFile(KFile("settings.json"), internalSerializersModule)
        start()
    }
}

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

    override val region = Region.US_WEST_2!!
    override fun Server.settings() {
        database.mongodbAtlasFree(orgId = "6323a65c43d66b56a2ea5aea", zoneName = "Zone 1")
        email.awsSesSmtp("josephivie@gmail.com".toEmailAddress())
        sms.direct(SMS.Settings())
        files.awsS3Bucket(signedUrlDuration = 1.days)
        cache.awsDynamoDb()
        secretBasis.generated()
        loggingSettings.direct(LoggingSettings())
        telemetrySettings.direct(OpenTelemetrySettings("console", reportFrequency = null))
        cors.direct(CorsSettings())
        newSecret.byVariable()
    }
}

object DemoEnvDeploy {
    @JvmStatic
    fun main(vararg args: String) = DemoEnv.deploy()
}

object DemoEnvEdit {
    @JvmStatic
    fun main(vararg args: String) = DemoEnv.editVars()
}

fun sdk() {
    println("Writing SDK")
    Server.writeSdk(FetcherSdk, KFile("demo/src/main/kotlin/sdk"), "com.lightningkite.lightningserver.demo")
    println("Finished")
}

fun main(vararg args: String) {
    cli(
        arguments = args,
        available = listOf(::serve, ::serveJdk, ::serveNetty, ::sdk),
    )
}
