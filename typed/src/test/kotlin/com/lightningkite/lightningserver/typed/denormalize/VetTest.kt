package com.lightningkite.lightningserver.typed.denormalize

import com.lightningkite.services.data.Queryable
import com.lightningkite.services.database.Modification
import com.lightningkite.services.database.optics.IdentityLens
import com.lightningkite.services.database.optics.Lens
import com.lightningkite.services.database.optics.Optic
import com.lightningkite.services.database.optics.elements
import com.lightningkite.services.database.optics.notNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VetTest {
    private fun <K, V> Modification<K>.vetted(path: Lens<K, V>): List<Modification<V>> =
        buildList { vet(path) { add(it) } }

    private fun <K, V> Modification<K>.vettedNullable(path: Optic<K, V>): List<Modification<V?>> =
        buildList { vetNullable(path) { add(it) } }

    @Test
    fun assign_on_the_path_is_reported() {
        val mod = Modification<VetRoot> { it.name assign "new" }
        assertEquals(listOf(Modification.Assign("new")), mod.vetted(VetRoot.name))
    }

    @Test
    fun other_modifications_on_the_path_are_reported_as_is() {
        val mod = Modification<VetRoot> { it.inner.value += 2 }
        assertEquals(listOf(Modification.Increment(2)), mod.vetted(VetRoot.inner.value))
    }

    @Test
    fun modifications_on_other_fields_are_ignored() {
        val mod = Modification<VetRoot> {
            it.name assign "new"
            it.inner.label assign "other"
        }
        assertEquals(emptyList<Modification<Int>>(), mod.vetted(VetRoot.inner.value))
    }

    @Test
    fun nested_assign_on_the_path_is_reported() {
        val mod = Modification<VetRoot> { it.inner.value assign 3 }
        assertEquals(listOf(Modification.Assign(3)), mod.vetted(VetRoot.inner.value))
    }

    @Test
    fun assign_on_a_parent_reports_the_value_at_the_path() {
        val mod = Modification<VetRoot> { it.inner assign VetInner(value = 7, label = "seven") }
        assertEquals(listOf(Modification.Assign(7)), mod.vetted(VetRoot.inner.value))
    }

    @Test
    fun assign_on_the_whole_model_reports_the_value_at_the_path() {
        val mod: Modification<VetRoot> = Modification.Assign(VetRoot(name = "replaced", inner = VetInner(value = 4)))
        assertEquals(listOf(Modification.Assign(4)), mod.vetted(VetRoot.inner.value))
    }

    @Test
    fun every_modification_in_a_chain_is_reported_in_order() {
        val mod = Modification<VetRoot> {
            it.inner.value assign 1
            it.name assign "between"
            it.inner.value += 5
        }
        assertEquals(
            listOf(Modification.Assign(1), Modification.Increment(5)),
            mod.vetted(VetRoot.inner.value),
        )
    }

    @Test
    fun modifications_through_a_nullable_field_are_reported() {
        val mod = Modification<VetRoot> { it.maybe.notNull.value assign 9 }
        assertEquals(listOf(Modification.Assign<Int?>(9)), mod.vettedNullable(VetRoot.maybe.notNull.value))
    }

    @Test
    fun other_modifications_through_a_nullable_field_are_reported_as_is() {
        val mod = Modification<VetRoot> { it.maybe.notNull.value += 2 }
        assertEquals<List<Any?>>(listOf(Modification.Increment(2)), mod.vettedNullable(VetRoot.maybe.notNull.value))
    }

    @Test
    fun assign_on_a_nullable_parent_reports_the_value_at_the_path() {
        val mod = Modification<VetRoot> { it.maybe assign VetInner(value = 6) }
        assertEquals(listOf(Modification.Assign<Int?>(6)), mod.vettedNullable(VetRoot.maybe.notNull.value))
    }

    @Test
    fun assigning_null_to_a_nullable_parent_reports_null() {
        val mod = Modification<VetRoot> { it.maybe assign null }
        assertEquals(listOf(Modification.Assign<Int?>(null)), mod.vettedNullable(VetRoot.maybe.notNull.value))
    }

    @Test
    fun an_identity_path_reports_every_part() {
        val mod = Modification<VetRoot> {
            it.name assign "new"
            it.inner.value += 1
        }
        assertEquals((mod as Modification.Chain).modifications, mod.vetted(IdentityLens(VetRoot.serializer())))
    }

    @Test
    fun collection_elements_are_rejected() {
        val mod = Modification<VetRoot> { it.tags assign listOf("a") }
        assertFailsWith<IllegalArgumentException> { mod.vettedNullable(VetRoot.tags.elements) }
    }
}

@Queryable
data class VetRoot(
    val name: String = "",
    val inner: VetInner = VetInner(),
    val maybe: VetInner? = null,
    val tags: List<String> = listOf(),
)

@Queryable
data class VetInner(
    val value: Int = 0,
    val label: String = "",
)
