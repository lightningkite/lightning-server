@file:UseContextualSerialization(Instant::class, UUID::class, ServerFile::class)

package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.aws.terraformAws
import com.lightningkite.lightningserver.cache.*
import com.lightningkite.lightningserver.db.*
import com.lightningkite.lightningserver.ktor.runServer
import com.lightningkite.lightningserver.pubsub.LocalPubSub
import com.lightningkite.lightningserver.settings.loadSettings
import com.lightningkite.lightningserver.typed.*
import kotlinx.serialization.*
import java.io.File
import java.time.Instant
import java.util.*

fun main(vararg args: String) {
    Server
    loadSettings(File("settings.json"))
    runServer(LocalPubSub, LocalCache)

//    println(Documentable.kotlinApi("test"))

//    terraformAws("com.lightningkite.lightningserver.demo.AwsHandler", "demo", File("demo/terraform"))

//    println(buildString { terraformAzure("demo", this) })
}