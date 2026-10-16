package com.lightningkite.lightningserver.audit

import com.lightningkite.services.data.Unsafe
import com.lightningkite.services.data.UuidV7
import com.lightningkite.services.database.TypedId
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@Serializable
@JvmInline
/** A shared version of `Execution.ID` from core lightning server. */
public value class ExecutionId @Unsafe("This should ALWAYS be constructed from a pre-existing Execution.ID") constructor(
    override val raw: UuidV7
) : TypedId<UuidV7, ExecutionId>