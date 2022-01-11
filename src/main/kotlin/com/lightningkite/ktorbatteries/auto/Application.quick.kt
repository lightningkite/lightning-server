package com.lightningkite.ktorbatteries.auto

import com.lightningkite.ktorbatteries.files.FilesSettings
import com.lightningkite.ktorbatteries.files.configureFiles
import com.lightningkite.ktorbatteries.serialization.configureSerialization
import io.ktor.application.*

fun Application.defaults() {
    configureSerialization()
    if(FilesSettings.isConfigured) configureFiles()
}