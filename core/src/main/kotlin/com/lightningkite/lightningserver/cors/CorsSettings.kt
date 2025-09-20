package com.lightningkite.lightningserver.cors

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
 * @param allowCredentials Specifies if Credentials are allowed for sharing.
 *      If allowCredentials is true, the header Access-Control-Allow-Credentials will be included with the value `true`.
 * @param cacheLength Specifics the allowed length(in seconds) for caching a prefight request.
 *      A non `null` value is placed directly into the Access-Control-Max-Age header.
 *      A `null` value means the header Access-Control-Max-Age is never sent.
 * @param forbidOnMatchFail If `true` ANY request with an `Origin` header that does not match any of the values in
 *      `limitToDomains` will result in an immediate Forbidden response. This response will happen before any further
 *      work is done. If `false` then all request play out as normal, and the headers returned in the response as
 *      expected. Websockets will always result in forbidden in these situations regardless of this value.
 */
@Serializable
public data class CorsSettings(
    val limitToDomains: List<String>? = emptyList(),
    val limitToHeaders: List<String>? = emptyList(),
    val limitToMethods: List<String>? = emptyList(),
    val exposedHeaders: List<String> = emptyList(),
    val allowCredentials: Boolean = false,
    val cacheLength: UInt? = null,
    val forbidOnMatchFail: Boolean = true,
)