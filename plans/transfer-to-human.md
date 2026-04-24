# Plan: Phone Call Agent Transfer to Human (lightning-server)

## Overview

Add capability for the voice/phone AI agent to transfer calls to a human support agent using a conference bridge pattern
where the AI introduces both parties before disconnecting.

## Requirements

- **Conference/bridge**: AI stays on briefly to introduce both parties
- **Phone number destination**: Transfer to a specific phone number
- **AI continues conversation during wait**: Gathers info, provides updates, handles limited hours
- **AI summary**: Generate brief summary to share with human agent

## Prerequisites

The `Conference` instruction must be added to service-abstractions first. Update the service-abstractions dependency
version in `gradle.properties` before implementing.

---

## Implementation Steps

### Step 1: Add Transfer Data Models (ai-shared)

**File**: `ai-shared/src/main/kotlin/com/lightningkite/lightningserver/ai/TransferModels.kt` (new file)

```kotlin
@Serializable
data class TransferConfiguration(
    val supportPhoneNumber: PhoneNumber,
    val supportHours: List<SupportHours> = emptyList(),  // Empty = 24/7
    val timezone: String = "America/Denver",
    val unavailableMessage: String = "Our support team is currently unavailable."
)

@Serializable
data class SupportHours(
    val dayOfWeek: Int,  // 1=Monday, 7=Sunday
    val startTime: String,  // "09:00"
    val endTime: String     // "17:00"
)

@Serializable
data class TransferState(
    val transferId: String = Uuid.random().toString(),
    val status: TransferStatus,
    val conferenceName: String,
    val summary: String? = null,
    val initiatedAt: Instant
)

@Serializable
enum class TransferStatus {
    PREPARING, CONNECTING, INTRODUCING, COMPLETED, FAILED, CANCELLED
}
```

### Step 2: Update SystemChatConversation

**File**: `ai-shared/src/main/kotlin/com/lightningkite/lightningserver/ai/SystemChatModels.kt`

Add optional transfer state to conversation:

```kotlin
data class SystemChatConversation(
    // ...existing fields...
    val activeTransfer: TransferState? = null
)
```

### Step 3: Create TransferToHumanTool

**File**: `ai/src/main/kotlin/com/lightningkite/lightningserver/ai/TransferToHumanTool.kt` (new file)

- Extends `AlwaysRequiresApprovalTool`
- `name = "transfer_to_human"`
- `description()`: Explains when AI should use this tool
- `checkApproval()`: Checks support hours availability
- `execute()`:
    1. Checks if support is available (hours)
    2. Generates handoff summary using LLM
    3. Creates TransferState and updates conversation
    4. Returns JSON with status and instructions for AI to continue conversation

### Step 4: Add Transfer Configuration to VoiceChannelSupport

**File**: `ai/src/main/kotlin/com/lightningkite/lightningserver/ai/VoiceChannelSupport.kt`

1. Add constructor parameter:
   ```kotlin
   private val transferConfig: TransferConfiguration? = null
   ```

2. Register TransferToHumanTool when building tools list (if transferConfig is set)

3. Add conference status webhook endpoints:
    - `conferenceStatus`: Handle join/leave events
    - `dialStatus`: Handle human agent answer/no-answer

4. Handle transfer initiation:
    - Move caller into conference
    - Dial out to human agent
    - On human answer: AI speaks introduction, then disconnects
    - On no-answer/timeout: Return to AI conversation

### Step 5: Add tests

**File**: `ai/src/test/kotlin/com/lightningkite/lightningserver/ai/TransferToHumanToolTest.kt` (new file)

- Test support hours availability check
- Test summary generation
- Test approval workflow

---

## Key Files to Modify

| File                                | Change                      |
|-------------------------------------|-----------------------------|
| `ai-shared/.../TransferModels.kt`   | New file with data models   |
| `ai-shared/.../SystemChatModels.kt` | Add `activeTransfer` field  |
| `ai/.../TransferToHumanTool.kt`     | New file with transfer tool |
| `ai/.../VoiceChannelSupport.kt`     | Integration and webhooks    |

---

## Transfer Flow

```
1. User: "Transfer me to a human"
2. AI calls transfer_to_human tool
3. Tool checks support hours
   - If unavailable: Returns message, AI informs user
   - If available: Continues...
4. Tool generates summary from conversation
5. Tool creates TransferState, returns "connecting" status
6. AI tells user "I'm connecting you now, anything else to note?"
7. System moves caller into conference room
8. System dials human agent
9. AI provides periodic updates: "Still connecting..."
10. Human agent answers -> joins conference
11. AI speaks introduction with summary
12. AI disconnects from conference
13. Caller and human continue privately
```

## Edge Cases to Handle

- Human agent doesn't answer -> Return to AI, inform user
- Caller hangs up during transfer -> Clean up state
- Support hours boundary -> Handle gracefully
- Multiple transfer attempts -> State management
