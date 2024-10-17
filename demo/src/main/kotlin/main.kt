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

fun setup() {
    Server
}

private fun serve() {
    loadSettings(File("settings.json"))
    runServer(LocalPubSub, LocalCache)
}

fun terraform() {
    Server
    createTerraform("com.lightningkite.lightningserverdemo.AwsHandler", "demo", File("demo/terraform"))
}

fun main(vararg args: String) {
    cli(
        arguments = args,
        setup = ::setup,
        available = listOf(::serve, ::terraform),
    )
}
