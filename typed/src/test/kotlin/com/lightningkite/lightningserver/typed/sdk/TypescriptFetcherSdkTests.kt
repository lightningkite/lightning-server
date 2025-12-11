package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.lightningserver.typed.sdk.SDK.writeUsingDefaultSettings
import com.lightningkite.services.data.KFile
import kotlin.test.Test

class TypescriptFetcherSdkTests {
    private val folder = KFile("./src/test/kotlin/com/lightningkite/lightningserver/typed/sdk/generated/typescript")

    @Test
    fun testSingleFile() {
        TypescriptFetcherSdk(fileStructure = TypescriptFetcherSdk.Structure.SingleFile("sdk.ts")).writeUsingDefaultSettings(
            Server, folder
        )
    }

    @Test
    fun testMultiFile() {
        TypescriptFetcherSdk(includeDocComments = false).writeUsingDefaultSettings(Server, folder)
    }
}