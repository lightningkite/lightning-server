package com.lightningkite.lightningserver.audit

import com.lightningkite.services.database.TypedId
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/** The permanent id of one audited field within its model, as assigned by the audit registry; its bit in a [FieldIdentifierSet]. */
@Serializable
@JvmInline
public value class ModelFieldId(override val raw: Int) : TypedId<Int, ModelFieldId> {
    init {
        require(raw in 0..<FieldIdentifierSet.CAPACITY) {
            "Field id $raw is out of range; a FieldIdentifierSet holds ids 0..${FieldIdentifierSet.CAPACITY - 1}."
        }
    }
}
