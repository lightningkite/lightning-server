package com.lightningkite.lightningserver.audit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DescriptorWalkTest {

    /**
     * Only annotated properties get paths, but the walk still traverses everything — `phones` is not
     * itemised yet `phones[].number` beneath it is, and the whole `tags` subtree contributes nothing.
     */
    @Test
    fun `paths are the annotated properties, found however deeply they nest`() {
        assertEquals(
            listOf("name", "ssn", "address", "address.street", "phones[].number", "doctor"),
            Patient.serializer().descriptor.auditFieldPaths(),
        )
    }

    /** `_id` is recorded as the disclosure's `recordId`, so spending a bit on it would be redundant. */
    @Test
    fun `an unannotated id gets no bit`() {
        assertTrue("_id" !in Patient.serializer().descriptor.auditFieldPaths())
    }

    @Test
    fun `a sealed field gets a path per annotated subclass field`() {
        assertEquals(
            listOf("payment", "payment(Card).last4"),
            Order.serializer().descriptor.auditFieldPaths(),
            "Cash.amount is not annotated, so it contributes nothing",
        )
    }

    /** A model that contains itself must not walk forever. */
    @Test
    fun `a self-referential structure terminates`() {
        val paths = Tree.serializer().descriptor.auditFieldPaths()
        assertTrue(paths.contains("root.label"), "did not descend at all; was $paths")
        assertTrue(paths.size < 20, "the walk did not converge; produced ${paths.size} paths")
    }
}
