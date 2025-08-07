package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.HttpHeaders
import com.lightningkite.services.data.TypedData

public data class HttpResponse(
    public val body: TypedData? = null,
    public val status: HttpStatus = if (body != null) HttpStatus.OK else HttpStatus.NoContent,
    public val headers: HttpHeaders = HttpHeaders.Companion.EMPTY,
) {
    public companion object
}