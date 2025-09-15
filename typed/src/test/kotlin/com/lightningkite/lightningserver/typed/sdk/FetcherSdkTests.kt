package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.typed.ModelRestEndpoints
import com.lightningkite.lightningserver.typed.jsonschema.openApiDescription
import com.lightningkite.lightningserver.typed.sdk.SDK.sdk
import com.lightningkite.lightningserver.typed.sdk.SDK.writeSdk
import com.lightningkite.services.data.KFile
import kotlin.test.Test

class FetcherSdkTests {
    private val folder = KFile("./src/test/kotlin/com/lightningkite/lightningserver/typed/sdk/generated")

    @Test
    fun test() {
        val build = Server.build()
        println("Modules: ${build.modules}")
        println("Extensions: ${build.extensions.entries.size}")

        build.sdk().let {
            println("Children: ${it.children}")
        }
        Server.writeSdk(FetcherSdk, folder, "com.lightningkite.lightningserver.typed.sdk")
    }

    @Test
    fun openApi() {
        Server.test({}) {
            openApiDescription
                .paths
                .flatMap { (_, path) ->
                    listOf(path.get, path.put, path.post, path.patch, path.delete).mapNotNull { it?.operationId }
                }
                .forEach(::println)
        }
    }
}