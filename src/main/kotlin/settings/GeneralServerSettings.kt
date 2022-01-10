package com.lightningkite.ktorkmongo.settings

import kotlinx.serialization.Serializable

@Serializable
data class GeneralServerSettings(
    val address: String = "0.0.0.0",
    val port: Int = 80,
    val publicUrl: String = "http://$address:$port/",
    val debug: Boolean = false
) {
    companion object {
        var instance: GeneralServerSettings = GeneralServerSettings()
    }
    init { instance = this }
}
