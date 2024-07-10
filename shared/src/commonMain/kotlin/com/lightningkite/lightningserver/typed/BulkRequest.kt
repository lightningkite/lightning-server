package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningdb.Description
import com.lightningkite.lightningserver.LSError
import kotlinx.serialization.Serializable

@Serializable
data class BulkRequest(
    val path: String,
    val method: String,
    @Description("JSON")
    val body: String? = null
)

@Serializable
data class BulkResponse(
    @Description("JSON")
    val result: String? = null,
    val error: LSError? = null,
    val durationMs: Long = 0L
)
