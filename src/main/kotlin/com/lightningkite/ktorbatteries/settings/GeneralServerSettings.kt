package com.lightningkite.ktorbatteries.settings

import com.lightningkite.ktorbatteries.SettingSingleton
import kotlinx.serialization.Serializable

@Serializable
data class GeneralServerSettings(
    val projectName: String = "My Project",
    val host: String = "0.0.0.0",
    val port: Int = 80,
    val publicUrl: String = "http://$host:$port/",
    val logging: String = "",
    val debug: Boolean = false
) {
    init {

    }
    companion object: SettingSingleton<GeneralServerSettings>()
    init { instance = this }
}
