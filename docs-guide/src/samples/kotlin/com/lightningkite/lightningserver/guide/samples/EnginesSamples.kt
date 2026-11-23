package com.lightningkite.lightningserver.guide.samples

import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.engine.jdk.JdkEngine
import com.lightningkite.lightningserver.engine.netty.NettyEngine
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.settings.loadFromFile
import com.lightningkite.services.kfile.KFile
import kotlin.time.Duration.Companion.minutes

// region serve-netty
fun serveNetty() {
    NettyEngine(HelloServer.build()).apply {
        settings.loadFromFile(KFile("settings.json"), internalSerializersModule)
        start()
    }
}
// endregion serve-netty

// region serve-jdk
fun serveJdk() {
    JdkEngine(HelloServer.build()).apply {
        settings.loadFromFile(KFile("settings.json"), internalSerializersModule)
        start()
    }
}
// endregion serve-jdk

// region handler-timeout
object SlowReportServer : ServerBuilder() {
    val report = path.path("report").get bind HttpHandler(timeout = 5.minutes) {
        HttpResponse.plainText("done")
    }
}
// endregion handler-timeout
