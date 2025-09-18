package com.lightningkite.lightningserver.definition

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable



/**
 * GeneralServerSettings is used to configure the server itself and how it runs on the machine.
 * That includes the port it will bind too, the host it run on, cors setup, and whether it's in debug mode.
 *
 * @param projectName Could also be called server name. [projectName] is used in many defaults here in Ktor Batteries but is not vital to the process.
 * @param publicUrl is meant to be a usable URL to the index of the server. This is used in many defaults here in Ktor Batteries.
 * @param wsUrl is meant to be a usable URL to the index of the server. This is used in many defaults here in Ktor Batteries.
 * @param debug states if the server should be in debug mode for development. This does not actually do anything particularly special to Ktor or Batteries. The only place it's used is in configureCors. This is meant to be used by the developer for their own use.
 */
@Serializable
public data class GeneralServerSettings(
    val projectName: String = "My Project",
    val publicUrl: String = "http://localhost:8080",
    val wsUrl: String = if(publicUrl.startsWith("https")) publicUrl.removePrefix("https").let { "wss$it" }
        else publicUrl.removePrefix("http").let { "ws$it" },
    val debug: Boolean = false,
    val emergencyContact: String? = null,
) {
    public val publicUrlDomain: String get() = publicUrl.substringAfter("://").substringBefore("/")
    public val wsUrlDomain: String get() = wsUrl.substringAfter("://").substringBefore("/")
    public fun absolutePathAdjustment(string: String): String {
        return if (string.startsWith("/")) {
            val inbetween = publicUrl.substringAfter("://").substringAfter("/", "")
            if (inbetween.isEmpty()) string
            else "/$inbetween$string"
        } else string
    }
}