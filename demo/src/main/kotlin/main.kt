@file:UseContextualSerialization(Instant::class, UUID::class, ServerFile::class)

package com.lightningkite.lightningserverdemo

import com.lightningkite.kotlinercli.cli
import com.lightningkite.lightningserver.cache.*
import com.lightningkite.lightningserver.files.ServerFile
import com.lightningkite.lightningserver.ktor.runServer
import com.lightningkite.lightningserver.pubsub.LocalPubSub
import com.lightningkite.lightningserver.settings.loadSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.*
import java.io.File
import kotlinx.datetime.Instant
import java.util.*
import com.lightningkite.UUID
import com.lightningkite.lightningserver.aws.terraform.createTerraform
import com.lightningkite.lightningserver.client
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.ktor.runServerNetty
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.now
import com.sun.management.HotSpotDiagnosticMXBean
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.discardRemaining
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.management.ManagementFactory
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

fun setup() {
    Server
}

private fun serve() {
    loadSettings(File("settings.json"))
    GlobalScope.launch {
        while(true) {
            delay(1.seconds)
            val total = Runtime.getRuntime().totalMemory()
            val free = Runtime.getRuntime().freeMemory()
            println("Memory: ${(total - free).memRender()}")
        }
    }
    Runtime.getRuntime().addShutdownHook(Thread {
        File("local/dumps").mkdirs()
        ManagementFactory.newPlatformMXBeanProxy(
            ManagementFactory.getPlatformMBeanServer(),
            "com.sun.management:type=HotSpotDiagnostic",
            HotSpotDiagnosticMXBean::class.java
        ).dumpHeap(
            "local/dumps/dump${now()}.hprof",
            true
        )
    })
    runServerNetty(LocalPubSub, LocalCache)
}

fun Long.memRender(): String {
    return when(this) {
        in 0 until 1024 -> "$this B"
        in 1024 until 1024*1024 -> "${this/1024} KB"
        in 1024*1024 until 1024*1024*1024 -> "${this/1024/1024} MB"
        else -> "${this/1024/1024/1024} GB"
    }
}

fun terraform() {
    Server
    createTerraform("com.lightningkite.lightningserverdemo.AwsHandler", "demo", File("demo/terraform"))
}

fun main(vararg args: String) {
    cli(
        arguments = args,
        setup = ::setup,
        available = listOf(::serve, ::terraform, ::hammer),
    )
}

fun hammer() {
    runBlocking {
        var requests = 0
        try {
            var tokenHolder: Deferred<String>? = null
            var tokenHolderAt: Instant = Instant.DISTANT_PAST
            suspend fun token(): String {
                if (now() - tokenHolderAt > 1.minutes) {
                    tokenHolderAt = now()
                    tokenHolder = async {
                        client.post("http://localhost:8080/${Server.subjects.tokenSimple.path.path}") {
                            accept(ContentType.Application.Json)
                            contentType(ContentType.Application.Json)
                            setBody("\"refresh/User/66fcc8c8-0350-4013-a823-e27c07af9654:TK4ryd93q4YQjgnEWTZGMmOV536Xkdzn\"")
                        }.also { if (!it.status.isSuccess()) println("FAIL ${it.status}: ${it.bodyAsText()}") }
                            .body<String>()
                            .trim('"')
                            .also { println("Got token: $it") }
                    }
                }
                return tokenHolder!!.await()
            }
            while (true) {
                delay(10.milliseconds)
                (0..50).map {
                    async {
                        client.post("http://localhost:8080/${Server.memLeakCheck.path}") {
                            header(HttpHeader.Authorization, "${token()}")
                            setBody(ByteArray(1_000_000) { it.toByte() })
                        }.also { if (!it.status.isSuccess()) println("FAIL ${it.status}: ${it.bodyAsText()}") }
                            .discardRemaining()
                        requests++
                    }
                }.awaitAll()
            }
        } finally {
            println("Completed $requests requests.")
        }
    }
}
