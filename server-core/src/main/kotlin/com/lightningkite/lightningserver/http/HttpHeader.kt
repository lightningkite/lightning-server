package com.lightningkite.lightningserver.http

object HttpHeader {
    // Permanently registered standard HTTP headers
    // The list is taken from http://www.iana.org/assignments/message-headers/message-headers.xml#perm-headers

    const val Accept: String = "Accept"
    const val AcceptCharset: String = "Accept-Charset"
    const val AcceptEncoding: String = "Accept-Encoding"
    const val AcceptLanguage: String = "Accept-Language"
    const val AcceptRanges: String = "Accept-Ranges"
    const val Age: String = "Age"
    const val Allow: String = "Allow"

    // Application-Layer Protocol Negotiation, HTTP/2
    const val ALPN: String = "ALPN"
    const val AuthenticationInfo: String = "Authentication-Info"
    const val Authorization: String = "Authorization"
    const val CacheControl: String = "Cache-Control"
    const val Connection: String = "Connection"
    const val ContentDisposition: String = "Content-Disposition"
    const val ContentEncoding: String = "Content-Encoding"
    const val ContentLanguage: String = "Content-Language"
    const val ContentLength: String = "Content-Length"
    const val ContentLocation: String = "Content-Location"
    const val ContentRange: String = "Content-Range"
    const val ContentType: String = "Content-Type"
    const val Cookie: String = "Cookie"

    // WebDAV Search
    const val DASL: String = "DASL"
    const val Date: String = "Date"

    // WebDAV
    const val DAV: String = "DAV"
    const val Depth: String = "Depth"

    const val Destination: String = "Destination"
    const val ETag: String = "ETag"
    const val Expect: String = "Expect"
    const val Expires: String = "Expires"
    const val From: String = "From"
    const val Forwarded: String = "Forwarded"
    const val Host: String = "Host"
    const val HTTP2Settings: String = "HTTP2-Settings"
    const val If: String = "If"
    const val IfMatch: String = "If-Match"
    const val IfModifiedSince: String = "If-Modified-Since"
    const val IfNoneMatch: String = "If-None-Match"
    const val IfRange: String = "If-Range"
    const val IfScheduleTagMatch: String = "If-Schedule-Tag-Match"
    const val IfUnmodifiedSince: String = "If-Unmodified-Since"
    const val LastModified: String = "Last-Modified"
    const val Location: String = "Location"
    const val LockToken: String = "Lock-Token"
    const val Link: String = "Link"
    const val MaxForwards: String = "Max-Forwards"
    const val MIMEVersion: String = "MIME-Version"
    const val OrderingType: String = "Ordering-Type"
    const val Origin: String = "Origin"
    const val Overwrite: String = "Overwrite"
    const val Position: String = "Position"
    const val Pragma: String = "Pragma"
    const val Prefer: String = "Prefer"
    const val PreferenceApplied: String = "Preference-Applied"
    const val ProxyAuthenticate: String = "Proxy-Authenticate"
    const val ProxyAuthenticationInfo: String = "Proxy-Authentication-Info"
    const val ProxyAuthorization: String = "Proxy-Authorization"
    const val constKeyPins: String = "const-Key-Pins"
    const val constKeyPinsReportOnly: String = "const-Key-Pins-Report-Only"
    const val Range: String = "Range"
    const val Referrer: String = "Referer"
    const val RetryAfter: String = "Retry-After"
    const val ScheduleReply: String = "Schedule-Reply"
    const val ScheduleTag: String = "Schedule-Tag"
    const val SecWebSocketAccept: String = "Sec-WebSocket-Accept"
    const val SecWebSocketExtensions: String = "Sec-WebSocket-Extensions"
    const val SecWebSocketKey: String = "Sec-WebSocket-Key"
    const val SecWebSocketProtocol: String = "Sec-WebSocket-Protocol"
    const val SecWebSocketVersion: String = "Sec-WebSocket-Version"
    const val Server: String = "Server"
    const val SetCookie: String = "Set-Cookie"

    // Atom Publishing
    const val SLUG: String = "SLUG"
    const val StrictTransportSecurity: String = "Strict-Transport-Security"
    const val TE: String = "TE"
    const val Timeout: String = "Timeout"
    const val Trailer: String = "Trailer"
    const val TransferEncoding: String = "Transfer-Encoding"
    const val Upgrade: String = "Upgrade"
    const val UserAgent: String = "User-Agent"
    const val Vary: String = "Vary"
    const val Via: String = "Via"
    const val Warning: String = "Warning"
    const val WWWAuthenticate: String = "WWW-Authenticate"

    // CORS
    const val AccessControlAllowOrigin: String = "Access-Control-Allow-Origin"
    const val AccessControlAllowMethods: String = "Access-Control-Allow-Methods"
    const val AccessControlAllowCredentials: String = "Access-Control-Allow-Credentials"
    const val AccessControlAllowHeaders: String = "Access-Control-Allow-Headers"

    const val AccessControlRequestMethod: String = "Access-Control-Request-Method"
    const val AccessControlRequestHeaders: String = "Access-Control-Request-Headers"
    const val AccessControlExposeHeaders: String = "Access-Control-Expose-Headers"
    const val AccessControlMaxAge: String = "Access-Control-Max-Age"

    // Unofficial de-facto headers
    const val XHttpMethodOverride: String = "X-Http-Method-Override"
    const val XForwardedHost: String = "X-Forwarded-Host"
    const val XForwardedServer: String = "X-Forwarded-Server"
    const val XForwardedProto: String = "X-Forwarded-Proto"
    const val XForwardedFor: String = "X-Forwarded-For"

    const val XForwardedPort: String = "X-Forwarded-Port"

    const val XRequestId: String = "X-Request-ID"
    const val XCorrelationId: String = "X-Correlation-ID"
    const val XTotalCount: String = "X-Total-Count"
}

private fun test() {
    HttpHeaders
}