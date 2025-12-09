package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.lightningserver.typed.sdk.SDK.writeSdkUsingDefaultSettings
import com.lightningkite.services.data.KFile
import kotlin.test.Test

class TypescriptFetcherSdkTests {
    private val folder = KFile("./src/test/kotlin/com/lightningkite/lightningserver/typed/sdk/generated/typescript")

    @Test
    fun testSingleFile() {
        Server.writeSdkUsingDefaultSettings(
            TypescriptFetcherSDK(fileStructure = TypescriptFetcherSDK.Files.SingleFile("sdk.ts")),
            folder
        )
    }

    @Test
    fun testMultiFile() {
        Server.writeSdkUsingDefaultSettings(
            TypescriptFetcherSDK(includeDocComments = false),
            folder,
        )
    }
}