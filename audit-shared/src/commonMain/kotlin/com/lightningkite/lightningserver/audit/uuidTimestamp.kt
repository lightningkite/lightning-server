package com.lightningkite.lightningserver.audit

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The epoch-millisecond timestamp a version-7 UUID embeds, or 0 for any other version.
 *
 * V7 lays the 48-bit millisecond timestamp into the top bits of the most-significant word, so a
 * right-shift by 16 recovers it. Any other version (an adopted proxy id, a legacy v4) stores no
 * timestamp there, so reading the bits blindly would report a plausible-looking but meaningless
 * instant; we check the version nibble first and return 0 instead, which the audit records render as
 * the epoch — candid about "unknown" rather than wrong.
 *
 * Shared by every audit row that derives its own "when" from its primary key rather than storing a
 * separate column.
 */
@OptIn(ExperimentalUuidApi::class)
internal val Uuid.epochMilliseconds: Long
    get() = toULongs { mostSignificantBits, _ ->
        if (((mostSignificantBits shr 12) and 0xFUL) != 0x7UL) 0L else (mostSignificantBits shr 16).toLong()
    }
