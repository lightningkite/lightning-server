@file: UseContextualSerialization(ServerFile::class, UUID::class)

package com.lightningkite.lightningserver.db

import com.lightningkite.UUID
import com.lightningkite.lightningdb.Condition
import com.lightningkite.lightningserver.files.ServerFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization

@Serializable
enum class DumpType { CSV, JSON_LINES }

@Serializable
data class DumpRequest<T>(
    val condition: Condition<T>,
    val type: DumpType,
    val email: String? = null
)
