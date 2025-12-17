package com.lightningkite.lightningserver.ai

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.data.WebsocketAdapter
import com.lightningkite.services.phonecall.AudioStreamCommand
import com.lightningkite.services.phonecall.AudioStreamEvent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class DirectVoiceStreamAdapterTest {

    private val connectionId = Uuid.random().toString()
    private val adapter = DirectVoiceStreamAdapter(connectionId)
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parseStart returns correct AudioStreamStart`(): Unit = runBlocking {
        val result = adapter.parseStart(
            queryParameters = emptyList(),
            headers = emptyMap(),
            body = TypedData(com.lightningkite.services.data.Data.Text(""), com.lightningkite.MediaType.Text.Plain)
        )

        assertEquals(connectionId, result.callId)
        assertEquals(connectionId, result.streamId)
        assertTrue(result.metadata.isEmpty())
    }

    @Test
    fun `parse converts VoiceClientMessage Audio to AudioStreamEvent Audio`(): Unit = runBlocking {
        val audioData = "dGVzdCBhdWRpbyBkYXRh" // base64 "test audio data"
        val message = VoiceClientMessage.Audio(data = audioData)
        val messageJson = json.encodeToString(VoiceClientMessage.serializer(), message)

        val frame = WebsocketAdapter.Frame.Text(messageJson)
        val result = adapter.parse(frame)

        assertTrue(result is AudioStreamEvent.Audio)
        assertEquals(connectionId, result.callId)
        assertEquals(connectionId, result.streamId)
        assertEquals(audioData, result.payload)
        assertEquals(0L, result.sequenceNumber)
        assertTrue(result.timestamp > 0)
    }

    @Test
    fun `parse converts VoiceClientMessage Commit to AudioStreamEvent Stop`(): Unit = runBlocking {
        val message = VoiceClientMessage.Commit
        val messageJson = json.encodeToString(VoiceClientMessage.serializer(), message)

        val frame = WebsocketAdapter.Frame.Text(messageJson)
        val result = adapter.parse(frame)

        assertTrue(result is AudioStreamEvent.Stop)
        assertEquals(connectionId, result.callId)
        assertEquals(connectionId, result.streamId)
    }

    @Test
    fun `parse converts VoiceClientMessage Cancel to AudioStreamEvent Stop`(): Unit = runBlocking {
        val message = VoiceClientMessage.Cancel
        val messageJson = json.encodeToString(VoiceClientMessage.serializer(), message)

        val frame = WebsocketAdapter.Frame.Text(messageJson)
        val result = adapter.parse(frame)

        assertTrue(result is AudioStreamEvent.Stop)
        assertEquals(connectionId, result.callId)
        assertEquals(connectionId, result.streamId)
    }

    @Test
    fun `parse rejects binary frames with BadRequestException`(): Unit = runBlocking {
        val frame = WebsocketAdapter.Frame.Binary(byteArrayOf(1, 2, 3))

        assertFailsWith<BadRequestException> {
            adapter.parse(frame)
        }
    }

    @Test
    fun `parse rejects invalid JSON`(): Unit = runBlocking {
        val frame = WebsocketAdapter.Frame.Text("invalid json")

        assertFailsWith<Exception> {
            adapter.parse(frame)
        }
    }

    @Test
    fun `render converts AudioStreamCommand Audio to VoiceServerMessage Audio`(): Unit = runBlocking {
        val audioData = "dGVzdCBhdWRpbyBkYXRh"
        val command = AudioStreamCommand.Audio(
            streamId = connectionId,
            payload = audioData
        )

        val frame = adapter.render(command)

        assertTrue(frame is WebsocketAdapter.Frame.Text)
        val parsedMessage = json.decodeFromString<VoiceServerMessage>(frame.text)
        assertTrue(parsedMessage is VoiceServerMessage.Audio)
        assertEquals(audioData, parsedMessage.data)
    }

    @Test
    fun `render converts AudioStreamCommand Clear to VoiceServerMessage SpeechEnded`(): Unit = runBlocking {
        val command = AudioStreamCommand.Clear(streamId = connectionId)

        val frame = adapter.render(command)

        assertTrue(frame is WebsocketAdapter.Frame.Text)
        val parsedMessage = json.decodeFromString<VoiceServerMessage>(frame.text)
        assertTrue(parsedMessage is VoiceServerMessage.SpeechEnded)
    }

    @Test
    fun `render converts AudioStreamCommand Mark to empty VoiceServerMessage Audio`(): Unit = runBlocking {
        val command = AudioStreamCommand.Mark(
            streamId = connectionId,
            name = "test-mark"
        )

        val frame = adapter.render(command)

        assertTrue(frame is WebsocketAdapter.Frame.Text)
        val parsedMessage = json.decodeFromString<VoiceServerMessage>(frame.text)
        assertTrue(parsedMessage is VoiceServerMessage.Audio)
        assertEquals("", parsedMessage.data)
    }

    @Test
    fun `round trip - parse audio then render audio`(): Unit = runBlocking {
        // Client sends audio
        val originalAudioData = "YWJjZGVmZ2hpamtsbW5vcA=="
        val clientMessage = VoiceClientMessage.Audio(data = originalAudioData)
        val clientJson = json.encodeToString(VoiceClientMessage.serializer(), clientMessage)

        // Parse incoming message
        val parsedEvent = adapter.parse(WebsocketAdapter.Frame.Text(clientJson))
        assertTrue(parsedEvent is AudioStreamEvent.Audio)
        assertEquals(originalAudioData, parsedEvent.payload)

        // Server sends audio back
        val serverCommand = AudioStreamCommand.Audio(
            streamId = connectionId,
            payload = parsedEvent.payload
        )
        val renderedFrame = adapter.render(serverCommand)

        // Verify server message
        assertTrue(renderedFrame is WebsocketAdapter.Frame.Text)
        val serverMessage = json.decodeFromString<VoiceServerMessage>(renderedFrame.text)
        assertTrue(serverMessage is VoiceServerMessage.Audio)
        assertEquals(originalAudioData, serverMessage.data)
    }

    @Test
    fun `VoiceClientMessage serialization - Audio`() {
        val message = VoiceClientMessage.Audio(data = "test123")
        val json = Json.encodeToString(VoiceClientMessage.serializer(), message)

        assertTrue(json.contains("\"type\":\"audio\""))
        assertTrue(json.contains("\"data\":\"test123\""))

        val decoded = Json.decodeFromString<VoiceClientMessage>(json)
        assertTrue(decoded is VoiceClientMessage.Audio)
        assertEquals("test123", decoded.data)
    }

    @Test
    fun `VoiceClientMessage serialization - Commit`() {
        val message = VoiceClientMessage.Commit
        val json = Json.encodeToString(VoiceClientMessage.serializer(), message)

        assertTrue(json.contains("\"type\":\"commit\""))

        val decoded = Json.decodeFromString<VoiceClientMessage>(json)
        assertTrue(decoded is VoiceClientMessage.Commit)
    }

    @Test
    fun `VoiceClientMessage serialization - Cancel`() {
        val message = VoiceClientMessage.Cancel
        val json = Json.encodeToString(VoiceClientMessage.serializer(), message)

        assertTrue(json.contains("\"type\":\"cancel\""))

        val decoded = Json.decodeFromString<VoiceClientMessage>(json)
        assertTrue(decoded is VoiceClientMessage.Cancel)
    }

    @Test
    fun `VoiceServerMessage serialization - SessionReady`() {
        val message = VoiceServerMessage.SessionReady(sessionId = "session-123")
        val json = Json.encodeToString(VoiceServerMessage.serializer(), message)

        assertTrue(json.contains("\"type\":\"session_ready\""))
        assertTrue(json.contains("\"sessionId\":\"session-123\""))

        val decoded = Json.decodeFromString<VoiceServerMessage>(json)
        assertTrue(decoded is VoiceServerMessage.SessionReady)
        assertEquals("session-123", decoded.sessionId)
    }

    @Test
    fun `VoiceServerMessage serialization - Audio`() {
        val message = VoiceServerMessage.Audio(data = "audio123")
        val json = Json.encodeToString(VoiceServerMessage.serializer(), message)

        assertTrue(json.contains("\"type\":\"audio\""))
        assertTrue(json.contains("\"data\":\"audio123\""))

        val decoded = Json.decodeFromString<VoiceServerMessage>(json)
        assertTrue(decoded is VoiceServerMessage.Audio)
        assertEquals("audio123", decoded.data)
    }

    @Test
    fun `VoiceServerMessage serialization - UserTranscript`() {
        val message = VoiceServerMessage.UserTranscript(text = "Hello there")
        val json = Json.encodeToString(VoiceServerMessage.serializer(), message)

        assertTrue(json.contains("\"type\":\"user_transcript\""))
        assertTrue(json.contains("\"text\":\"Hello there\""))

        val decoded = Json.decodeFromString<VoiceServerMessage>(json)
        assertTrue(decoded is VoiceServerMessage.UserTranscript)
        assertEquals("Hello there", decoded.text)
    }

    @Test
    fun `VoiceServerMessage serialization - AgentTranscript`() {
        val message = VoiceServerMessage.AgentTranscript(text = "I can help with that")
        val json = Json.encodeToString(VoiceServerMessage.serializer(), message)

        assertTrue(json.contains("\"type\":\"agent_transcript\""))
        assertTrue(json.contains("\"text\":\"I can help with that\""))

        val decoded = Json.decodeFromString<VoiceServerMessage>(json)
        assertTrue(decoded is VoiceServerMessage.AgentTranscript)
        assertEquals("I can help with that", decoded.text)
    }

    @Test
    fun `VoiceServerMessage serialization - SpeechStarted`() {
        val message = VoiceServerMessage.SpeechStarted
        val json = Json.encodeToString(VoiceServerMessage.serializer(), message)

        assertTrue(json.contains("\"type\":\"speech_started\""))

        val decoded = Json.decodeFromString<VoiceServerMessage>(json)
        assertTrue(decoded is VoiceServerMessage.SpeechStarted)
    }

    @Test
    fun `VoiceServerMessage serialization - SpeechEnded`() {
        val message = VoiceServerMessage.SpeechEnded
        val json = Json.encodeToString(VoiceServerMessage.serializer(), message)

        assertTrue(json.contains("\"type\":\"speech_ended\""))

        val decoded = Json.decodeFromString<VoiceServerMessage>(json)
        assertTrue(decoded is VoiceServerMessage.SpeechEnded)
    }

    @Test
    fun `VoiceServerMessage serialization - Error`() {
        val message = VoiceServerMessage.Error(code = "invalid_audio", message = "Audio format not supported")
        val json = Json.encodeToString(VoiceServerMessage.serializer(), message)

        assertTrue(json.contains("\"type\":\"error\""))
        assertTrue(json.contains("\"code\":\"invalid_audio\""))
        assertTrue(json.contains("\"message\":\"Audio format not supported\""))

        val decoded = Json.decodeFromString<VoiceServerMessage>(json)
        assertTrue(decoded is VoiceServerMessage.Error)
        assertEquals("invalid_audio", decoded.code)
        assertEquals("Audio format not supported", decoded.message)
    }
}
