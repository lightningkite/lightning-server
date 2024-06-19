@file: UseContextualSerialization(ServerFile::class, UUID::class)

package com.lightningkite.lightningserver.db

import com.lightningkite.UUID
import com.lightningkite.lightningdb.Condition
import com.lightningkite.lightningdb.GenerateDataClassPaths
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.ServerFile
import com.lightningkite.now
import com.lightningkite.uuid
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlin.time.Duration.Companion.hours

@Serializable
enum class DumpType { CSV, JSON_LINES }

@Serializable
data class DumpRequest<T>(
    val condition: Condition<T>,
    val type: DumpType,
    val email: String? = null
)
