package com.lightningkite.ktorbatteries.settings

import com.lightningkite.ktorbatteries.SettingSingleton
import kotlinx.serialization.Serializable

@Serializable
data class GeneralServerSettings(
    val projectName: String = "My Project",
    val host: String = "0.0.0.0",
    val port: Int = 80,
    val publicUrl: String = "http://$host:$port/",
    val debug: Boolean = false,
    val cors: List<String>? = null
) {
    init {

    }
    companion object: SettingSingleton<GeneralServerSettings>()
    init { instance = this }
}
