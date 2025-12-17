package com.lightningkite.lightningserver.ai

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for voice approval/disapproval word detection logic.
 * These tests verify that the verbal confirmation system correctly identifies
 * approval and disapproval responses with tolerance for transcription errors.
 */
class VoiceApprovalTest {

    /**
     * Helper function to simulate the approval detection logic from VoiceChannelSupport
     */
    private fun detectApproval(content: String?): Boolean {
        return content?.lowercase()?.replace("please", "")?.replace("thank you", "")?.let { processed ->
            val justLetters = processed.filter { it.isLetter() }
            val validYes = setOf("yes", "yeah", "yep")
            justLetters in validYes
        } ?: false
    }

    /**
     * Helper function to simulate the disapproval detection logic from VoiceChannelSupport
     */
    private fun detectDisapproval(content: String?): Boolean {
        return content?.lowercase()?.replace("please", "")?.replace("thank you", "")?.let { processed ->
            val justLetters = processed.filter { it.isLetter() }
            val validNo = setOf("no", "nope", "nah", "dont", "dontdoit")
            justLetters in validNo
        } ?: false
    }

    // ==================== APPROVAL TESTS ====================

    @Test
    fun `detects simple yes`() {
        assertTrue(detectApproval("yes"))
        assertTrue(detectApproval("Yes"))
        assertTrue(detectApproval("YES"))
    }

    @Test
    fun `detects yes with punctuation`() {
        assertTrue(detectApproval("yes!"))
        assertTrue(detectApproval("yes."))
        assertTrue(detectApproval("yes?"))
        assertTrue(detectApproval("Yes!"))
    }

    @Test
    fun `detects yes with polite words`() {
        assertTrue(detectApproval("yes please"))
        assertTrue(detectApproval("yes thank you"))
        assertTrue(detectApproval("please yes"))
        assertTrue(detectApproval("thank you yes"))
    }

    @Test
    fun `detects yes variants`() {
        assertTrue(detectApproval("yeah"))
        assertTrue(detectApproval("yep"))
        assertTrue(detectApproval("Yeah!"))
        assertTrue(detectApproval("Yep."))
    }

    @Test
    fun `rejects yes with additional words`() {
        assertFalse(detectApproval("yes I agree"))
        assertFalse(detectApproval("okay yes"))
        assertFalse(detectApproval("yes absolutely"))
        assertFalse(detectApproval("yes do it"))
    }

    @Test
    fun `rejects similar but not exact words`() {
        assertFalse(detectApproval("sure"))
        assertFalse(detectApproval("okay"))
        assertFalse(detectApproval("alright"))
        assertFalse(detectApproval("yep go ahead"))
        assertFalse(detectApproval("yeah sure"))
    }

    @Test
    fun `handles null and empty strings for approval`() {
        assertFalse(detectApproval(null))
        assertFalse(detectApproval(""))
        assertFalse(detectApproval("   "))
    }

    // ==================== DISAPPROVAL TESTS ====================

    @Test
    fun `detects simple no`() {
        assertTrue(detectDisapproval("no"))
        assertTrue(detectDisapproval("No"))
        assertTrue(detectDisapproval("NO"))
    }

    @Test
    fun `detects no with punctuation`() {
        assertTrue(detectDisapproval("no!"))
        assertTrue(detectDisapproval("no."))
        assertTrue(detectDisapproval("No!"))
    }

    @Test
    fun `detects no with polite words`() {
        assertTrue(detectDisapproval("no please"))
        assertTrue(detectDisapproval("no thank you"))
        assertTrue(detectDisapproval("please no"))
    }

    @Test
    fun `detects no variants`() {
        assertTrue(detectDisapproval("nope"))
        assertTrue(detectDisapproval("nah"))
        assertTrue(detectDisapproval("dont"))
        assertTrue(detectDisapproval("don't"))  // Apostrophe is removed, becomes "dont"
        assertTrue(detectDisapproval("don't do it"))  // Becomes "dontdoit"
    }

    @Test
    fun `rejects no with additional words`() {
        assertFalse(detectDisapproval("no way"))
        assertFalse(detectDisapproval("not now"))
        assertFalse(detectDisapproval("no I disagree"))
        assertFalse(detectDisapproval("absolutely no"))
    }

    @Test
    fun `handles null and empty strings for disapproval`() {
        assertFalse(detectDisapproval(null))
        assertFalse(detectDisapproval(""))
        assertFalse(detectDisapproval("   "))
    }

    // ==================== MUTUAL EXCLUSIVITY TESTS ====================

    @Test
    fun `approval and disapproval are mutually exclusive`() {
        // These should be detected as one or the other, never both
        val testCases = listOf(
            "yes", "no", "yeah", "nope", "yep", "nah"
        )

        testCases.forEach { message ->
            val isApproval = detectApproval(message)
            val isDisapproval = detectDisapproval(message)

            // Exactly one should be true (XOR)
            assertTrue(
                isApproval xor isDisapproval,
                "Message '$message' should be detected as either approval or disapproval, not both or neither. " +
                "approval=$isApproval, disapproval=$isDisapproval"
            )
        }
    }

    @Test
    fun `neither approval nor disapproval for ambiguous messages`() {
        val ambiguousMessages = listOf(
            "maybe",
            "I'm not sure",
            "let me think about it",
            "okay",
            "sure",
            "alright"
        )

        ambiguousMessages.forEach { message ->
            assertFalse(detectApproval(message), "Should not detect '$message' as approval")
            assertFalse(detectDisapproval(message), "Should not detect '$message' as disapproval")
        }
    }

    // ==================== TRANSCRIPTION ERROR TOLERANCE TESTS ====================

    @Test
    fun `handles transcription with extra spaces`() {
        assertTrue(detectApproval("y e s"))  // Spaces removed, becomes "yes"
        assertTrue(detectDisapproval("n o"))  // Spaces removed, becomes "no"
    }

    @Test
    fun `handles mixed case transcription`() {
        assertTrue(detectApproval("YeS"))
        assertTrue(detectApproval("yEs"))
        assertTrue(detectDisapproval("No"))
        assertTrue(detectDisapproval("nOpE"))
    }

    @Test
    fun `polite words are properly stripped`() {
        // These should work because "please" and "thank you" are removed
        assertTrue(detectApproval("yes please thank you"))
        assertTrue(detectApproval("please thank you yes"))
        assertTrue(detectDisapproval("no please"))
        assertTrue(detectDisapproval("no thank you"))
    }
}
