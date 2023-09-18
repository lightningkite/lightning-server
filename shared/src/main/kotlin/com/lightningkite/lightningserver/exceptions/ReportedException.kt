@file:UseContextualSerialization(Instant::class)
package com.lightningkite.lightningserver.exceptions

import com.lightningkite.lightningdb.*
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import java.time.Instant
import java.util.*

@AdminTitleFields(["message"])
@AdminTableColumns(["message", "lastOccurredAt", "count"])
@Serializable
@GenerateDataClassPaths
data class ReportedExceptionGroup(
    override val _id: Int,
    /*@Contextual */val lastOccurredAt: Instant = Instant.now(),
    val count: Int = 1,
    val context: String,
    val server: String,
    val message: String,
    val trace: String,
): HasId<Int> {
}