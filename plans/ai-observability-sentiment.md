# Plan: AI Agent Observability & Sentiment Detection

## Overview

Add OpenTelemetry-based observability for AI chat agents, including conversation quality metrics and sentiment detection for proactive escalation. This builds on the existing OTel infrastructure in Lightning Server.

## Context

- **Use Case**: Customer support chatbots need to measure effectiveness and catch frustrated customers
- **Existing Infrastructure**: Lightning Server has OTel support via `serviceAbstractionsOtelJvm`
- **Related Work**: HTTP metrics already implemented in `HttpMetrics.kt`
- **AI Semantic Conventions**: Use emerging [OpenTelemetry GenAI conventions](https://opentelemetry.io/blog/2025/ai-agent-observability/)

## Goals

1. Track conversation quality metrics (resolution rate, satisfaction, escalations)
2. Detect customer sentiment and proactively offer human escalation
3. Enable analysis of agent performance and common issues
4. Follow OpenTelemetry semantic conventions for AI/LLM systems

---

## Part 1: Conversation Quality Metrics

### Data Model Changes

**File**: `ai-shared/src/main/kotlin/com/lightningkite/lightningserver/ai/SystemChatModels.kt`

Add fields to track conversation outcomes:

```kotlin
@Serializable
@GenerateDataClassPaths
@IndexSet(["subjectId", "createdAt"])
public data class SystemChatConversation(
    // ...existing fields...

    /**
     * Whether the customer's issue was successfully resolved.
     * Set by calling markResolved() or via end-of-conversation survey.
     */
    val issueResolved: Boolean? = null,

    /**
     * Customer satisfaction score (1-5).
     * Typically collected at end of conversation.
     */
    val customerSatisfaction: Int? = null,

    /**
     * Primary issue category for analytics.
     * Examples: "password_reset", "billing_question", "technical_support"
     */
    val issueCategory: String? = null,

    /**
     * Whether human escalation was offered to the customer.
     */
    val humanEscalationOffered: Boolean = false,

    /**
     * Sentiment analysis data for the conversation.
     */
    val sentiment: ConversationSentiment? = null,
)

/**
 * Tracks sentiment signals throughout a conversation.
 */
@Serializable
@GenerateDataClassPaths
public data class ConversationSentiment(
    /**
     * Current frustration level (0-10).
     * Updated as conversation progresses.
     */
    val frustrationLevel: Int = 0,

    /**
     * Count of positive sentiment signals.
     * Examples: "thanks", "helpful", "perfect"
     */
    val positiveSignals: Int = 0,

    /**
     * Count of negative sentiment signals.
     * Examples: "frustrated", "not working", "useless"
     */
    val negativeSignals: Int = 0,

    /**
     * Timestamp of last sentiment update.
     */
    val lastUpdated: Instant,
)
```

### OpenTelemetry Metrics

**File**: `ai/src/main/kotlin/com/lightningkite/lightningserver/ai/telemetry/AiAgentMetrics.kt` (new)

```kotlin
package com.lightningkite.lightningserver.ai.telemetry

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.*

/**
 * OpenTelemetry metrics for AI agent conversations.
 *
 * Follows OpenTelemetry semantic conventions for GenAI systems:
 * https://opentelemetry.io/blog/2025/ai-agent-observability/
 */
public class AiAgentMetrics(meter: Meter) {

    // ===== Conversation Metrics =====

    /**
     * Total conversations started.
     * Attributes: gen_ai.agent.name, channel (sms, email, voice, web)
     */
    public val conversationsStarted: LongCounter = meter.counterBuilder("gen_ai.agent.conversation.started")
        .setDescription("Total AI agent conversations started")
        .setUnit("{conversation}")
        .build()

    /**
     * Conversations completed (resolved or abandoned).
     * Attributes: gen_ai.agent.name, resolution_status (resolved, unresolved, escalated, abandoned)
     */
    public val conversationsCompleted: LongCounter = meter.counterBuilder("gen_ai.agent.conversation.completed")
        .setDescription("AI agent conversations completed")
        .setUnit("{conversation}")
        .build()

    /**
     * Conversation duration in seconds.
     * Attributes: gen_ai.agent.name, resolution_status
     */
    public val conversationDuration: LongHistogram = meter.histogramBuilder("gen_ai.agent.conversation.duration")
        .setDescription("Duration of AI agent conversations in seconds")
        .setUnit("s")
        .ofLongs()
        .build()

    /**
     * Customer satisfaction scores (1-5).
     * Attributes: gen_ai.agent.name, issue_category
     */
    public val customerSatisfaction: LongHistogram = meter.histogramBuilder("gen_ai.agent.satisfaction.score")
        .setDescription("Customer satisfaction scores for AI agent conversations")
        .setUnit("{score}")
        .ofLongs()
        .build()

    // ===== Tool/Action Metrics =====

    /**
     * Total tool calls made by the agent.
     * Attributes: gen_ai.agent.name, gen_ai.tool.name, gen_ai.action.status (success, error, requires_approval)
     */
    public val toolCalls: LongCounter = meter.counterBuilder("gen_ai.agent.tool.calls")
        .setDescription("Tool calls made by AI agent")
        .setUnit("{call}")
        .build()

    /**
     * Tool execution duration.
     * Attributes: gen_ai.agent.name, gen_ai.tool.name
     */
    public val toolDuration: LongHistogram = meter.histogramBuilder("gen_ai.agent.tool.duration")
        .setDescription("Duration of tool execution in milliseconds")
        .setUnit("ms")
        .ofLongs()
        .build()

    /**
     * Tool approval decisions.
     * Attributes: gen_ai.agent.name, gen_ai.tool.name, approval_decision (approved, rejected)
     */
    public val toolApprovals: LongCounter = meter.counterBuilder("gen_ai.agent.tool.approvals")
        .setDescription("Tool approval decisions")
        .setUnit("{decision}")
        .build()

    // ===== LLM Metrics =====

    /**
     * LLM requests made by the agent.
     * Attributes: gen_ai.agent.name, gen_ai.operation.name (respond, summarize, analyze_sentiment)
     */
    public val llmRequests: LongCounter = meter.counterBuilder("gen_ai.agent.llm.requests")
        .setDescription("LLM requests made by AI agent")
        .setUnit("{request}")
        .build()

    /**
     * Input tokens consumed.
     * Attributes: gen_ai.agent.name, gen_ai.operation.name, gen_ai.system (koog, openai, etc.)
     */
    public val llmInputTokens: LongCounter = meter.counterBuilder("gen_ai.agent.llm.input_tokens")
        .setDescription("LLM input tokens consumed")
        .setUnit("{token}")
        .build()

    /**
     * Output tokens generated.
     * Attributes: gen_ai.agent.name, gen_ai.operation.name, gen_ai.system
     */
    public val llmOutputTokens: LongCounter = meter.counterBuilder("gen_ai.agent.llm.output_tokens")
        .setDescription("LLM output tokens generated")
        .setUnit("{token}")
        .build()

    /**
     * LLM request duration.
     * Attributes: gen_ai.agent.name, gen_ai.operation.name
     */
    public val llmDuration: LongHistogram = meter.histogramBuilder("gen_ai.agent.llm.duration")
        .setDescription("Duration of LLM requests in milliseconds")
        .setUnit("ms")
        .ofLongs()
        .build()

    // ===== Sentiment & Escalation Metrics =====

    /**
     * Current frustration level distribution.
     * Attributes: gen_ai.agent.name, frustration_level (0-10)
     */
    public val frustrationLevel: LongHistogram = meter.histogramBuilder("gen_ai.agent.sentiment.frustration")
        .setDescription("Customer frustration level distribution")
        .setUnit("{level}")
        .ofLongs()
        .build()

    /**
     * Escalations to human agents.
     * Attributes: gen_ai.agent.name, escalation_reason (customer_request, high_frustration, repeated_failures, etc.)
     */
    public val escalations: LongCounter = meter.counterBuilder("gen_ai.agent.escalation.count")
        .setDescription("Escalations to human agents")
        .setUnit("{escalation}")
        .build()

    /**
     * Time spent before escalation.
     * Attributes: gen_ai.agent.name, escalation_reason
     */
    public val timeToEscalation: LongHistogram = meter.histogramBuilder("gen_ai.agent.escalation.time")
        .setDescription("Time from conversation start to escalation in seconds")
        .setUnit("s")
        .ofLongs()
        .build()

    // ===== Helper Methods =====

    /**
     * Records conversation start.
     */
    public fun recordConversationStart(
        agentName: String,
        channel: String?
    ) {
        val attrs = Attributes.builder()
            .put(AGENT_NAME, agentName)
            .apply { if (channel != null) put(CHANNEL, channel) }
            .build()
        conversationsStarted.add(1, attrs)
    }

    /**
     * Records conversation completion with outcome.
     */
    public fun recordConversationComplete(
        agentName: String,
        durationSeconds: Long,
        resolved: Boolean?,
        escalated: Boolean,
        abandoned: Boolean = false,
        satisfactionScore: Int? = null,
        issueCategory: String? = null
    ) {
        val resolutionStatus = when {
            abandoned -> "abandoned"
            escalated -> "escalated"
            resolved == true -> "resolved"
            resolved == false -> "unresolved"
            else -> "unknown"
        }

        val attrs = Attributes.builder()
            .put(AGENT_NAME, agentName)
            .put(RESOLUTION_STATUS, resolutionStatus)
            .apply { if (issueCategory != null) put(ISSUE_CATEGORY, issueCategory) }
            .build()

        conversationsCompleted.add(1, attrs)
        conversationDuration.record(durationSeconds, attrs)

        if (satisfactionScore != null) {
            val satisfactionAttrs = Attributes.builder()
                .put(AGENT_NAME, agentName)
                .apply { if (issueCategory != null) put(ISSUE_CATEGORY, issueCategory) }
                .build()
            customerSatisfaction.record(satisfactionScore.toLong(), satisfactionAttrs)
        }
    }

    /**
     * Records a tool call.
     */
    public fun recordToolCall(
        agentName: String,
        toolName: String,
        durationMs: Long,
        status: ToolCallStatus,
        requiresApproval: Boolean = false
    ) {
        val statusStr = when {
            requiresApproval -> "requires_approval"
            status == ToolCallStatus.SUCCESS -> "success"
            else -> "error"
        }

        val attrs = Attributes.of(
            AGENT_NAME, agentName,
            TOOL_NAME, toolName,
            ACTION_STATUS, statusStr
        )

        toolCalls.add(1, attrs)

        if (status != ToolCallStatus.PENDING_APPROVAL) {
            val durationAttrs = Attributes.of(
                AGENT_NAME, agentName,
                TOOL_NAME, toolName
            )
            toolDuration.record(durationMs, durationAttrs)
        }
    }

    /**
     * Records tool approval decision.
     */
    public fun recordToolApproval(
        agentName: String,
        toolName: String,
        approved: Boolean
    ) {
        val attrs = Attributes.of(
            AGENT_NAME, agentName,
            TOOL_NAME, toolName,
            APPROVAL_DECISION, if (approved) "approved" else "rejected"
        )
        toolApprovals.add(1, attrs)
    }

    /**
     * Records LLM request.
     */
    public fun recordLlmRequest(
        agentName: String,
        operation: String, // "respond", "summarize", "analyze_sentiment"
        systemName: String, // "koog", "openai"
        inputTokens: Long,
        outputTokens: Long,
        durationMs: Long
    ) {
        val attrs = Attributes.of(
            AGENT_NAME, agentName,
            OPERATION_NAME, operation,
            SYSTEM, systemName
        )

        llmRequests.add(1, attrs)
        llmInputTokens.add(inputTokens, attrs)
        llmOutputTokens.add(outputTokens, attrs)
        llmDuration.record(durationMs, attrs)
    }

    /**
     * Records sentiment update.
     */
    public fun recordSentimentUpdate(
        agentName: String,
        frustrationLevel: Int
    ) {
        val attrs = Attributes.of(
            AGENT_NAME, agentName,
            FRUSTRATION_LEVEL, frustrationLevel.toString()
        )
        this.frustrationLevel.record(frustrationLevel.toLong(), attrs)
    }

    /**
     * Records escalation to human.
     */
    public fun recordEscalation(
        agentName: String,
        reason: String, // "customer_request", "high_frustration", "repeated_failures"
        timeToEscalationSeconds: Long
    ) {
        val attrs = Attributes.of(
            AGENT_NAME, agentName,
            ESCALATION_REASON, reason
        )

        escalations.add(1, attrs)
        timeToEscalation.record(timeToEscalationSeconds, attrs)
    }

    public companion object {
        // Attribute keys following OpenTelemetry semantic conventions
        public val AGENT_NAME: AttributeKey<String> = AttributeKey.stringKey("gen_ai.agent.name")
        public val CHANNEL: AttributeKey<String> = AttributeKey.stringKey("channel")
        public val RESOLUTION_STATUS: AttributeKey<String> = AttributeKey.stringKey("resolution_status")
        public val ISSUE_CATEGORY: AttributeKey<String> = AttributeKey.stringKey("issue_category")
        public val TOOL_NAME: AttributeKey<String> = AttributeKey.stringKey("gen_ai.tool.name")
        public val ACTION_STATUS: AttributeKey<String> = AttributeKey.stringKey("gen_ai.action.status")
        public val APPROVAL_DECISION: AttributeKey<String> = AttributeKey.stringKey("approval_decision")
        public val OPERATION_NAME: AttributeKey<String> = AttributeKey.stringKey("gen_ai.operation.name")
        public val SYSTEM: AttributeKey<String> = AttributeKey.stringKey("gen_ai.system")
        public val FRUSTRATION_LEVEL: AttributeKey<String> = AttributeKey.stringKey("frustration_level")
        public val ESCALATION_REASON: AttributeKey<String> = AttributeKey.stringKey("escalation_reason")
    }
}

public enum class ToolCallStatus {
    SUCCESS,
    ERROR,
    PENDING_APPROVAL
}
```

---

## Part 2: Sentiment Detection

### Sentiment Analysis Interface (Pluggable)

**File**: `ai/src/main/kotlin/com/lightningkite/lightningserver/ai/SentimentAnalyzer.kt` (new)

```kotlin
package com.lightningkite.lightningserver.ai

import com.lightningkite.lightningserver.runtime.ServerRuntime
import kotlinx.serialization.Serializable

/**
 * Pluggable interface for sentiment analysis.
 * Implementations can use keyword matching, LLM-based analysis, or external services.
 */
public interface SentimentAnalyzer {
    /**
     * Analyzes recent messages for sentiment.
     * Returns updated sentiment analysis result.
     */
    context(ServerRuntime)
    public suspend fun analyzeSentiment(
        messages: List<SystemChatMessage>,
        currentSentiment: ConversationSentiment?
    ): SentimentAnalysisResult
}

@Serializable
public data class SentimentAnalysisResult(
    val frustrationLevel: Int,
    val positiveSignals: Int,
    val negativeSignals: Int,
    val shouldEscalate: Boolean,
    val reason: String? = null
)
```

### Default Implementation: Keyword-Based Sentiment Analysis

**File**: `ai/src/main/kotlin/com/lightningkite/lightningserver/ai/KeywordSentimentAnalyzer.kt` (new)

```kotlin
package com.lightningkite.lightningserver.ai

import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.ai.koog.LLMClientAndModel
import kotlinx.serialization.Serializable

/**
 * Default sentiment analyzer using keyword matching with optional LLM fallback.
 * Fast and cost-effective for most use cases.
 *
 * @param llmProvider Optional LLM provider for deep analysis when needed
 * @param frustrationThreshold Frustration level (0-10) that triggers escalation (default: 6)
 */
public class KeywordSentimentAnalyzer(
    private val llmProvider: (() -> LLMClientAndModel)? = null,
    private val frustrationThreshold: Int = 6
) : SentimentAnalyzer {

    /**
     * Analyzes recent messages for sentiment.
     * Returns updated ConversationSentiment.
     */
    context(ServerRuntime)
    public suspend fun analyzeSentiment(
        messages: List<SystemChatMessage>,
        currentSentiment: ConversationSentiment?
    ): SentimentAnalysisResult {
        // Use simple keyword matching first (fast, no LLM cost)
        val quickAnalysis = quickSentimentCheck(messages)

        // If quick analysis shows potential issues, use LLM for deeper analysis
        return if (quickAnalysis.needsDeepAnalysis) {
            deepSentimentAnalysis(messages)
        } else {
            quickAnalysis
        }
    }

    /**
     * Fast keyword-based sentiment detection.
     */
    private fun quickSentimentCheck(messages: List<SystemChatMessage>): SentimentAnalysisResult {
        val recentUserMessages = messages
            .filter { it.role == SystemChatMessage.Role.User }
            .takeLast(3)
            .map { it.content.lowercase() }

        var positiveSignals = 0
        var negativeSignals = 0

        for (message in recentUserMessages) {
            // Positive signals
            if (POSITIVE_KEYWORDS.any { message.contains(it) }) positiveSignals++

            // Negative signals
            if (NEGATIVE_KEYWORDS.any { message.contains(it) }) negativeSignals++

            // Strong frustration signals
            if (FRUSTRATION_KEYWORDS.any { message.contains(it) }) {
                negativeSignals += 2 // Weight frustration more heavily
            }
        }

        val frustrationLevel = (negativeSignals * 2).coerceIn(0, 10)

        return SentimentAnalysisResult(
            frustrationLevel = frustrationLevel,
            positiveSignals = positiveSignals,
            negativeSignals = negativeSignals,
            shouldEscalate = frustrationLevel >= frustrationThreshold
        )
    }

    /**
     * LLM-based deep sentiment analysis.
     */
    context(ServerRuntime)
    private suspend fun deepSentimentAnalysis(messages: List<SystemChatMessage>): SentimentAnalysisResult {
        val recentConversation = messages.takeLast(5).joinToString("\n") { msg ->
            "${msg.role}: ${msg.content}"
        }

        val prompt = """
        Analyze the customer's sentiment in this support conversation.

        Conversation:
        $recentConversation

        Provide a JSON response with:
        {
          "frustrationLevel": 0-10,
          "positiveSignals": count,
          "negativeSignals": count,
          "shouldEscalate": boolean,
          "reason": "brief explanation"
        }
        """.trimIndent()

        val llm = llmProvider()
        // Call LLM and parse response...
        // (Implementation details omitted for brevity)

        // For now, fall back to quick analysis
        return quickSentimentCheck(messages)
    }

    private companion object {
        val POSITIVE_KEYWORDS = setOf(
            "thank", "thanks", "helpful", "perfect", "great", "appreciate",
            "solved", "worked", "excellent", "awesome"
        )

        val NEGATIVE_KEYWORDS = setOf(
            "not working", "doesn't work", "broken", "error", "problem",
            "issue", "wrong", "bad", "terrible", "poor"
        )

        val FRUSTRATION_KEYWORDS = setOf(
            "frustrated", "annoyed", "angry", "ridiculous", "useless",
            "waste of time", "give up", "human", "real person", "agent",
            "speak to someone", "talk to someone"
        )
    }
}

```

---

## Part 3: Utility-Based Composition (No Inheritance)

### Design Philosophy

Instead of extending classes, provide **utility objects** that `LLMChatEndpoints` implementations can use via delegation. Each utility is self-contained and can be mixed-and-matched.

### Utility 1: Sentiment Detection Helper

**File**: `ai/src/main/kotlin/com/lightningkite/lightningserver/ai/ConversationSentimentDetection.kt` (new)

```kotlin
package com.lightningkite.lightningserver.ai

import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.typed.AuthAccess
import com.lightningkite.services.database.*
import kotlinx.serialization.Serializable

/**
 * Utility for adding sentiment detection to any LLMChatEndpoints.
 * Use by creating an instance and calling checkAndHandle() in your respond() method.
 *
 * Example:
 * ```kotlin
 * class BlogAssistantChat : LLMChatEndpoints<User>(...) {
 *     private val sentimentDetection = ConversationSentimentDetection(
 *         analyzer = KeywordSentimentAnalyzer(frustrationThreshold = 6),
 *         onEscalation = { auth, conv, result ->
 *             // Custom escalation behavior
 *             messageInfo.table().insertOne(...)
 *         }
 *     )
 *
 *     override suspend fun respond(auth: AuthAccess<User>, conversation: SystemChatConversation) {
 *         // Check sentiment first - returns true if escalated
 *         if (sentimentDetection.checkAndHandle(this, auth, conversation)) return
 *
 *         // Continue with normal response
 *         super.respond(auth, conversation)
 *     }
 * }
 * ```
 */
public class ConversationSentimentDetection<Subject : HasId<*>>(
    private val analyzer: SentimentAnalyzer,
    private val onEscalation: (suspend context(ServerRuntime) (
        auth: AuthAccess<Subject>,
        conversation: SystemChatConversation,
        result: SentimentAnalysisResult
    ) -> Unit)? = null
) {

    /**
     * Checks sentiment and handles escalation if needed.
     *
     * @return true if conversation was escalated (caller should stop processing)
     */
    context(ServerRuntime)
    public suspend fun checkAndHandle(
        endpoints: LLMChatEndpoints<Subject>,
        auth: AuthAccess<Subject>,
        conversation: SystemChatConversation
    ): Boolean {
        // Analyze sentiment
        val messages = endpoints.messagesForPrompt(conversation)
        val result = analyzer.analyzeSentiment(messages, conversation.sentiment)

        // Update conversation sentiment
        endpoints.conversations.info.table().updateOneById(
            conversation._id,
            modification {
                it.sentiment assign ConversationSentiment(
                    frustrationLevel = result.frustrationLevel,
                    positiveSignals = result.positiveSignals,
                    negativeSignals = result.negativeSignals,
                    lastUpdated = now()
                )
            }
        )

        // Check if escalation needed (only once)
        if (result.shouldEscalate && !conversation.humanEscalationOffered) {
            if (onEscalation != null) {
                // Custom escalation handler
                onEscalation(auth, conversation, result)
            } else {
                // Default: offer help message
                defaultEscalationMessage(endpoints, auth, conversation)
            }
            return true  // Signal to stop processing
        }

        return false
    }

    context(ServerRuntime)
    private suspend fun defaultEscalationMessage(
        endpoints: LLMChatEndpoints<Subject>,
        auth: AuthAccess<Subject>,
        conversation: SystemChatConversation
    ) {
        // Mark escalation offered
        endpoints.conversations.info.table().updateOneById(
            conversation._id,
            modification { it.humanEscalationOffered assign true }
        )

        // Insert message
        endpoints.messageInfo.table().insertOne(
            SystemChatMessage(
                conversationId = conversation._id,
                subjectId = conversation.subjectId,
                role = SystemChatMessage.Role.Assistant,
                content = "I sense this might be frustrating. Would you like me to connect you with a human agent who can help?",
                createdAt = now()
            )
        )
    }
}
```

### Utility 2: Agent Metrics Tracker

**File**: `ai/src/main/kotlin/com/lightningkite/lightningserver/ai/ConversationMetricsTracker.kt` (new)

```kotlin
package com.lightningkite.lightningserver.ai

import com.lightningkite.lightningserver.ai.telemetry.AiAgentMetrics
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.AuthAccess
import com.lightningkite.services.database.HasId

/**
 * Utility for adding OpenTelemetry metrics to any LLMChatEndpoints.
 * Use by creating an instance and calling track methods.
 *
 * Example:
 * ```kotlin
 * class BlogAssistantChat : LLMChatEndpoints<User>(...) {
 *     private val metrics = ConversationMetricsTracker<User>(
 *         agentName = "blog_assistant"
 *     )
 *
 *     override suspend fun respond(auth: AuthAccess<User>, conversation: SystemChatConversation) {
 *         metrics.trackResponse(this) {
 *             super.respond(auth, conversation)
 *         }
 *     }
 * }
 * ```
 */
public class ConversationMetricsTracker<Subject : HasId<*>>(
    private val agentName: String
) {

    /**
     * Tracks a response operation with timing and sentiment.
     */
    context(ServerRuntime)
    public suspend fun trackResponse(
        endpoints: LLMChatEndpoints<Subject>,
        conversation: SystemChatConversation,
        block: suspend () -> Unit
    ) {
        val metrics = getMetrics() ?: run {
            // No metrics configured, just run the block
            block()
            return
        }

        val startTime = System.currentTimeMillis()

        try {
            block()
        } finally {
            val durationMs = System.currentTimeMillis() - startTime

            // Record response metrics
            metrics.recordLlmRequest(
                agentName = agentName,
                operation = "respond",
                systemName = "koog", // TODO: extract from endpoints.defaultLlm
                inputTokens = 0, // TODO: extract from LLM response
                outputTokens = 0, // TODO: extract from LLM response
                durationMs = durationMs
            )

            // Record sentiment if available
            conversation.sentiment?.let {
                metrics.recordSentimentUpdate(
                    agentName = agentName,
                    frustrationLevel = it.frustrationLevel
                )
            }
        }
    }

    /**
     * Tracks a tool call.
     */
    context(ServerRuntime)
    public suspend fun trackToolCall(
        toolName: String,
        block: suspend () -> ToolCallResult
    ): ToolCallResult {
        val metrics = getMetrics() ?: return block()

        val startTime = System.currentTimeMillis()
        val result = block()
        val durationMs = System.currentTimeMillis() - startTime

        val status = when (result) {
            is ToolCallResult.Executed -> ToolCallStatus.SUCCESS
            is ToolCallResult.Error -> ToolCallStatus.ERROR
            is ToolCallResult.WaitingForApproval -> ToolCallStatus.PENDING_APPROVAL
        }

        metrics.recordToolCall(
            agentName = agentName,
            toolName = toolName,
            durationMs = if (status == ToolCallStatus.PENDING_APPROVAL) 0 else durationMs,
            status = status,
            requiresApproval = status == ToolCallStatus.PENDING_APPROVAL
        )

        return result
    }

    /**
     * Tracks conversation completion.
     */
    context(ServerRuntime)
    public fun trackCompletion(
        conversation: SystemChatConversation,
        durationSeconds: Long
    ) {
        val metrics = getMetrics() ?: return

        metrics.recordConversationComplete(
            agentName = agentName,
            durationSeconds = durationSeconds,
            resolved = conversation.issueResolved,
            escalated = conversation.activeTransfer != null,
            satisfactionScore = conversation.customerSatisfaction,
            issueCategory = conversation.issueCategory
        )
    }

    context(ServerRuntime)
    private fun getMetrics(): AiAgentMetrics? {
        // TODO: Get from ServerRuntime's OpenTelemetry
        // return AiAgentMetrics(serverRuntime.openTelemetry?.getMeter(...))
        return null
    }
}
```

### Utility 3: Conversation Lifecycle Endpoints

**File**: `ai/src/main/kotlin/com/lightningkite/lightningserver/ai/ConversationLifecycleEndpoints.kt` (new)

```kotlin
package com.lightningkite.lightningserver.ai

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.database.*
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * Reusable endpoints for managing conversation lifecycle (resolution, satisfaction).
 * Include this in your LLMChatEndpoints to add lifecycle tracking.
 *
 * Example:
 * ```kotlin
 * class BlogAssistantChat : LLMChatEndpoints<User>(...) {
 *     val lifecycle = ConversationLifecycleEndpoints(
 *         conversations = conversations,
 *         authRequirement = Server.UserAuth.require(),
 *         metricsTracker = metrics  // Optional
 *     )
 *
 *     init {
 *         path.path("lifecycle").include(lifecycle)
 *     }
 * }
 * ```
 */
public class ConversationLifecycleEndpoints<Subject : HasId<*>>(
    private val conversations: ModelInfo<Subject, SystemChatConversation, Uuid>,
    private val authRequirement: AuthRequirement<Subject>,
    private val metricsTracker: ConversationMetricsTracker<Subject>? = null
) : ServerBuilder() {

    /**
     * Mark conversation as resolved.
     * POST /conversations/{id}/resolve
     */
    public val markResolved = path.path("conversations").arg<Uuid>("id").path("resolve").post.api(
        summary = "Mark conversation as resolved",
        authOptions = authOptions(authRequirement),
        implementation = { input: MarkResolvedInput ->
            val conversation = conversations.table(this).get(input.conversationId)
                ?: throw NotFoundException("Conversation not found")

            // Update conversation
            conversations.table(this).updateOneById(
                input.conversationId,
                modification {
                    it.issueResolved assign true
                    it.customerSatisfaction assign input.satisfactionScore
                    it.issueCategory assign input.category
                }
            )

            // Track metrics if configured
            metricsTracker?.let {
                val durationSeconds = (now() - conversation.createdAt).inWholeSeconds
                it.trackCompletion(
                    conversation.copy(
                        issueResolved = true,
                        customerSatisfaction = input.satisfactionScore,
                        issueCategory = input.category
                    ),
                    durationSeconds
                )
            }

            Unit
        }
    )

    /**
     * Update conversation category.
     * POST /conversations/{id}/categorize
     */
    public val categorize = path.path("conversations").arg<Uuid>("id").path("categorize").post.api(
        summary = "Categorize conversation",
        authOptions = authOptions(authRequirement),
        implementation = { input: CategorizeInput ->
            conversations.table(this).updateOneById(
                input.conversationId,
                modification {
                    it.issueCategory assign input.category
                }
            )
        }
    )
}

@Serializable
public data class MarkResolvedInput(
    val conversationId: Uuid,
    val satisfactionScore: Int? = null, // 1-5
    val category: String? = null
)

@Serializable
public data class CategorizeInput(
    val conversationId: Uuid,
    val category: String
)
```

### Complete Example: Composing All Utilities

```kotlin
class BlogAssistantChat(
    database: ServerSetting<Database.Settings, Database>,
    override val defaultLlm: ServerSetting<LLMClientAndModelSettings, LLMClientAndModel>,
    private val blogPostInfo: ModelInfo<User, BlogPost, Uuid>,
) : LLMChatEndpoints<User>(
    database = database,
    authRequirement = Server.UserAuth.require(),
    conversationPermissions = { /* ... */ },
    messagePermissions = { /* ... */ },
) {

    // ===== Utilities - Compose as needed =====

    private val sentimentDetection = ConversationSentimentDetection<User>(
        analyzer = KeywordSentimentAnalyzer(
            llmProvider = { defaultLlm() },
            frustrationThreshold = 6
        ),
        onEscalation = { auth, conv, result ->
            // Custom: trigger transfer-to-human if available
            // OR just use default message
        }
    )

    private val metrics = ConversationMetricsTracker<User>(
        agentName = "blog_assistant"
    )

    private val lifecycle = ConversationLifecycleEndpoints(
        conversations = conversations,
        authRequirement = Server.UserAuth.require(),
        metricsTracker = metrics
    )

    // ===== Include lifecycle endpoints =====

    init {
        path.path("lifecycle").include(lifecycle)
    }

    // ===== Tools =====

    override val tools: Map<String, ChatTool<User, *>> =
        (blogPostInfo.readTools(queryLimit = 20) + blogPostInfo.writeTools(writeLimit = 5, modelExamples = emptyList()))
            .associateBy { it.name }

    // ===== Override respond() to use utilities =====

    context(serverRuntime: ServerRuntime)
    override suspend fun respond(
        auth: AuthAccess<User>,
        conversation: SystemChatConversation
    ) {
        // Use sentiment detection utility
        if (sentimentDetection.checkAndHandle(this, auth, conversation)) {
            return  // Escalated, stop processing
        }

        // Use metrics tracking utility
        metrics.trackResponse(this, conversation) {
            // Delegate to parent for actual response
            super.respond(auth, conversation)
        }
    }

    // ===== Optional: Track tool calls =====

    context(serverRuntime: ServerRuntime)
    override suspend fun processToolCall(
        auth: AuthAccess<User>,
        conversation: SystemChatConversation,
        tool: ChatTool<User, *>,
        arguments: String
    ): ToolCallResult {
        return metrics.trackToolCall(tool.name) {
            super.processToolCall(auth, conversation, tool, arguments)
        }
    }
}
```

### Benefits of Utility Pattern

✅ **No inheritance required** - Just create utility instances
✅ **Pick and choose** - Use only what you need
✅ **Easy to test** - Each utility tested independently
✅ **Clear composition** - Utilities are explicit properties
✅ **Flexible configuration** - Each utility configures independently
✅ **Reusable** - Same utilities across multiple agents

---

## Implementation Steps

### Phase 1: Foundation (Sprint 1)
1. ✅ Create `AiAgentMetrics.kt` with OTel metric instruments
2. ✅ Update `SystemChatConversation` data model with sentiment/outcome fields
3. ✅ Create `SentimentAnalyzer` interface for pluggability
4. ✅ Create `KeywordSentimentAnalyzer` as default implementation
5. Create `ConversationSentimentDetection` utility class
6. Create `ConversationMetricsTracker` utility class
7. Create `ConversationLifecycleEndpoints` utility endpoints
8. Test sentiment detection with mock conversations

### Phase 2: Integration & Testing (Sprint 1 continued)
9. Wire up `ConversationMetricsTracker.getMetrics()` to ServerRuntime's OpenTelemetry
10. Update `BlogAssistantChat` demo to use utilities
11. Add integration tests for sentiment + metrics composition
12. Test lifecycle endpoints (resolve, categorize)

### Phase 3: Testing & Validation (Sprint 2)
13. Write unit tests for sentiment detection
14. Write integration tests for metrics recording
15. Set up Grafana dashboards for agent metrics
16. Document metric conventions

---

## Testing Plan

### Unit Tests

```kotlin
class SentimentAnalysisToolTest {
    @Test
    fun `detects frustration keywords`() {
        val messages = listOf(
            SystemChatMessage(/* ... */, content = "This is frustrating!")
        )
        val result = sentimentAnalyzer.quickSentimentCheck(messages)
        assertTrue(result.frustrationLevel > 5)
        assertTrue(result.shouldEscalate)
    }

    @Test
    fun `detects positive sentiment`() {
        val messages = listOf(
            SystemChatMessage(/* ... */, content = "Thank you, this is helpful!")
        )
        val result = sentimentAnalyzer.quickSentimentCheck(messages)
        assertTrue(result.positiveSignals > 0)
        assertFalse(result.shouldEscalate)
    }
}

class AiAgentMetricsTest {
    @Test
    fun `records conversation metrics`() {
        // Setup mock meter
        // Record conversation start/end
        // Verify counters incremented
    }
}
```

---

## Example Metrics Queries

### Grafana Dashboard Queries

```promql
# Conversation resolution rate
rate(gen_ai_agent_conversation_completed{resolution_status="resolved"}[5m])
/
rate(gen_ai_agent_conversation_completed[5m])

# Average customer satisfaction
avg_over_time(gen_ai_agent_satisfaction_score[1h])

# Escalation rate
rate(gen_ai_agent_escalation_count[5m])

# Tool error rate
rate(gen_ai_agent_tool_calls{gen_ai_action_status="error"}[5m])
/
rate(gen_ai_agent_tool_calls[5m])

# P95 response time
histogram_quantile(0.95, rate(gen_ai_agent_llm_duration_bucket[5m]))

# Frustration level distribution
sum by (frustration_level) (rate(gen_ai_agent_sentiment_frustration[5m]))
```

---

## Sources

- [OpenTelemetry for AI Systems](https://uptrace.dev/blog/opentelemetry-ai-systems)
- [AI Agent Observability - OpenTelemetry Blog](https://opentelemetry.io/blog/2025/ai-agent-observability/)
- [GenAI Semantic Conventions](https://github.com/open-telemetry/semantic-conventions/issues/2664)
- [Datadog LLM Observability with OTel](https://www.datadoghq.com/blog/llm-otel-semantic-convention/)
- [OpenLLMetry - Traceloop](https://github.com/traceloop/openllmetry)

## Next Steps After This Plan

1. **Transfer to Human** - Complete the phone transfer implementation (already in progress)
2. **Tool Fallback Chains** - Add graceful degradation when tools fail
3. **Issue Pattern Analytics** - Aggregate metrics to identify common problems
