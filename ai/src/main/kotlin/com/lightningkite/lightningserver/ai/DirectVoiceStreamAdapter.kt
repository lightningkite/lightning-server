package com.lightningkite.lightningserver.ai

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.data.WebsocketAdapter
import com.lightningkite.services.phonecall.AudioStreamCommand
import com.lightningkite.services.phonecall.AudioStreamEvent
import com.lightningkite.services.phonecall.AudioStreamStart
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

/**
 * Client → Server message types for direct voice WebSocket.
 */
@Serializable
public sealed class VoiceClientMessage {
    /** Send audio data (base64 encoded PCM16 24kHz) */
    @Serializable
    @SerialName("audio")
    public data class Audio(val data: String) : VoiceClientMessage()

    /** Commit audio buffer (for manual turn detection) */
    @Serializable
    @SerialName("commit")
    public data object Commit : VoiceClientMessage()

    /** Cancel current response */
    @Serializable
    @SerialName("cancel")
    public data object Cancel : VoiceClientMessage()
}

/**
 * Server → Client message types for direct voice WebSocket.
 */
@Serializable
public sealed class VoiceServerMessage {
    /** Session is ready */
    @Serializable
    @SerialName("session_ready")
    public data class SessionReady(val sessionId: String) : VoiceServerMessage()

    /** Audio data from agent (base64 encoded PCM16 24kHz) */
    @Serializable
    @SerialName("audio")
    public data class Audio(val data: String) : VoiceServerMessage()

    /** User speech transcription */
    @Serializable
    @SerialName("user_transcript")
    public data class UserTranscript(val text: String) : VoiceServerMessage()

    /** Agent speech transcription */
    @Serializable
    @SerialName("agent_transcript")
    public data class AgentTranscript(val text: String) : VoiceServerMessage()

    /** User started speaking */
    @Serializable
    @SerialName("speech_started")
    public data object SpeechStarted : VoiceServerMessage()

    /** User stopped speaking */
    @Serializable
    @SerialName("speech_ended")
    public data object SpeechEnded : VoiceServerMessage()

    /** Error occurred */
    @Serializable
    @SerialName("error")
    public data class Error(val code: String, val message: String) : VoiceServerMessage()
}

/**
 * Adapter that bridges direct voice WebSocket protocol with AudioStream protocol
 * for use with PubSubVoiceAgentHandler.
 *
 * This adapter converts between the OpenAI-compatible voice protocol (PCM16 24kHz)
 * used by direct clients and the AudioStream protocol expected by PubSubVoiceAgentHandler.
 *
 * **Audio Format**: Both direct voice and voice agent use PCM16 24kHz, so no
 * audio format conversion is needed (unlike phone calls which use μ-law 8kHz).
 *
 * @param connectionId Unique identifier for this WebSocket connection
 */
public class DirectVoiceStreamAdapter(
    private val connectionId: String,
) : WebsocketAdapter<AudioStreamStart, AudioStreamEvent, AudioStreamCommand> {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parse initial connection handshake.
     *
     * For direct voice, we don't have a separate handshake - connection details
     * come from the WebSocket state. Return minimal info using connectionId.
     */
    override suspend fun parseStart(
        queryParameters: List<Pair<String, String>>,
        headers: Map<String, List<String>>,
        body: TypedData
    ): AudioStreamStart {
        return AudioStreamStart(
            callId = connectionId,
            streamId = connectionId,
            metadata = emptyMap()
        )
    }

    /**
     * Parse incoming WebSocket frames from client.
     *
     * Converts VoiceClientMessage → AudioStreamEvent.
     */
    override suspend fun parse(frame: WebsocketAdapter.Frame): AudioStreamEvent {
        val text = (frame as? WebsocketAdapter.Frame.Text)?.text
            ?: throw BadRequestException("Expected text frame")

        val message = json.decodeFromString<VoiceClientMessage>(text)

        return when (message) {
            is VoiceClientMessage.Audio -> {
                // Direct pass-through - both use PCM16 24kHz base64
                AudioStreamEvent.Audio(
                    callId = connectionId,
                    streamId = connectionId,
                    payload = message.data,
                    timestamp = System.currentTimeMillis(),
                    sequenceNumber = 0 // We don't track sequence numbers
                )
            }

            is VoiceClientMessage.Commit -> {
                // Treat commit as a stop event (end of user's audio chunk)
                AudioStreamEvent.Stop(
                    callId = connectionId,
                    streamId = connectionId
                )
            }

            is VoiceClientMessage.Cancel -> {
                // Treat cancel as a stop event
                AudioStreamEvent.Stop(
                    callId = connectionId,
                    streamId = connectionId
                )
            }
        }
    }

    /**
     * Render outgoing commands to client.
     *
     * Converts AudioStreamCommand → VoiceServerMessage.
     */
    override suspend fun render(output: AudioStreamCommand): WebsocketAdapter.Frame {
        val message = when (output) {
            is AudioStreamCommand.Audio -> {
                // Direct pass-through - both use PCM16 24kHz base64
                VoiceServerMessage.Audio(output.payload)
            }

            is AudioStreamCommand.Clear -> {
                // Map to speech ended (interruption)
                VoiceServerMessage.SpeechEnded
            }

            is AudioStreamCommand.Mark -> {
                // Marks are internal - don't send to client
                // Return empty audio frame
                VoiceServerMessage.Audio("")
            }
        }

        val text = json.encodeToString(VoiceServerMessage.serializer(), message)
        return WebsocketAdapter.Frame.Text(text)
    }
}
