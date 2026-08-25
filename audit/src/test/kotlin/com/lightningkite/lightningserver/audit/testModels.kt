package com.lightningkite.lightningserver.audit

import com.lightningkite.services.database.HasId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
@Audited
data class Patient(
    override val _id: Uuid,
    val name: String,
    val ssn: String,
    val address: Address? = null,
    val phones: List<Phone> = listOf(),
    val tags: Map<String, String> = mapOf(),
    val doctor: Doctor? = null,
) : HasId<Uuid>

@Serializable
data class Address(val street: String, val city: String)

@Serializable
data class Phone(val number: String, val label: String)

/** Audited in its own right, so it must not consume bits of the model that carries it. */
@Serializable
@Audited
data class Doctor(override val _id: Uuid, val name: String) : HasId<Uuid>

@Serializable
sealed class Payment {
    @Serializable
    @SerialName("Card")
    data class Card(val last4: String) : Payment()

    @Serializable
    @SerialName("Cash")
    data class Cash(val amount: Int) : Payment()
}

@Serializable
@Audited
data class Order(override val _id: Uuid, val payment: Payment) : HasId<Uuid>

@Serializable
data class Node(val label: String = "", val child: Node? = null)

@Serializable
@Audited
data class Tree(override val _id: Uuid, val root: Node = Node()) : HasId<Uuid>

/** No `_id`, so no disclosure record could say which record was disclosed. */
@Serializable
@Audited
data class Anonymous(val value: String)

/** Keyed by a String, which is too wide for a table written to once per record disclosed. */
@Serializable
@Audited
data class StringKeyed(override val _id: String, val value: String) : HasId<String>

@Serializable
data class Deep3(
    val d0: String = "",
    val d1: String = "",
    val d2: String = "",
    val d3: String = "",
    val d4: String = "",
    val d5: String = "",
    val d6: String = "",
    val d7: String = "",
    val d8: String = "",
    val d9: String = "",
    val d10: String = "",
    val d11: String = "",
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

/** 158 field paths — deliberately past the 128 available to one model. */
@Serializable
@Audited
data class Wide(override val _id: Uuid, val root: Deep2 = Deep2()) : HasId<Uuid>

// Three shapes of the same audited model over time, sharing a serial name so that they share a
// model id: fields added, then a field renamed.
@Serializable
@SerialName("Versioned")
@Audited
data class VersionedV1(override val _id: Uuid, val a: String) : HasId<Uuid>

@Serializable
@SerialName("Versioned")
@Audited
data class VersionedV2(override val _id: Uuid, val a: String, val b: String) : HasId<Uuid>

@Serializable
@SerialName("Versioned")
@Audited
data class VersionedV3(override val _id: Uuid, val renamed: String, val b: String) : HasId<Uuid>
