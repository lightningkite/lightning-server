package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.*
import com.lightningkite.services.data.toEmailAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmailTest {
    @Test
    fun `welcome email and SMS code reach the test services`() { mailTest() }

    @Test
    fun `sendBulk substitutes each personalization`() { bulkMailTest() }

    @Test
    fun `an html email gets a plain-text fallback`() {
        val email = orderConfirmation("jane@example.com".toEmailAddress())
        assertTrue("Thanks for your order" in email.plainText, email.plainText)
        assertTrue(email.html.startsWith("<!doctype html>"))
    }

    @Test
    fun `an attachment email carries its file`() {
        val email = invoiceEmail("jane@example.com".toEmailAddress(), byteArrayOf(1, 2, 3))
        assertEquals("invoice.pdf", email.attachments.single().filename)
    }
}
