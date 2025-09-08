package com.lightningkite.lightningserver

import kotlinx.serialization.Serializable

@Serializable
public data class LSError(
    val http: Int,
    val detail: String = "",
    val message: String = "",
    val data: String = "",
    val stackTrace: String? = null,
)

@Serializable
public data class MultiplexMessage(
    val channel: String,
    val path: String? = null,
    val queryParams: Map<String, List<String>>? = null,
    val start: Boolean = false,
    val end: Boolean = false,
    val data: String? = null,
    val error: String? = null
)
