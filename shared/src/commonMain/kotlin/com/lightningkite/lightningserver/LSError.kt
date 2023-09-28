@file:SharedCode
package com.lightningkite.lightningserver

import com.lightningkite.khrysalis.SharedCode
import kotlinx.serialization.Serializable

@Serializable
data class LSError(
    val http: Int,
    val detail: String = "",
    val message: String = "",
    val data: String = "",
) {
}
