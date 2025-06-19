package com.lightningkite.lightningserver.email

import com.lightningkite.EmailAddress
import kotlinx.serialization.Serializable

@Serializable
data class EmailLabeledValue(
    val value: String,
    val label: String = ""
) {
    constructor(email: EmailAddress, label: String = "") : this(email.toString(), label)
    companion object {
        fun parse(raw: String) =
            EmailLabeledValue(label = raw.substringBefore('<', "").trim(), value = raw.substringAfter('<').substringBefore('>').trim())
    }

    override fun toString(): String = "$label <$value>"
}