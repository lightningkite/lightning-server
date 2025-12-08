package com.lightningkite.lightningserver.http

/**
 * Represents an HTTP status code.
 *
 * This is a lightweight value class wrapping an integer status code. Use the companion object
 * constants for standard HTTP status codes, or construct with a custom code if needed.
 *
 * Example:
 * ```kotlin
 * val ok = HttpStatus.OK // 200
 * val custom = HttpStatus(418) // I'm a teapot
 * if (status.success) { /* handle success */ }
 * ```
 *
 * @property code The numeric HTTP status code (100-599)
 */
@JvmInline
public value class HttpStatus(public val code: Int) {
    /**
     * Returns true if this status code indicates success (2xx range).
     */
    public val success: Boolean get() = code / 100 == 2

    public companion object {
        public val Continue: HttpStatus = HttpStatus(100)
        public val SwitchingProtocols: HttpStatus = HttpStatus(101)
        public val Processing: HttpStatus = HttpStatus(102)
        public val OK: HttpStatus = HttpStatus(200)
        public val Created: HttpStatus = HttpStatus(201)
        public val Accepted: HttpStatus = HttpStatus(202)
        public val NonAuthoritativeInformation: HttpStatus = HttpStatus(203)
        public val NoContent: HttpStatus = HttpStatus(204)
        public val ResetContent: HttpStatus = HttpStatus(205)
        public val PartialContent: HttpStatus = HttpStatus(206)
        public val MultiStatus: HttpStatus = HttpStatus(207)
        public val MultipleChoices: HttpStatus = HttpStatus(300)
        public val MovedPermanently: HttpStatus = HttpStatus(301)
        public val Found: HttpStatus = HttpStatus(302)
        public val SeeOther: HttpStatus = HttpStatus(303)
        public val NotModified: HttpStatus = HttpStatus(304)
        public val UseProxy: HttpStatus = HttpStatus(305)
        public val SwitchProxy: HttpStatus = HttpStatus(306)
        public val TemporaryRedirect: HttpStatus = HttpStatus(307)
        public val PermanentRedirect: HttpStatus = HttpStatus(308)
        public val BadRequest: HttpStatus = HttpStatus(400)
        public val Unauthorized: HttpStatus = HttpStatus(401)
        public val PaymentRequired: HttpStatus = HttpStatus(402)
        public val Forbidden: HttpStatus = HttpStatus(403)
        public val NotFound: HttpStatus = HttpStatus(404)
        public val MethodNotAllowed: HttpStatus = HttpStatus(405)
        public val NotAcceptable: HttpStatus = HttpStatus(406)
        public val ProxyAuthenticationRequired: HttpStatus = HttpStatus(407)
        public val RequestTimeout: HttpStatus = HttpStatus(408)
        public val Conflict: HttpStatus = HttpStatus(409)
        public val Gone: HttpStatus = HttpStatus(410)
        public val LengthRequired: HttpStatus = HttpStatus(411)
        public val PreconditionFailed: HttpStatus = HttpStatus(412)
        public val PayloadTooLarge: HttpStatus = HttpStatus(413)
        public val RequestURITooLong: HttpStatus = HttpStatus(414)
        public val UnsupportedMediaType: HttpStatus = HttpStatus(415)
        public val RequestedRangeNotSatisfiable: HttpStatus = HttpStatus(416)
        public val ExpectationFailed: HttpStatus = HttpStatus(417)
        public val UnprocessableEntity: HttpStatus = HttpStatus(422)
        public val Locked: HttpStatus = HttpStatus(423)
        public val FailedDependency: HttpStatus = HttpStatus(424)
        public val UpgradeRequired: HttpStatus = HttpStatus(426)
        public val TooManyRequests: HttpStatus = HttpStatus(429)
        public val RequestHeaderFieldTooLarge: HttpStatus = HttpStatus(431)
        public val InternalServerError: HttpStatus = HttpStatus(500)
        public val NotImplemented: HttpStatus = HttpStatus(501)
        public val BadGateway: HttpStatus = HttpStatus(502)
        public val ServiceUnavailable: HttpStatus = HttpStatus(503)
        public val GatewayTimeout: HttpStatus = HttpStatus(504)
        public val VersionNotSupported: HttpStatus = HttpStatus(505)
        public val VariantAlsoNegotiates: HttpStatus = HttpStatus(506)
        public val InsufficientStorage: HttpStatus = HttpStatus(507)

        /**
         * Map of status codes to their standard textual descriptions.
         * Used by toString() to provide human-readable status representations.
         */
        public val strings: Map<Int, String> = mapOf(
            100 to "Continue",
            101 to "Switching Protocols",
            102 to "Processing",
            200 to "OK",
            201 to "Created",
            202 to "Accepted",
            203 to "Non-Authoritative Information",
            204 to "No Content",
            205 to "Reset Content",
            206 to "Partial Content",
            207 to "Multi-Status",
            300 to "Multiple Choices",
            301 to "Moved Permanently",
            302 to "Found",
            303 to "See Other",
            304 to "Not Modified",
            305 to "Use Proxy",
            306 to "Switch Proxy",
            307 to "Temporary Redirect",
            308 to "Permanent Redirect",
            400 to "Bad Request",
            401 to "Unauthorized",
            402 to "Payment Required",
            403 to "Forbidden",
            404 to "Not Found",
            405 to "Method Not Allowed",
            406 to "Not Acceptable",
            407 to "Proxy Authentication Required",
            408 to "Request Timeout",
            409 to "Conflict",
            410 to "Gone",
            411 to "Length Required",
            412 to "Precondition Failed",
            413 to "Payload Too Large",
            414 to "Request-URI Too Long",
            415 to "Unsupported Media Type",
            416 to "Requested Range Not Satisfiable",
            417 to "Expectation Failed",
            422 to "Unprocessable Entity",
            423 to "Locked",
            424 to "Failed Dependency",
            426 to "Upgrade Required",
            429 to "Too Many Requests",
            431 to "Request Header Fields Too Large",
            500 to "Internal Server Error",
            501 to "Not Implemented",
            502 to "Bad Gateway",
            503 to "Service Unavailable",
            504 to "Gateway Timeout",
            505 to "HTTP Version Not Supported",
            506 to "Variant Also Negotiates",
            507 to "Insufficient Storage",
        )
    }

    override fun toString(): String = code.toString() + (strings[code]?.let { " $it" } ?: "")
}

/*
 * TODO: API Recommendations for HttpStatus.kt
 *
 * 1. Add convenience properties for status code categories:
 *    - val isInformational: Boolean (1xx)
 *    - val isRedirection: Boolean (3xx)
 *    - val isClientError: Boolean (4xx)
 *    - val isServerError: Boolean (5xx)
 *
 * 2. Missing some common status codes:
 *    - 418 I'm a teapot (often used for testing/jokes)
 *    - 451 Unavailable For Legal Reasons
 *    - 425 Too Early
 *
 * 3. Consider adding a description property that returns the standard text for the code:
 *    - val description: String?
 *
 * 4. Add validation to ensure status codes are in valid range (100-599):
 *    - init { require(code in 100..599) { "Invalid HTTP status code: $code" } }
 */