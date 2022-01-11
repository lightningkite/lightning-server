package com.lightningkite.ktorbatteries.settings

import com.lightningkite.ktorbatteries.SetOnce
import com.lightningkite.ktorbatteries.SettingSingleton
import kotlinx.serialization.Serializable

@Serializable
data class GeneralServerSettings(
    val projectName: String = "My Project",
    val address: String = "0.0.0.0",
    val port: Int = 80,
    val publicUrl: String = "http://$address:$port/",
    val logging: String = "",
    val debug: Boolean = false
) {
    init {

    }
    companion object: SettingSingleton<GeneralServerSettings>()
    init { instance = this }
}
