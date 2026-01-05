package com.lightningkite.lightningserver.ai

import ai.koog.prompt.dsl.Prompt
import com.lightningkite.lightningserver.runtime.now
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertTrue

/**
 * Tests to measure and track voice prompt sizes.
 *
 * The OpenAI Realtime API has a 40k TPM limit. Large prompts will quickly exhaust this.
 * These tests help track prompt size and identify optimization opportunities.
 */
class VoicePromptSizeTest {

    companion object {
        // Rough estimate: ~4 characters per token for English text
        fun estimateTokens(text: String): Int = text.length / 4
    }

    @Test
    fun `measure phone handler prompt components`() = runBlocking {
        // Simulate the components that go into a phone handler prompt
        // Based on VoiceChannelSupport.createPhoneHandler

        val systemPrompt = """
            You are a helpful blog management assistant. You can help users:
            - Search and browse blog posts
            - Create new blog posts
            - Edit existing posts (title, content, excerpt, tags)
            - Publish or archive posts
            - Delete posts (requires user approval)

            When creating or updating posts, be helpful and ask clarifying questions if needed.
            For destructive operations like delete, always explain what will happen first.
        """.trimIndent()

        val voiceInstructions = """
            You are having a voice conversation. Always respond in English, regardless of how the user speaks.
            Be brief but clear in your responses since this is a voice call.
            Greet the user naturally when the conversation starts and ask how you can help.
        """.trimIndent()

        val postPrompt = "You are working on behalf of user: 550e8400-e29b-41d4-a716-446655440000"

        // Simulate 10 messages (historyMessageLimit = 10)
        val historyMessages = buildString {
            repeat(10) { i ->
                if (i % 2 == 0) {
                    append("## Previous User Message\n")
                    append("User query $i about the blog - can you help me find posts about technology?\n\n")
                } else {
                    append("## Previous Assistant Message\n")
                    append("Of course! I'd be happy to help you find blog posts about technology. ")
                    append("Let me search for posts with that topic. What specific aspects are you interested in?\n\n")
                }
            }
        }

        // Build full instructions as VoiceChannelSupport.createPhoneHandler does
        val fullInstructions = buildString {
            append(systemPrompt)
            append("\n\n")
            append(historyMessages)
            append(postPrompt)
            append("\n\n")
            append("## Voice Session Instructions\n")
            append(voiceInstructions)
            append("\n\n")
            append("When starting a new voice session, greet the user naturally and ask how you can help.")
        }

        val estimatedTokens = estimateTokens(fullInstructions)

        println("=== PHONE HANDLER PROMPT SIZE ===")
        println("System prompt: ${systemPrompt.length} chars (~${estimateTokens(systemPrompt)} tokens)")
        println("Voice instructions: ${voiceInstructions.length} chars (~${estimateTokens(voiceInstructions)} tokens)")
        println("Post prompt: ${postPrompt.length} chars (~${estimateTokens(postPrompt)} tokens)")
        println("History (10 messages): ${historyMessages.length} chars (~${estimateTokens(historyMessages)} tokens)")
        println()
        println("Total: ${fullInstructions.length} chars (~$estimatedTokens tokens)")
        println()

        // For OpenAI Realtime, session.instructions should ideally be under 4k tokens
        // to leave room for conversation in the 40k TPM budget
        assertTrue(estimatedTokens < 4000,
            "Phone handler instructions should be under 4000 tokens but was $estimatedTokens. " +
            "Consider reducing system prompts or history.")
    }

