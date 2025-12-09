package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.typed.jsonschema.openApiDescription
import com.lightningkite.lightningserver.typed.sdk.SDK.writeSdkUsingDefaultSettings
import com.lightningkite.services.data.KFile
import kotlin.test.Test

class FetcherSdkTests {
    private val folder = KFile("./src/test/kotlin/com/lightningkite/lightningserver/typed/sdk/generated/fetcher")

    @Test
    fun test() {
        Server.writeSdkUsingDefaultSettings(FetcherSdk("com.lightningkite.lightningserver.typed.sdk"), folder)
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