package com.lightningkite.lightningserver

import com.lightningkite.serviceabstractions.data.TypedData

public data class HttpResponse(
    public val body: TypedData? = null,
    public val status: HttpStatus = if (body != null) HttpStatus.OK else HttpStatus.NoContent,
    public val headers: HttpHeaders = HttpHeaders.EMPTY,
) {
    public companion object
}