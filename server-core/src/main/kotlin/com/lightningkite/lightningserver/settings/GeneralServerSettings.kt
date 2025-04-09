package com.lightningkite.lightningserver.settings

import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpMethod
import kotlinx.serialization.Serializable


/**
 * CorsSettings is used to configure Cross Origin Resource Sharing.
 * These settings determine how the server will apply CORS headers during requests.
 *
 * limitToDomains will have a smart match applied to it. This allows for wildcard ("*") subdomains. If you add `https://\*.some.domain` as a limit,
 * any Origin provided that is a sub domain of `some.domain` will be allowed to share. This does mean the wildcard on it's own will
 * match every value in the Origins domain. The wildcard will not be returned in this case, but the Origin domain will be.
 * You can omit the Schema in your limit and any schema will be accepted from the Origin. If you provide a schema than it too must match.
 *
 * The engine will not attempt any smart functionality when it comes to limited values of methods and headers.
 * It does the dumb static method of dumping these values directly into the header responses. Any behavior changes
 * required for allowed credentials is up to the implementor to properly configure.
 *
 * Providing a null value to a limit field will allow literally everything by mirroring the request values into the
 * response headers. This is not recommended outside testing environments.
 *
 * For backwards compatibility, allowedDomains and allowedHeaders will override the new limit values.
 *
 * @param allowedDomains (Deprecated) Specifies what domains are allowed for sharing. Will behave the same as limitToDomains. Will be removed in V5.
 * @param allowedHeaders (Deprecated) Specifies what Headers are allowed for sharing. If used will automatically have Content-Type and Authorization applied as backwards compatibility. Will be removed in V5.
 * @param limitToDomains Specifies what domains are limited for sharing.
 *      These values are NOT placed directly into the Access-Control-Allow-Origin.
 *      The values will be compared against the incoming Origin header.
 *      If a match is made, then the incoming Origin header will be placed into the response Access-Control-Allow-Origin header.
 *      A `null` value means there are no limits and the request Origin is mirrored onto the response Access-Control-Allow-Origin header.
 * @param limitToHeaders Specifies what headers are limited for sharing.
 *      These values get directly placed into the Access-Control-Allow-Headers header.
 *      A `null` value means there are no limits and the Access-Control-Request-Headers values are mirrored onto the Access-Control-Allow-Headers header.
 * @param limitToMethods Specifies what methods are limited for sharing.
 *      These values get directly placed into the Access-Control-Allow-Methods header.
 *      A `null` value means there are no limits and the Access-Control-Request-Method values are mirrored onto the Access-Control-Allow-Methods header.
 * @param exposedHeaders Specifies what headers are available for sharing beyond the request headers.
 *      These values get directly placed into the Access-Control-Expose-Methods header.
 *      A `null` value will be considered an empty list. Nullability is for backwards compatibility. Nullability will be removed in V5.
 * @param allowCredentials Specifies if Credentials are allowed for sharing.
 *      If allowCredentials is true, the header Access-Control-Allow-Credentials will be included with the value `true`.
 *      A `null` value will be considered false. Nullability is for backwards compatibility. Nullability will be removed in V5.
 */
@Serializable
data class CorsSettings(
    @Deprecated("allowedDomains is deprecated. Use limitToDomains. This will be removed in V5")
    val allowedDomains: List<String>? = null,
    @Deprecated("allowedHeaders is deprecated. Use limitToHeaders. This will be removed in V5.")
    val allowedHeaders: List<String>? = null,
    val limitToDomains: List<String>? = emptyList(),
    val limitToHeaders: List<String>? = emptyList(),
    val limitToMethods: List<String>? = emptyList(),
    val exposedHeaders: List<String>? = emptyList(), // Nullability will be removed in V5. It is there for backwards compatibility
    val allowCredentials: Boolean? = false, // Nullability will be removed in V5. It is there for backwards compatibility
)

/**
 * GeneralServerSettings is used to configure the server itself and how it runs on the machine.
 * That includes the port it will bind too, the host it run on, cors setup, and whether it's in debug mode.
 *
 * @param projectName Could also be called server name. [projectName] is used in many defaults here in Ktor Batteries but is not vital to the process.
 * @param host is used in the `embeddedServer` call for ktor and specifies the host of the server.
 * @param port is used in the `embeddedServer` call for ktor and specifies the port to bind to.
 * @param publicUrl is meant to be a usable URL to the index of the server. This is used in many defaults here in Ktor Batteries.
 * @param debug states if the server should be in debug mode for development. This does not actually do anything particularly special to Ktor or Batteries. The only place it's used is in configureCors. This is meant to be used by the developer for their own use.
 * @param cors defines a list of domains that are allows to communicate to the server. A `null` value means no CORS headers are applied.
 */
@Serializable
data class GeneralServerSettings(
    val projectName: String = "My Project",
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val publicUrl: String = "http://$host:$port",
    val wsUrl: String = publicUrl.removePrefix("http").let { "ws" + it },
    val debug: Boolean = false,
    val realIpHeader: String? = null,
    val cors: CorsSettings? = if (debug) CorsSettings(
        limitToDomains = null,
        limitToHeaders = null,
        limitToMethods = null,
        allowCredentials = true,
    ) else null,
    val emergencyContact: String? = null,
) {
    fun absolutePathAdjustment(string: String): String {
        return if (string.startsWith("/")) {
            val inbetween = publicUrl.substringAfter("://").substringAfter("/", "")
            if (inbetween.isEmpty()) string
            else "/$inbetween$string"
        } else string
    }
}

val generalSettings = setting("general", GeneralServerSettings())
