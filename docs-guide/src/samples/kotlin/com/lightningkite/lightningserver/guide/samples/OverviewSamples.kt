package com.lightningkite.lightningserver.guide.samples

// region overview-imports
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.test.*
import kotlinx.coroutines.*
// endregion overview-imports

// region overview-server
// A complete Lightning Server in ~5 lines: declare a ServerBuilder, add an endpoint.
object OverviewServer : ServerBuilder() {

    // GET / — responds "Hello, world!"
    val root = path.get bind HttpHandler {
        HttpResponse.plainText("Hello, world!")
    }
}
// endregion overview-server

// region overview-server-test
fun overviewServerTest() = runBlocking {
    OverviewServer.test(settings = {}) {
        val response = OverviewServer.root.test()
        check(response.body?.text() == "Hello, world!")
    }
}
// endregion overview-server-test
