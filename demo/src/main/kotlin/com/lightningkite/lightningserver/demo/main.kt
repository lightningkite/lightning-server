package com.lightningkite.lightningserver.demo

import com.lightningkite.kotlinercli.*
import com.lightningkite.lightningserver.engine.jdk.*
import com.lightningkite.lightningserver.engine.ktor.*
import com.lightningkite.lightningserver.engine.netty.*
import com.lightningkite.lightningserver.settings.*
import com.lightningkite.lightningserver.typed.sdk.*
import com.lightningkite.lightningserver.typed.sdk.SDK.writeSdkUsingDefaultSettings
import com.lightningkite.services.data.*
import io.ktor.server.netty.*
import kotlin.time.*


private fun serve() {
    val before = TimeSource.Monotonic.markNow()
    val built = Server.build()
    println("Server built in ${before.elapsedNow()}")
    KtorEngine(built).apply {
        settings.loadFromFile(KFile("settings.json"), internalSerializersModule)
        start(Netty)
    }
}

private fun serveJdk() {
    val before = TimeSource.Monotonic.markNow()
    val built = Server.build()
    println("Server built in ${before.elapsedNow()}")
    JdkEngine(built).apply {
        settings.loadFromFile(KFile("settings.json"), internalSerializersModule)
        start()
    }
}

private fun serveNetty() {
    val before = TimeSource.Monotonic.markNow()
    val built = Server.build()
    println("Server built in ${before.elapsedNow()}")
    NettyEngine(built).apply {
        settings.loadFromFile(KFile("settings.json"), internalSerializersModule)
        start()
    }
}

fun sdk() {
    println("Writing SDK")
    Server.writeSdkUsingDefaultSettings(FetcherSdk("com.lightningkite.lightningserver.demo"), KFile("demo/src/main/kotlin/sdk"))
    println("Finished")
}

fun main(vararg args: String) {
    cli(
        arguments = args,
        available = listOf(::serve, ::serveJdk, ::serveNetty, ::sdk),
    )
}
