package com.lightningkite.lightningserver.audit

import com.lightningkite.services.data.Queryable
import com.lightningkite.services.database.HasId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.uuid.Uuid

@Queryable
data class Patient(
    override val _id: ID,
    @Audited val name: String,
    @Audited val ssn: String,
    @Audited val address: Address? = null,
    // Not itemised itself, but the walk still descends: `phones[].number` is.
    val phones: List<Phone> = listOf(),
    // Nothing beneath is itemised, so this whole subtree costs no bits.
    val tags: Map<String, String> = mapOf(),
    @Audited val doctor: Doctor? = null,
    val visits: Int = 0,
) : AuditedModel<Patient.ID> {
    @Serializable
    @JvmInline
    value class ID(override val raw: Uuid) : AuditedModel.Id<Uuid, ID> {
        override val asUuid: Uuid get() = raw
    }
}

@Serializable
data class Address(@Audited val street: String, val city: String)

@Serializable
data class Phone(@Audited val number: String, val label: String)

/** Audited in its own right, so it must not consume bits of the model that carries it. */
@Serializable
data class Doctor(override val _id: ID, @Audited val name: String) : AuditedModel<Doctor.ID> {
    @Serializable
    @JvmInline
    value class ID(override val raw: Uuid) : AuditedModel.Id<Uuid, ID> {
        override val asUuid: Uuid get() = raw
    }

    /** Not audited, but has an `_id` of its own that must not be mistaken for its parent's. */
    @Serializable
    data class Ref(val _id: Uuid)
}

@Serializable
sealed class Payment {
    @Serializable
    @SerialName("Card")
    data class Card(@Audited val last4: String) : Payment()

    @Serializable
    @SerialName("Cash")
    data class Cash(val amount: Int) : Payment()
}

@Serializable
data class Order(override val _id: ID, @Audited val payment: Payment) : AuditedModel<Order.ID> {
    @Serializable
    @JvmInline
    value class ID(override val raw: Uuid) : AuditedModel.Id<Uuid, ID> {
        override val asUuid: Uuid get() = raw
    }
}

/** Two subclasses share an audited field name, so a path naming the wrong one is caught. */
@Serializable
sealed interface Channel {
    @Serializable
    @SerialName("Email")
    data class Email(@Audited val address: String) : Channel

    @Serializable
    @SerialName("Post")
    data class Post(@Audited val address: String) : Channel

    /** No `@SerialName`, so its path segment is the fully qualified class name. */
    @Serializable
    data class Pigeon(@Audited val loft: String) : Channel
}

@Serializable
data class Subscriber(
    override val _id: ID,
    @Audited val via: Channel,
    val fallbacks: List<Channel> = listOf(),
) : AuditedModel<Subscriber.ID> {
    @Serializable
    @JvmInline
    value class ID(override val raw: Uuid) : AuditedModel.Id<Uuid, ID> {
        override val asUuid: Uuid get() = raw
    }
}

/** Each subclass is its own audited model, reached only through the sealed parent. */
@Serializable
sealed interface Visit {
    @Serializable
    @SerialName("Checkup")
    data class Checkup(override val _id: ID, @Audited val notes: String) : Visit, AuditedModel<Visit.Checkup.ID> {
        @Serializable
        @JvmInline
        value class ID(override val raw: Uuid) : AuditedModel.Id<Uuid, ID> {
            override val asUuid: Uuid get() = raw
        }
    }

    @Serializable
    @SerialName("Surgery")
    data class Surgery(override val _id: ID, @Audited val notes: String) : Visit, AuditedModel<Visit.Surgery.ID> {
        @Serializable
        @JvmInline
        value class ID(override val raw: Uuid) : AuditedModel.Id<Uuid, ID> {
            override val asUuid: Uuid get() = raw
        }
    }
}

/** Other Uuids, raw and wrapped, on both sides of `_id`, plus a nested audited record before it. */
@Serializable
data class Referencing(
    val before: Uuid,
    val typedBefore: TypedKeyed.ID,
    @Audited val doctor: Doctor? = null,
    override val _id: ID,
    val after: Uuid,
    val typedAfter: TypedKeyed.ID,
    val nested: Doctor.Ref? = null,
    @Audited val value: String = "",
) : AuditedModel<Referencing.ID> {
    @Serializable
    @JvmInline
    value class ID(override val raw: Uuid) : AuditedModel.Id<Uuid, ID> {
        override val asUuid: Uuid get() = raw
    }
}

@Serializable
data class Node(@Audited val label: String = "", val child: Node? = null)

