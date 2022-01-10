package com.lightningkite.ktorkmongo.files

import com.lightningkite.ktorkmongo.settings.GeneralServerSettings
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class FilesSettings(
    val storageUrl: String = "file://${File("./local/files").absolutePath}"
) {
    companion object {
        var instance: FilesSettings = FilesSettings()
    }
    init { instance = this }
}