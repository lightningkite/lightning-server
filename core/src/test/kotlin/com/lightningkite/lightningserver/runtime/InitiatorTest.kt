package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.http.PathSegments
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.RawHttpEndpoint
import com.lightningkite.lightningserver.pathing.RawWebSocketPath
import com.lightningkite.lightningserver.roundTripTest
import kotlin.test.Test
import kotlin.uuid.Uuid

/**
 * An initiator crosses a task queue and the DynamoDB socket row, so a subtype that cannot round-trip
 * is a socket dropped on deploy or a task that cannot say what launched it — neither of which would
 * show up at compile time.
 */
@OptIn(InternalLightningServerApi::class)
class InitiatorTest {
    private val execution = Uuid.parse("00000000-0000-4000-8000-000000000001")
    private val root = Uuid.parse("00000000-0000-4000-8000-000000000002")
    private val socket = Uuid.parse("00000000-0000-4000-8000-000000000003")
    private val location = PathSegments.parse("tasks/reindex")

    @Test
    fun `every subtype survives a round trip`() {
        Initiator.Http(
            executionId = execution,
            causedBy = root,
            rootExecutionId = root,
            endpoint = RawHttpEndpoint<PathSpec>(asString = "/users/abc", method = HttpMethod.GET),
        ).roundTripTest()
        Initiator.WebSocket(
            executionId = execution,
            rootExecutionId = execution,
            socketId = socket,
            path = RawWebSocketPath<PathSpec>("/updates"),
            phase = Initiator.WebSocket.Phase.ClientMessage,
        ).roundTripTest()
        Initiator.Task(
            executionId = execution,
            causedBy = root,
            rootExecutionId = root,
            attributedTo = root,
            location = location,
        ).roundTripTest()
        Initiator.Schedule(executionId = execution, attributedTo = execution, location = location).roundTripTest()
        Initiator.Startup(executionId = execution, location = location).roundTripTest()
        Initiator.PreDeploy(executionId = execution, location = location).roundTripTest()
        Initiator.Direct(executionId = execution).roundTripTest()
    }

    /** Polymorphic dispatch is what makes the persisted form readable back as the right subtype. */
    @Test
    fun `a subtype survives a round trip through the sealed interface`() {
        val initiator: Initiator = Initiator.Task(executionId = execution, attributedTo = execution, location = location)
        initiator.roundTripTest()
    }
}