@Serializable
data class Tree(override val _id: ID, @Audited val root: Node = Node()) : AuditedModel<Tree.ID> {
    @Serializable
    @JvmInline
    value class ID(override val raw: Uuid) : AuditedModel.Id<Uuid, ID> {
        override val asUuid: Uuid get() = raw
    }
}

@Serializable
data class TypedKeyed(override val _id: ID, @Audited val value: String) : AuditedModel<TypedKeyed.ID> {
    @Serializable
    @JvmInline
    value class ID(override val raw: Uuid) : AuditedModel.Id<Uuid, ID> {
        override val asUuid: Uuid get() = raw
    }
}

/** Exercises descent into map *values*, whose path separator differs from a list's. */
@Serializable
data class Contact(
    override val _id: ID,
    val label: String = "",
    @Audited val numbers: Map<String, Phone> = mapOf(),
) : AuditedModel<Contact.ID> {
    @Serializable
    @JvmInline
    value class ID(override val raw: Uuid) : AuditedModel.Id<Uuid, ID> {
        override val asUuid: Uuid get() = raw
    }
}

@Serializable
enum class Severity { Low, High }

@Serializable
data class Reading(
    override val _id: ID,
    @Audited val severity: Severity? = null,
    @Audited val count: Int = 0,
    @Audited val flagged: Boolean = false,
    @Audited val code: Code = Code(0),
    @Audited val note: String? = null,
) : AuditedModel<Reading.ID> {
    @Serializable
    @JvmInline
    value class ID(override val raw: Uuid) : AuditedModel.Id<Uuid, ID> {
        override val asUuid: Uuid get() = raw
    }

    @Serializable
    @JvmInline
    value class Code(val raw: Int)
}

/** One audited field of every type, each defaulting to the most empty value that type allows. */
@Serializable
data class Emptiness(
    override val _id: ID,
    @Audited val boolean: Boolean = false,
    @Audited val byte: Byte = 0,
    @Audited val short: Short = 0,
    @Audited val int: Int = 0,
    @Audited val long: Long = 0,
    @Audited val float: Float = 0f,
    @Audited val double: Double = 0.0,
    @Audited val char: Char = '\u0000',
    @Audited val string: String = "",
    @Audited val list: List<String> = listOf(),
    @Audited val set: Set<String> = setOf(),
    @Audited val map: Map<String, Int> = mapOf(),
    @Audited val wrapped: Wrapped = Wrapped(""),
    @Audited val nullableInt: Int? = null,
    @Audited val nullableString: String? = null,
    @Audited val nullableList: List<String>? = null,
    @Audited val nullableWrapped: Wrapped? = null,
) : AuditedModel<Emptiness.ID> {
    @Serializable
    @JvmInline
    value class ID(override val raw: Uuid) : AuditedModel.Id<Uuid, ID> {
        override val asUuid: Uuid get() = raw
    }

    @Serializable
    @JvmInline
    value class Wrapped(val raw: String)
}

@Serializable
data class Deep3(
    @Audited val d0: String = "",
    @Audited val d1: String = "",
    @Audited val d2: String = "",
    @Audited val d3: String = "",
    @Audited val d4: String = "",
    @Audited val d5: String = "",
    @Audited val d6: String = "",
    @Audited val d7: String = "",
    @Audited val d8: String = "",
    @Audited val d9: String = "",
    @Audited val d10: String = "",
    @Audited val d11: String = "",
)

@Serializable
data class Deep2(
    val n0: Deep3 = Deep3(),
    val n1: Deep3 = Deep3(),
    val n2: Deep3 = Deep3(),
    val n3: Deep3 = Deep3(),
    val n4: Deep3 = Deep3(),
    val n5: Deep3 = Deep3(),
    val n6: Deep3 = Deep3(),
    val n7: Deep3 = Deep3(),
    val n8: Deep3 = Deep3(),
    val n9: Deep3 = Deep3(),
    val n10: Deep3 = Deep3(),
    val n11: Deep3 = Deep3(),
)

/** 144 itemised paths — deliberately past the 64 available to one model. */
@Serializable
data class Wide(override val _id: ID, val root: Deep2 = Deep2()) : AuditedModel<Wide.ID> {
    @Serializable
    @JvmInline
    value class ID(override val raw: Uuid) : AuditedModel.Id<Uuid, ID> {
        override val asUuid: Uuid get() = raw
    }
}

