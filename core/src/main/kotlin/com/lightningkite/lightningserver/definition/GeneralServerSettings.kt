package com.lightningkite.lightningserver.definition

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable



/**
 * Configuration for general server runtime settings.
 *
 * These settings control how the server runs on the machine, including URLs for HTTP and WebSocket
 * connections, debug mode, and project identification. These values are used throughout Lightning Server
 * for constructing absolute URLs, CORS configuration, and other runtime behaviors.
 *
 * @property projectName A human-readable name for this server/project, used in various defaults and logging
 * @property publicUrl The base URL where this server is accessible (e.g., "https://api.example.com"). Used for
 *                     generating absolute URLs in responses, emails, and other contexts. Should not include a
 *                     trailing slash.
 * @property wsUrl The base WebSocket URL for this server (e.g., "wss://api.example.com"). Defaults to the
 *                 appropriate WebSocket protocol based on [publicUrl] (wss for https, ws for http).
 * @property debug Whether the server is running in debug/development mode. Affects CORS behavior and may be
 *                 used by application code for development-specific features. Should be false in production.
 * @property emergencyContact Optional contact information (email or phone) for emergency server issues.
 *                            May be used in error pages or monitoring alerts.
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
    /**
     * Extracts the domain (host and port) from [publicUrl].
     *
     * For example, "https://api.example.com:8080/path" returns "api.example.com:8080".
     */
    public val publicUrlDomain: String get() = publicUrl.substringAfter("://").substringBefore("/")

    /**
     * Extracts the domain (host and port) from [wsUrl].
     *
     * For example, "wss://api.example.com:8080/path" returns "api.example.com:8080".
     */
    public val wsUrlDomain: String get() = wsUrl.substringAfter("://").substringBefore("/")

    /**
     * Adjusts an absolute path to account for the server being hosted at a subpath.
     *
     * If [publicUrl] includes a path component (e.g., "https://example.com/api"), this function
     * prepends that path to absolute paths. Relative paths are returned unchanged.
     *
     * Examples:
     * - publicUrl = "https://example.com", path = "/users" → "/users"
     * - publicUrl = "https://example.com/api", path = "/users" → "/api/users"
     * - publicUrl = "https://example.com/api", path = "users" → "users" (relative, unchanged)
     *
     * @param string The path to adjust
     * @return The adjusted path that accounts for the server's base path
     */
    public fun absolutePathAdjustment(string: String): String {
        return if (string.startsWith("/")) {
            val inbetween = publicUrl.substringAfter("://").substringAfter("/", "")
            if (inbetween.isEmpty()) string
            else "/$inbetween$string"
        } else string
    }
}