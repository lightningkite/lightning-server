package com.lightningkite.lightningserver.audit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DescriptorWalkTest {

    @Test
    fun `an audited model is found through wrappers, lists and nullability`() {
        val found = Patient.serializer().descriptor.auditedModels().keys
        assertTrue(found.any { it.endsWith("Patient") }, "the root audited model was missed; found $found")
        assertTrue(found.any { it.endsWith("Doctor") }, "a nested audited model was missed; found $found")
    }

    @Test
    fun `an audited model is found inside a sealed hierarchy`() {
        val found = Order.serializer().descriptor.auditedModels().keys
        assertTrue(found.any { it.endsWith("Order") }, "found $found")
    }

    @Test
    fun `field paths descend into structures and stop at anything with its own record`() {
        assertEquals(
            listOf(
                "_id",
                "name",
                "ssn",
                "address",
                "address.street",
                "address.city",
                "phones",
                "phones[].number",
                "phones[].label",
                "tags",
                "doctor",
            ),
            Patient.serializer().descriptor.auditFieldPaths(),
        )
    }

    @Test
    fun `a sealed field gets a path per subclass`() {
        val paths = Order.serializer().descriptor.auditFieldPaths()
        assertEquals(listOf("_id", "payment", "payment(Card).last4", "payment(Cash).amount"), paths)
    }

    /** A model that contains itself must not walk forever. */
    @Test
    fun `a self-referential structure terminates`() {
        val paths = Tree.serializer().descriptor.auditFieldPaths()
        assertTrue(paths.contains("root.label"), "did not descend at all; was $paths")
        assertTrue(paths.size < 20, "the walk did not converge; produced ${paths.size} paths")
    }

    @Test
    fun `an unaudited model contributes nothing`() {
        assertEquals(emptyMap(), Address.serializer().descriptor.auditedModels())
    }
}
