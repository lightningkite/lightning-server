package com.lightningkite.lightningserver.audit

import com.lightningkite.services.database.TypedId
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/** The permanent id of an audited model type, as assigned by the audit registry. */
@Serializable
@JvmInline
public value class ModelTypeId(override val raw: Int) : TypedId<Int, ModelTypeId>
