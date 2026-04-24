package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.runtime.test.test
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerEndpointsTest {

    @Test
    fun testRootEndpoint() = runBlocking {
        TestHelper.testServer {
            val response = Server.basic.root.test()
            assertEquals(HttpStatus.OK, response.status)
            assertTrue(response.body?.text()?.contains("Welcome") == true)
        }
    }

}
