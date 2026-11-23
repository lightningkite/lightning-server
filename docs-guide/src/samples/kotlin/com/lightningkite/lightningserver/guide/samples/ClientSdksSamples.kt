package com.lightningkite.lightningserver.guide.samples

// region sdk-imports
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.serialization.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.lightningserver.typed.contract.*
import com.lightningkite.lightningserver.typed.kschema.lightningServerKSchemaFromDefaultRuntime
import com.lightningkite.lightningserver.typed.sdk.*
import com.lightningkite.lightningserver.typed.sdk.SDK.writeUsingDefaultSettings
import com.lightningkite.lightningserver.typed.sdk.SdkModule.Companion.withSdkInfo
import com.lightningkite.services.kfile.KFile
import kotlinx.serialization.*
// endregion sdk-imports

// region sdk-server
@Serializable
data class Memo(val id: Int, val text: String)

class MemoEndpoints : ServerBuilder() {
    val list = path.get bind ApiHttpHandler(
        summary = "List memos",
        auth = noAuth,
        implementation = { _: Unit -> listOf(Memo(1, "Hello")) }
    )
}

class StatusModule : ServerBuilder() {
    val ping = path.path("ping").get bind ApiHttpHandler(
        summary = "Ping",
        auth = noAuth,
        implementation = { _: Unit -> "pong" }
    )
}

object SdkServer : ServerBuilder() {
    init { registerBasicMediaTypeCoders() }

    // Its own namespace, named from the class: interface MemoApi, property `memo`
    val memos = path.path("memos") module MemoEndpoints()

    // Its own namespace, named explicitly
    val status = path.path("status") module StatusModule().withSdkInfo("StatusApi", "status")

    // Directly on the root Api
    val version = path.path("version").get bind ApiHttpHandler(
        summary = "Get version",
        auth = noAuth,
        implementation = { _: Unit -> "1.0.0" }
    )
}
// endregion sdk-server

// region sdk-generate
fun generateSdks(output: KFile) {
    FetcherSdk(packageName = "com.example.client").writeUsingDefaultSettings(SdkServer, output.then("kotlin"))
    TypescriptFetcherSdk().writeUsingDefaultSettings(SdkServer, output.then("typescript"))
}
// endregion sdk-generate

// region sdk-metadata
fun authorized(fetcher: Fetcher, accessToken: suspend () -> String): Fetcher =
    fetcher.withMetadata { set("Authorization", "Bearer ${accessToken()}") }
// endregion sdk-metadata

// region sdk-contract
fun writeApiBaseline(baseline: KFile) {
    baseline.writeString(
        apiBaselineJson.encodeToString(
            LightningServerKSchema.serializer(),
            SdkServer.lightningServerKSchemaFromDefaultRuntime.sorted()
        )
    )
}

fun checkApiAgainstBaseline(baseline: KFile): Boolean {
    val old = apiBaselineJson.decodeFromString(LightningServerKSchema.serializer(), baseline.readString())
    val report = diffApiContract(old, SdkServer.lightningServerKSchemaFromDefaultRuntime, ApiAllowlist.EMPTY)
    println(report.render(strict = false))
    return !report.hasFailures(strict = false)
}
// endregion sdk-contract
