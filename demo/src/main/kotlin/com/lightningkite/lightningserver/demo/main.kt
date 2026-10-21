package com.lightningkite.lightningserver.demo

import com.lightningkite.kotlinercli.cli
import com.lightningkite.lightningserver.engine.jdk.JdkEngine
import com.lightningkite.lightningserver.engine.ktor.KtorEngine
import com.lightningkite.lightningserver.engine.netty.NettyEngine
import com.lightningkite.lightningserver.settings.loadFromFile
import com.lightningkite.lightningserver.typed.contract.ApiAllowlist
import com.lightningkite.lightningserver.runtime.Engine
import com.lightningkite.lightningserver.typed.LightningServerKSchema
import com.lightningkite.lightningserver.typed.contract.apiBaselineJson
import com.lightningkite.lightningserver.typed.contract.diffApiContract
import com.lightningkite.lightningserver.typed.kschema.lightningServerKSchemaFromDefaultRuntime
import com.lightningkite.lightningserver.typed.sdk.FetcherSdk
import com.lightningkite.lightningserver.typed.sdk.SDK
import com.lightningkite.lightningserver.typed.sdk.SDK.writeUsingDefaultSettings
import com.lightningkite.lightningserver.typed.settingsSchemaJson
import com.lightningkite.services.kfile.KFile
import io.ktor.server.netty.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.system.exitProcess
import kotlin.time.TimeSource


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

private fun predeploy() {
    val built = Server.build()
    KtorEngine(built).apply {
        settings.loadFromFile(KFile("settings.json"), internalSerializersModule)
        runPreDeploy()
    }
    println("Pre-deploy complete")
}

/**
 * Local development convenience: run pre-deploy tasks (DB reconciliation, etc.) and then serve, in a
 * single process. In production these are separate: the pipeline runs `predeploy` before cutover,
 * and instances run `serve`.
 */
private fun dev() {
    val before = TimeSource.Monotonic.markNow()
    val built = Server.build()
    println("Server built in ${before.elapsedNow()}")
    KtorEngine(built).apply {
        settings.loadFromFile(KFile("settings.json"), internalSerializersModule)
        settings.ready()
        runPreDeployTasksBlocking()
        start(Netty)
    }
}

fun sdk() {
    println("Writing SDK")
    FetcherSdk("com.lightningkite.lightningserver.demo").writeUsingDefaultSettings(
        Server,
        KFile("demo/src/main/kotlin/sdk")
    )
    println("Finished")
}

/** Captures the current API contract and writes it to the committed baseline file. */
fun apiBaselineWrite(out: File = File("api-baseline.json")) {
    println("Writing API baseline to ${out.absolutePath}")
    out.parentFile?.mkdirs()
    out.writeText(
        apiBaselineJson.encodeToString(
            LightningServerKSchema.serializer(),
            Server.lightningServerKSchemaFromDefaultRuntime.sorted()
        )
    )
    println("Finished")
}

/**
 * Diffs the current API contract against the committed baseline and fails (exit 1) on any unsuppressed breaking change.
 *
 * @param strict When true, potentially-breaking changes also fail the check.
 */
fun apiCheck(baseline: File = File("api-baseline.json"), strict: Boolean = false) {
    val allowlist = File("api-allowlist.json").takeIf { it.exists() }
        ?.let { ApiAllowlist.json.decodeFromString(ApiAllowlist.serializer(), it.readText()) }
    if (!baseline.exists()) throw IllegalStateException("API baseline file does not exist: ${baseline.absolutePath}. Generate it with writeApiBaseline first.")
    val baseline = apiBaselineJson.decodeFromString(LightningServerKSchema.serializer(), baseline.readText())
    val current = Server.lightningServerKSchemaFromDefaultRuntime
    val report = diffApiContract(baseline, current, allowlist ?: ApiAllowlist.EMPTY)
    println(report.render(strict))
    if (report.hasFailures(strict)) exitProcess(1)
}

/** Exports the JSON Schema for settings.json so editors and CI can validate the file. */
fun settingsSchema(output: File = File("settings.schema.json")) {
    println("Writing settings schema to ${output.absolutePath}")
    // Resolve the serializers module offline (default settings, no port, no service connections), then JSONify.
    val schema = SDK.withDefaultRuntime(Server) {
        Server.settingsSchemaJson(contextOf<Engine>().internalSerializersModule)
    }
    output.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), schema))
    println("Finished")
}

fun main(vararg args: String) {
    cli(
        arguments = args,
        available = listOf(::serve, ::serveJdk, ::serveNetty, ::predeploy, ::dev, ::sdk, ::apiBaselineWrite, ::apiCheck, ::settingsSchema),
    )
}


