@file:Suppress("ConstPropertyName")

package com.lightningkite.lightningserver.http

public object HttpHeader {
    // Permanently registered standard HTTP headers
    // The list is taken from http://www.iana.org/assignments/message-headers/message-headers.xml#perm-headers

    public const val Accept: String = "Accept"
    public const val AcceptCharset: String = "Accept-Charset"
    public const val AcceptEncoding: String = "Accept-Encoding"
    public const val AcceptLanguage: String = "Accept-Language"
    public const val AcceptRanges: String = "Accept-Ranges"
    public const val Age: String = "Age"
    public const val Allow: String = "Allow"

    // Application-Layer Protocol Negotiation, HTTP/2
    public const val ALPN: String = "ALPN"
    public const val AuthenticationInfo: String = "Authentication-Info"
    public const val Authorization: String = "Authorization"
    public const val CacheControl: String = "Cache-Control"
    public const val Connection: String = "Connection"
    public const val ContentDisposition: String = "Content-Disposition"
    public const val ContentEncoding: String = "Content-Encoding"
    public const val ContentLanguage: String = "Content-Language"
    public const val ContentLength: String = "Content-Length"
    public const val ContentLocation: String = "Content-Location"
    public const val ContentRange: String = "Content-Range"
    public const val ContentType: String = "Content-Type"
    public const val Cookie: String = "Cookie"

    // WebDAV Search
    public const val DASL: String = "DASL"
    public const val Date: String = "Date"

    // WebDAV
    public const val DAV: String = "DAV"
    public const val Depth: String = "Depth"

    public const val Destination: String = "Destination"
    public const val ETag: String = "ETag"
    public const val Expect: String = "Expect"
    public const val Expires: String = "Expires"
    public const val From: String = "From"
    public const val Forwarded: String = "Forwarded"
    public const val Host: String = "Host"
    public const val HTTP2Settings: String = "HTTP2-Settings"
    public const val If: String = "If"
    public const val IfMatch: String = "If-Match"
    public const val IfModifiedSince: String = "If-Modified-Since"
    public const val IfNoneMatch: String = "If-None-Match"
    public const val IfRange: String = "If-Range"
    public const val IfScheduleTagMatch: String = "If-Schedule-Tag-Match"
    public const val IfUnmodifiedSince: String = "If-Unmodified-Since"
    public const val LastModified: String = "Last-Modified"
    public const val Location: String = "Location"
    public const val LockToken: String = "Lock-Token"
    public const val Link: String = "Link"
    public const val MaxForwards: String = "Max-Forwards"
    public const val MIMEVersion: String = "MIME-Version"
    public const val OrderingType: String = "Ordering-Type"
    public const val Origin: String = "Origin"
    public const val Overwrite: String = "Overwrite"
    public const val Position: String = "Position"
    public const val Pragma: String = "Pragma"
    public const val Prefer: String = "Prefer"
    public const val PreferenceApplied: String = "Preference-Applied"
    public const val ProxyAuthenticate: String = "Proxy-Authenticate"
    public const val ProxyAuthenticationInfo: String = "Proxy-Authentication-Info"
    public const val ProxyAuthorization: String = "Proxy-Authorization"
    public const val constKeyPins: String = "const-Key-Pins"
    public const val constKeyPinsReportOnly: String = "const-Key-Pins-Report-Only"
    public const val Range: String = "Range"
    public const val Referrer: String = "Referer"
    public const val RetryAfter: String = "Retry-After"
    public const val ScheduleReply: String = "Schedule-Reply"
    public const val ScheduleTag: String = "Schedule-Tag"
    public const val SecWebSocketAccept: String = "Sec-WebSocket-Accept"
    public const val SecWebSocketExtensions: String = "Sec-WebSocket-Extensions"
    public const val SecWebSocketKey: String = "Sec-WebSocket-Key"
    public const val SecWebSocketProtocol: String = "Sec-WebSocket-Protocol"
    public const val SecWebSocketVersion: String = "Sec-WebSocket-Version"
    public const val Server: String = "Server"
    public const val SetCookie: String = "Set-Cookie"

    // Atom Publishing
    public const val SLUG: String = "SLUG"
    public const val StrictTransportSecurity: String = "Strict-Transport-Security"
    public const val TE: String = "TE"
    public const val Timeout: String = "Timeout"
    public const val Trailer: String = "Trailer"
    public const val TransferEncoding: String = "Transfer-Encoding"
    public const val Upgrade: String = "Upgrade"
    public const val UserAgent: String = "User-Agent"
    public const val Vary: String = "Vary"
    public const val Via: String = "Via"
    public const val Warning: String = "Warning"
    public const val WWWAuthenticate: String = "WWW-Authenticate"

    // CORS
    public const val AccessControlAllowOrigin: String = "Access-Control-Allow-Origin"
    public const val AccessControlAllowMethods: String = "Access-Control-Allow-Methods"
    public const val AccessControlAllowCredentials: String = "Access-Control-Allow-Credentials"
    public const val AccessControlAllowHeaders: String = "Access-Control-Allow-Headers"

    public const val AccessControlRequestMethod: String = "Access-Control-Request-Method"
    public const val AccessControlRequestHeaders: String = "Access-Control-Request-Headers"
    public const val AccessControlExposeHeaders: String = "Access-Control-Expose-Headers"
    public const val AccessControlMaxAge: String = "Access-Control-Max-Age"

    // Unofficial de-facto headers
    public const val XHttpMethodOverride: String = "X-Http-Method-Override"
    public const val XForwardedHost: String = "X-Forwarded-Host"
    public const val XForwardedServer: String = "X-Forwarded-Server"
    public const val XForwardedProto: String = "X-Forwarded-Proto"
    public const val XForwardedFor: String = "X-Forwarded-For"

    public const val XForwardedPort: String = "X-Forwarded-Port"

    public const val XRequestId: String = "X-Request-ID"
    public const val XCorrelationId: String = "X-Correlation-ID"
    public const val XTotalCount: String = "X-Total-Count"

    public const val XMasquerade: String = "X-Masquerade"
}
