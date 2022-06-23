package com.lightningkite.ktorbatteries.settings

import com.lightningkite.ktorbatteries.SettingSingleton
import kotlinx.serialization.Serializable

/**
 * GeneralServerSettings is used to configure the server itself and how it runs on the machine.
 * That includes the port it will bind too, the host it run on, cors setup, and whether it's in debug mode.
 *
 * @param projectName Could also be called server name. [projectName] is used in many defaults here in Ktor Batteries but is not vital to the process.
 * @param host is used in the `embeddedServer` call for ktor and specifies the host of the server.
 * @param port is used in the `embeddedServer` call for ktor and specifies the port to bind to.
 * @param publicUrl is meant to be a usable URL to the index of the server. This is used in many defaults here in Ktor Batteries.
 * @param debug states if the server should be in debug mode for development. This does not actually do anything particularly special to Ktor or Batteries. The only place it's used is in configureCors. This is meant to be used by the developer for their own use.
 * @param cors defines a list of domains that are allows to communicate to the server. A `null` value with debug being true will allow ALL communications.
 */
@Serializable
data class GeneralServerSettings(
    val projectName: String = "My Project",
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val publicUrl: String = "http://$host:$port",
    val debug: Boolean = false,
    val cors: List<String>? = null
) {
    init {

    }

    companion object: SettingSingleton<GeneralServerSettings>()

    init { instance = this }
}
