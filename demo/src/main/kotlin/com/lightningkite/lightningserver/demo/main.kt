package com.lightningkite.lightningserver.demo

import com.lightningkite.kotlinercli.cli
import com.lightningkite.lightningserver.engine.jdk.JdkEngine
import com.lightningkite.lightningserver.engine.ktor.KtorEngine
import com.lightningkite.lightningserver.engine.netty.NettyEngine
import com.lightningkite.lightningserver.settings.loadFromFile
import com.lightningkite.lightningserver.typed.sdk.FetcherSdk
import com.lightningkite.lightningserver.typed.sdk.SDK.writeUsingDefaultSettings
import com.lightningkite.services.kfile.KFile
import io.ktor.server.netty.*
import kotlin.time.TimeSource


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
    FetcherSdk("com.lightningkite.lightningserver.demo").writeUsingDefaultSettings(
        Server,
        KFile("demo/src/main/kotlin/sdk")
    )
    println("Finished")
}

fun main(vararg args: String) {
    cli(
        arguments = args,
        available = listOf(::serve, ::serveJdk, ::serveNetty, ::sdk),
    )
}
