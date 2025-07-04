package com.lightningkite.lightningserver.email

import kotlinx.serialization.Serializable

@Serializable
data class EmailLabeledValue(
    val value: String,
    val label: String = ""
) {
    companion object {
        fun parse(raw: String) =
            EmailLabeledValue(label = raw.substringBefore('<', "").trim(), value = raw.substringAfter('<').substringBefore('>').trim())
    }

    override fun toString(): String = "$label <$value>"
}