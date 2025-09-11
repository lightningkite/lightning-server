package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.lightningserver.typed.sdk.SDK.writeSdk
import com.lightningkite.services.data.KFile
import kotlin.test.Test

class FetcherSdkTests {
    private val folder = KFile("./src/test/kotlin/com/lightningkite/lightningserver/typed/sdk/generated")

    @Test
    fun test() {
        Server.writeSdk(FetcherSdk, folder, "com.lightningkite.lightningserver.typed.sdk")
    }
}