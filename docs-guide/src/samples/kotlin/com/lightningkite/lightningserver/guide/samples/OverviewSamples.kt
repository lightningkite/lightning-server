package com.lightningkite.lightningserver.guide.samples

// region overview-imports
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.settings.*
// endregion overview-imports

// region overview-server
object OverviewServer : ServerBuilder() {
    val greeting = setting("greeting", "Hello")

    val root = path.get bind HttpHandler {
        HttpResponse.plainText("${greeting()}, world!")
    }
}
// endregion overview-server

// region overview-server-test
fun overviewServerTest() = OverviewServer.testBlocking(settings = { greeting set "Howdy" }) {
    val response = OverviewServer.root.test()
    check(response.body?.text() == "Howdy, world!")
}
// endregion overview-server-test

// region overview-context
context(engine: Engine)
fun shout(): String = OverviewServer.greeting().uppercase()
// endregion overview-context

fun overviewContextTest() = OverviewServer.testBlocking(settings = {}) {
    check(shout() == "HELLO")
}
