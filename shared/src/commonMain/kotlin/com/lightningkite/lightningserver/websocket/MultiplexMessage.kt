package com.lightningkite.lightningserver.websocket

import kotlinx.serialization.Serializable

@Serializable
data class MultiplexMessage(
    val channel: String,
    val path: String? = null,
    val queryParams: Map<String, List<String>>? = null,
    val start: Boolean = false,
    val end: Boolean = false,
    val data: String? = null,
    val error: String? = null
)