// Three shapes of the same audited model over time, sharing a serial name so that they share a
// model id: fields added, then a field renamed.
@Serializable
@SerialName("Versioned")
data class VersionedV1(override val _id: ID, @Audited val a: String) : AuditedModel<VersionedV1.ID> {
    @Serializable
    @JvmInline
    value class ID(override val raw: Uuid) : AuditedModel.Id<Uuid, ID> {
        override val asUuid: Uuid get() = raw
    }
}

@Serializable
@SerialName("Versioned")
data class VersionedV2(override val _id: ID, @Audited val a: String, @Audited val b: String) : AuditedModel<VersionedV2.ID> {
    @Serializable
    @JvmInline
    value class ID(override val raw: Uuid) : AuditedModel.Id<Uuid, ID> {
        override val asUuid: Uuid get() = raw
    }
}

@Serializable
@SerialName("Versioned")
data class VersionedV3(override val _id: ID, @Audited val renamed: String, @Audited val b: String) : AuditedModel<VersionedV3.ID> {
    @Serializable
    @JvmInline
    value class ID(override val raw: Uuid) : AuditedModel.Id<Uuid, ID> {
        override val asUuid: Uuid get() = raw
    }
}

/** 50 itemised paths — past the 75% mark of the 64 available, but not past the ceiling. */
@Serializable
data class Roomy(
    override val _id: ID,
    @Audited val f0: String = "",
    @Audited val f1: String = "",
    @Audited val f2: String = "",
    @Audited val f3: String = "",
    @Audited val f4: String = "",
    @Audited val f5: String = "",
    @Audited val f6: String = "",
    @Audited val f7: String = "",
    @Audited val f8: String = "",
    @Audited val f9: String = "",
    @Audited val f10: String = "",
    @Audited val f11: String = "",
    @Audited val f12: String = "",
    @Audited val f13: String = "",
    @Audited val f14: String = "",
    @Audited val f15: String = "",
    @Audited val f16: String = "",
    @Audited val f17: String = "",
    @Audited val f18: String = "",
    @Audited val f19: String = "",
    @Audited val f20: String = "",
    @Audited val f21: String = "",
    @Audited val f22: String = "",
    @Audited val f23: String = "",
    @Audited val f24: String = "",
    @Audited val f25: String = "",
    @Audited val f26: String = "",
    @Audited val f27: String = "",
    @Audited val f28: String = "",
    @Audited val f29: String = "",
    @Audited val f30: String = "",
    @Audited val f31: String = "",
    @Audited val f32: String = "",
    @Audited val f33: String = "",
    @Audited val f34: String = "",
    @Audited val f35: String = "",
    @Audited val f36: String = "",
    @Audited val f37: String = "",
    @Audited val f38: String = "",
    @Audited val f39: String = "",
    @Audited val f40: String = "",
    @Audited val f41: String = "",
    @Audited val f42: String = "",
    @Audited val f43: String = "",
    @Audited val f44: String = "",
    @Audited val f45: String = "",
    @Audited val f46: String = "",
    @Audited val f47: String = "",
    @Audited val f48: String = "",
    @Audited val f49: String = "",
) : AuditedModel<Roomy.ID> {
    @Serializable
    @JvmInline
    value class ID(override val raw: Uuid) : AuditedModel.Id<Uuid, ID> {
        override val asUuid: Uuid get() = raw
    }
}

/** Not audited, so the data access log must pass it through untouched. */
@Queryable
data class PlainThing(override val _id: Uuid, val value: String) : HasId<Uuid>

/**
 * Not audited itself, but reaches an audited model through a field.
 *
 * The shape that used to slip past the data access log: gating on the table's own descriptor found no
 * `@Audited` here and returned the table undecorated, so every read of the Patient inside went
 * unrecorded.
 */
@Queryable
data class PatientWrapper(override val _id: Uuid, val patient: Patient) : HasId<Uuid>

/** The [OriginRecord] id that audit records made under this execution's origin join to. */
@get:OptIn(com.lightningkite.services.data.Unsafe::class)
internal val com.lightningkite.lightningserver.runtime.Execution.ID.originId: OriginRecord.ID
    get() = OriginRecord.ID(toExternal())

/** The route part of [OriginRecord.location], e.g. `/meta/bulk` for `POST /meta/bulk`. */
internal val OriginRecord<*>.endpoint: String
    get() = location.substringAfter(' ')

// SAFETY: Freshly generated v7, standing in for an execution that was never run.
@OptIn(com.lightningkite.services.data.Unsafe::class)
internal fun randomExecutionId(): ExecutionId = ExecutionId(com.lightningkite.services.data.UuidV7.generate())

internal fun fieldIds(vararg raw: Int): FieldIdentifierSet = FieldIdentifierSet.from(raw.map(::ModelFieldId))
