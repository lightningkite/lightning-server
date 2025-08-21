package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningserver.engine.ktor.KtorEngine
import com.lightningkite.lightningserver.settings.loadFromFile
import com.lightningkite.lightningserver.typed.mediaTypeEncoders
import io.ktor.server.netty.Netty
import java.io.File

//import com.lightningkite.kotlinercli.cli
//import com.lightningkite.lightningserver.cache.*
//import com.lightningkite.lightningserver.files.ServerFile
//import com.lightningkite.lightningserver.ktor.runServer
//import com.lightningkite.lightningserver.pubsub.LocalPubSub
//import com.lightningkite.lightningserver.settings.loadSettings
//import kotlinx.coroutines.runBlocking
//import kotlinx.serialization.*
//import java.io.File
//import kotlinx.datetime.Instant
//import java.util.*
//import com.lightningkite.UUID
//import com.lightningkite.lightningserver.aws.terraform.createTerraform
//import com.lightningkite.lightningserver.ktor.runServerNetty
//import com.lightningkite.lightningserver.pubsub.BadPubSub

//fun setup() {
//    Server
//}

private fun serve() {
    KtorEngine(Server.build()).apply {
        settings.loadFromFile(File("settings.json"), internalSerializersModule)
        start(Netty)
    }

//    loadSettings(File("settings.json"))
//    runServerNetty(BadPubSub, LocalCache)
}

//fun terraform() {
//    Server
////    createTerraform("com.lightningkite.lightningserverdemo.AwsHandler", "demo", File("demo/terraform"))
//}

fun main(vararg args: String) {
    val server = Server.build()
    println("Coders: ${server.mediaTypeEncoders.entries.joinToString { "${it.key}: ${it.value}" }}")
    assert(server.mediaTypeEncoders.isNotEmpty())
    KtorEngine(server).apply {
        println(server.endpoints.entries.joinToString("\n"))
        settings.loadFromFile(File("settings.json"), internalSerializersModule)
        start(Netty)
    }
}
