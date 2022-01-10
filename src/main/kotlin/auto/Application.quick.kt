package com.lightningkite.ktorkmongo.auto

import io.ktor.application.*
import kotlin.reflect.KFunction

fun <SETTINGS> serverMain(
    arguments: Array<out String>,
    additionalCommands: List<KFunction<*>>,
    applicationSetup: Application.()->Unit
) {
    // Run CLI with custom settings loaded from file
}