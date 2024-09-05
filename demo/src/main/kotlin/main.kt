@file:UseContextualSerialization(Instant::class, UUID::class, ServerFile::class)

package com.lightningkite.lightningserver.demo

import com.lightningkite.kotlinercli.cli
import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.aws.terraformMigrate
import com.lightningkite.lightningserver.aws.terraformAws
import com.lightningkite.lightningserver.cache.*
import com.lightningkite.lightningserver.db.*
import com.lightningkite.lightningserver.ktor.runServer
import com.lightningkite.lightningserver.pubsub.LocalPubSub
import com.lightningkite.lightningserver.settings.loadSettings
import com.lightningkite.lightningserver.typed.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.*
import java.io.File
import kotlinx.datetime.Instant
import java.util.*
import com.lightningkite.UUID

fun setup() {
    Server
}

private fun serve() {
    loadSettings(File("settings.json"))
    runServer(LocalPubSub, LocalCache)
}

fun terraform() {
    Server
    terraformAws("com.lightningkite.lightningserver.demo.AwsHandler", "demo", File("demo/terraform2"))
}

fun tfMigrate() {
    Server
    terraformMigrate("com.lightningkite.lightningserver.demo.AwsHandler", File("demo/terraform"))
}

fun main(vararg args: String) {
    cli(
        arguments = args,
        setup = ::setup,
        available = listOf(::serve, ::terraform, ::tfMigrate, ::dbTest),
    )
}

fun dbTest(): Unit = runBlocking {
    Server
    loadSettings(File("settings.json"))
}
