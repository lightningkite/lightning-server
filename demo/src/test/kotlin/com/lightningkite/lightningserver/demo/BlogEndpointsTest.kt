package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.runtime.test.test
import kotlin.test.Test

class BlogEndpointsTest {
    @Test
    fun test() {
        TestHelper.testServer {
            blogAssist.tools.forEach { string, tool ->
                println("$string: ${tool.koogDescriptor(serverRuntime.externalSerialization.json.serializersModule)}")
            }
        }
    }
}