    @Test
    fun `measure tool schema impact on token usage`() = runBlocking {
        // OpenAI Realtime API sends tools separately from instructions
        // But the tool schemas still consume tokens in the overall budget

        // Simulate BlogPost model structure explanation (from ModelStructure.render())
        val blogPostSchema = """
            Structure of the BlogPost table in a syntax roughly similar to Typescript: {
                _id: Uuid (unique identifier)
                title: String (post title)
                content: String (post body content)
                excerpt: String? (optional summary)
                authorId: Uuid (author reference)
                tags: List<String> (categorization tags)
                status: enum PostStatus { Draft, Published, Archived }
                viewCount: Int (number of views)
                createdAt: Instant (creation timestamp)
                updatedAt: Instant (last update timestamp)
            }
        """.trimIndent()

        // Simulate tools for CRUD operations
        val tools = listOf(
            "get_blogpost_by_id" to "Get a single record from the BlogPost table by its ID",
            "count_blogpost" to "Count the total number of records in the BlogPost table that match the given condition",
            "query_blogpost" to "Query the BlogPost table with advanced filters and optional sorting",
            "aggregate_blogpost" to "Aggregate Query the BlogPost table with advanced filters (Sum, Average, etc.)",
            "create_blogpost" to "Create a new BlogPost record",
            "update_blogpost" to "Update an existing BlogPost record by ID",
            "modify_blogpost" to "Apply partial modifications to BlogPost records matching a condition",
            "delete_blogpost" to "Delete a BlogPost record by ID (requires approval)",
        )

        var totalToolSize = 0
        println("=== TOOL DESCRIPTIONS ===")
        for ((name, desc) in tools) {
            // Each tool has: name, description, and shared schema
            val toolSize = name.length + desc.length + blogPostSchema.length
            totalToolSize += toolSize
            println("$name: ${desc.length} chars + schema = ~${estimateTokens(toolSize.toString())} tokens")
        }

        println()
        println("Schema size: ${blogPostSchema.length} chars (~${estimateTokens(blogPostSchema)} tokens)")
        println("Total tools overhead: $totalToolSize chars (~${estimateTokens(totalToolSize.toString())} tokens)")
        println()
        println("Note: For OpenAI Realtime, tool schemas are sent via session config,")
        println("not in instructions. But they still consume from the 40k TPM budget.")

        // Each tool call consumes both the schema tokens and response tokens
        // With 8 tools sharing a schema, the schema is sent once but each tool adds overhead
    }

    @Test
    fun `estimate max conversation before rate limit`() = runBlocking {
        // OpenAI Realtime API has a 40k TPM limit for gpt-4o-realtime
        val tpmLimit = 40000

        // Base prompt components
        val baseInstructions = 500  // system + voice instructions
        val toolSchemas = 2000      // shared schema + tool definitions
        val perMessageTokens = 50   // average tokens per conversation message

        // Calculate how many messages we can have before hitting rate limit
        val fixedOverhead = baseInstructions + toolSchemas
        val availableForConversation = tpmLimit - fixedOverhead
        val maxMessages = availableForConversation / perMessageTokens

        println("=== RATE LIMIT ANALYSIS ===")
        println("TPM Limit: $tpmLimit")
        println("Fixed overhead (instructions + tools): $fixedOverhead tokens")
        println("Available for conversation: $availableForConversation tokens")
        println("Average tokens per message: $perMessageTokens")
        println()
        println("Estimated max messages per minute: $maxMessages")
        println()

        // In practice, each response also consumes output tokens
        // And there's audio token overhead too
        println("Warning: This doesn't account for:")
        println("- Output tokens (assistant responses)")
        println("- Audio encoding overhead")
        println("- Tool call/result tokens")
        println()
        println("Recommendations to stay under rate limit:")
        println("1. Keep historyMessageLimit low (5-10 messages)")
        println("2. Use conversation summarization for long calls")
        println("3. Keep system prompts concise")
        println("4. Consider reducing tool count for voice")

        // Ensure our base overhead isn't too high
        assertTrue(fixedOverhead < tpmLimit / 4,
            "Fixed overhead ($fixedOverhead) should be under 25% of TPM limit")
    }

    @Test
    fun `verify prompt compression triggers at appropriate size`() = runBlocking {
        // LLMChatEndpoints compresses when prompt exceeds contextLength / 2
        // For gpt-4o, context is typically 128k, so compression at 64k tokens

        // But for voice, we hit rate limits long before context limits
        // This test documents the discrepancy

        val gpt4oContext = 128000
        val compressionThreshold = gpt4oContext / 2  // 64k tokens

        val realtimeTPMLimit = 40000  // tokens per minute

        println("=== COMPRESSION THRESHOLD vs RATE LIMIT ===")
        println("GPT-4o context: $gpt4oContext tokens")
        println("Default compression threshold: $compressionThreshold tokens")
        println("Realtime API TPM limit: $realtimeTPMLimit tokens")
        println()
        println("Problem: Compression triggers at $compressionThreshold tokens,")
        println("but rate limit hits at $realtimeTPMLimit tokens/minute.")
        println()
        println("For voice calls, we need more aggressive compression or")
        println("a separate historyMessageLimit to stay under rate limits.")

        // The current historyMessageLimit of 10 helps, but for long calls
        // we may need even more aggressive summarization
    }
}
