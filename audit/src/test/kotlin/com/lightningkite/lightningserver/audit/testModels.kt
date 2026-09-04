package com.lightningkite.lightningserver.audit

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.HasId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
@GenerateDataClassPaths
@Audited
data class Patient(
    override val _id: Uuid,
    @Audited val name: String,
    @Audited val ssn: String,
    @Audited val address: Address? = null,
    // Not itemised itself, but the walk still descends: `phones[].number` is.
    val phones: List<Phone> = listOf(),
    // Nothing beneath is itemised, so this whole subtree costs no bits.
    val tags: Map<String, String> = mapOf(),
    @Audited val doctor: Doctor? = null,
) : HasId<Uuid>

@Serializable
data class Address(@Audited val street: String, val city: String)

@Serializable
data class Phone(@Audited val number: String, val label: String)

/** Audited in its own right, so it must not consume bits of the model that carries it. */
@Serializable
@Audited
data class Doctor(override val _id: Uuid, @Audited val name: String) : HasId<Uuid>

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
@Audited
data class Order(override val _id: Uuid, @Audited val payment: Payment) : HasId<Uuid>

@Serializable
data class Node(@Audited val label: String = "", val child: Node? = null)

@Serializable
@Audited
data class Tree(override val _id: Uuid, @Audited val root: Node = Node()) : HasId<Uuid>

/** No `_id`, so no disclosure record could say which record was disclosed. */
@Serializable
@Audited
data class Anonymous(@Audited val value: String)

/** Keyed by a String, which is too wide for a table written to once per record disclosed. */
@Serializable
@Audited
data class StringKeyed(override val _id: String, @Audited val value: String) : HasId<String>

/** Exercises descent into map *values*, whose path separator differs from a list's. */
@Serializable
@Audited
data class Contact(
    override val _id: Uuid,
    val label: String = "",
    @Audited val numbers: Map<String, Phone> = mapOf(),
) : HasId<Uuid>

@Serializable
enum class Severity { Low, High }

@Serializable
@Audited
data class Reading(
    override val _id: Uuid,
    @Audited val severity: Severity? = null,
    @Audited val count: Int = 0,
    @Audited val flagged: Boolean = false,
) : HasId<Uuid>

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
@Audited
data class Wide(override val _id: Uuid, val root: Deep2 = Deep2()) : HasId<Uuid>

// Three shapes of the same audited model over time, sharing a serial name so that they share a
// model id: fields added, then a field renamed.
@Serializable
@SerialName("Versioned")
@Audited
data class VersionedV1(override val _id: Uuid, @Audited val a: String) : HasId<Uuid>

@Serializable
@SerialName("Versioned")
@Audited
data class VersionedV2(override val _id: Uuid, @Audited val a: String, @Audited val b: String) : HasId<Uuid>

@Serializable
@SerialName("Versioned")
@Audited
data class VersionedV3(override val _id: Uuid, @Audited val renamed: String, @Audited val b: String) : HasId<Uuid>

/** 50 itemised paths — past the 75% mark of the 64 available, but not past the ceiling. */
@Serializable
@Audited
data class Roomy(
    override val _id: Uuid,
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
) : HasId<Uuid>

/** Not audited, so the data access log must pass it through untouched. */
@Serializable
@GenerateDataClassPaths
data class PlainThing(override val _id: Uuid, val value: String) : HasId<Uuid>

/**
 * Not audited itself, but reaches an audited model through a field.
 *
 * The shape that used to slip past the data access log: gating on the table's own descriptor found no
 * `@Audited` here and returned the table undecorated, so every read of the Patient inside went
 * unrecorded.
 */
@Serializable
@GenerateDataClassPaths
data class PatientWrapper(override val _id: Uuid, val patient: Patient) : HasId<Uuid>
