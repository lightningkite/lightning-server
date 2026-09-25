package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.lightningserver.runtime.test.execute
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.typed.jsonschema.openApiDescription
import com.lightningkite.lightningserver.typed.sdk.SDK.writeUsingDefaultSettings
import com.lightningkite.services.kfile.KFile
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

class FetcherSdkTests {
    private val folder = KFile("./src/test/kotlin/com/lightningkite/lightningserver/typed/sdk/generated/kotlin")

    @Test
    fun testMultipleFiles() {
        FetcherSdk("com.lightningkite.lightningserver.typed.sdk").writeUsingDefaultSettings(Server, folder)
    }

    @Test
    fun testSingleFile() {
        FetcherSdk(
            "com.lightningkite.lightningserver.typed.singlesdk",
            fileStructure = FetcherSdk.Structure.SingleFile("sdk.kt")
        ).writeUsingDefaultSettings(Server, folder)
    }

    @Test
    fun openApi() {
        Server.test({}) {
            runBlocking { execute { openApiDescription } }
                .paths
                .flatMap { (_, path) ->
                    listOf(path.get, path.put, path.post, path.patch, path.delete).mapNotNull { it?.operationId }
                }
                .forEach(::println)
        }
    }
}