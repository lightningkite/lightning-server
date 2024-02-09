package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.TestSettings
import kotlinx.coroutines.runBlocking
import org.junit.Test

class BulkRequestEndpointTest {
    @Test
    fun test(): Unit = runBlocking {
        TestSettings.bulk.test(
            authenticatedAs = TestSettings.sampleUser.await(),
            input = mapOf(
                "sample1" to BulkRequest(TestSettings.sample1.route.path.path.toString(), TestSettings.sample1.route.method.toString(), "18"),
                "sample2" to BulkRequest(TestSettings.sample2.route.path.path.toString(), TestSettings.sample2.route.method.toString(), "22"),
                "sample3" to BulkRequest(TestSettings.sample3.route.path.path.toString(), TestSettings.sample3.route.method.toString(), "22"),
            )
        ).also { println(it) }
    }
}
