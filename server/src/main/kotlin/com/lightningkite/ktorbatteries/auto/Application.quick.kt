package com.lightningkite.ktorbatteries.auto

import com.lightningkite.ktorbatteries.files.FilesSettings
import com.lightningkite.ktorbatteries.files.configureFiles
import com.lightningkite.ktorbatteries.serialization.configureSerialization
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*


/**
 * A shortcut function that calls the most common configure functions used.
 */
fun Application.defaults() {
    configureSerialization()
    if (FilesSettings.isConfigured) configureFiles()
}
