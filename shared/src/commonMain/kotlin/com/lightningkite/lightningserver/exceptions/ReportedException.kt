@file:UseContextualSerialization(Instant::class)
package com.lightningkite.lightningserver.exceptions

import com.lightningkite.lightningdb.*
import com.lightningkite.serialization.*
import kotlinx.datetime.Clock
import com.lightningkite.now
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlinx.datetime.Instant


@AdminTitleFields(["message"])
@AdminTableColumns(["message", "lastOccurredAt", "count"])
@Serializable
@GenerateDataClassPaths
data class ReportedExceptionGroup(
    override val _id: Int,
    val lastOccurredAt: Instant = now(),
    val count: Int = 1,
    val context: String,
    val server: String,
    val message: String,
    val trace: String,
): HasId<Int> {
}