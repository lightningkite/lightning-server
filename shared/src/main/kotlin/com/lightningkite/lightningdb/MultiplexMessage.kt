@file:SharedCode
package com.lightningkite.lightningdb

import com.lightningkite.khrysalis.SharedCode
import kotlinx.serialization.Serializable

@Serializable
data class MultiplexMessage(
    val channel: String,
    val path: String? = null,
    val start: Boolean = false,
    val end: Boolean = false,
    val data: String? = null,
    val error: String? = null
)