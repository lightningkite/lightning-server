package com.lightningkite.lightningserver.http

@JvmInline
value class HttpStatus(val code: Int) {
    val success: Boolean get() = code / 100 == 2
    companion object {
        val Continue = HttpStatus(100)
        val SwitchingProtocols = HttpStatus(101)
        val Processing = HttpStatus(102)
        val OK = HttpStatus(200)
        val Created = HttpStatus(201)
        val Accepted = HttpStatus(202)
        val NonAuthoritativeInformation = HttpStatus(203)
        val NoContent = HttpStatus(204)
        val ResetContent = HttpStatus(205)
        val PartialContent = HttpStatus(206)
        val MultiStatus = HttpStatus(207)
        val MultipleChoices = HttpStatus(300)
        val MovedPermanently = HttpStatus(301)
        val Found = HttpStatus(302)
        val SeeOther = HttpStatus(303)
        val NotModified = HttpStatus(304)
        val UseProxy = HttpStatus(305)
        val SwitchProxy = HttpStatus(306)
        val TemporaryRedirect = HttpStatus(307)
        val PermanentRedirect = HttpStatus(308)
        val BadRequest = HttpStatus(400)
        val Unauthorized = HttpStatus(401)
        val PaymentRequired = HttpStatus(402)
        val Forbidden = HttpStatus(403)
        val NotFound = HttpStatus(404)
        val MethodNotAllowed = HttpStatus(405)
        val NotAcceptable = HttpStatus(406)
        val ProxyAuthenticationRequired = HttpStatus(407)
        val RequestTimeout = HttpStatus(408)
        val Conflict = HttpStatus(409)
        val Gone = HttpStatus(410)
        val LengthRequired = HttpStatus(411)
        val PreconditionFailed = HttpStatus(412)
        val PayloadTooLarge = HttpStatus(413)
        val RequestURITooLong = HttpStatus(414)
        val UnsupportedMediaType = HttpStatus(415)
        val RequestedRangeNotSatisfiable = HttpStatus(416)
        val ExpectationFailed = HttpStatus(417)
        val UnprocessableEntity = HttpStatus(422)
        val Locked = HttpStatus(423)
        val FailedDependency = HttpStatus(424)
        val UpgradeRequired = HttpStatus(426)
        val TooManyRequests = HttpStatus(429)
        val RequestHeaderFieldTooLarge = HttpStatus(431)
        val InternalServerError = HttpStatus(500)
        val NotImplemented = HttpStatus(501)
        val BadGateway = HttpStatus(502)
        val ServiceUnavailable = HttpStatus(503)
        val GatewayTimeout = HttpStatus(504)
        val VersionNotSupported = HttpStatus(505)
        val VariantAlsoNegotiates = HttpStatus(506)
        val InsufficientStorage = HttpStatus(507)

        val strings = mapOf(
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