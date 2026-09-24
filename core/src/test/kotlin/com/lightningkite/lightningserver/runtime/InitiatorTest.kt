package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.http.PathSegments
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.RawHttpEndpoint
import com.lightningkite.lightningserver.pathing.RawWebSocketPath
import com.lightningkite.lightningserver.roundTripTest
import com.lightningkite.services.data.UuidV7
import kotlin.test.Test
import kotlin.uuid.Uuid

/**
 * An initiator crosses a task queue and the DynamoDB socket row, so a subtype that cannot round-trip
 * is a socket dropped on deploy or a task that cannot say what launched it — neither of which would
 * show up at compile time.
 */
@OptIn(InternalLightningServerApi::class, com.lightningkite.services.data.Unsafe::class)
class InitiatorTest {
    private fun id(raw: String): Execution.ID = Execution.ID(UuidV7.fromRaw(Uuid.parse(raw)))

    private val execution = id("00000000-0000-4000-8000-000000000001")
    private val root = id("00000000-0000-4000-8000-000000000002")
    private val socket = id("00000000-0000-4000-8000-000000000003")
    private val location = PathSegments.parse("tasks/reindex")

    @Test
    fun `every subtype survives a round trip`() {
        Execution.Http(
            id = execution,
            causedBy = root,
            rootExecution = root,
            endpoint = RawHttpEndpoint(asString = "/users/abc", method = HttpMethod.GET),
        ).roundTripTest()
        Execution.WebSocket(
            id = execution,
            rootExecution = execution,
            socketId = socket,
            path = RawWebSocketPath("/updates"),
            phase = Execution.WebSocket.Phase.ClientMessage,
        ).roundTripTest()
        Execution.Task(
            id = execution,
            causedBy = root,
            rootExecution = root,
            location = location,
        ).roundTripTest()
        Execution.Schedule(id = execution, location = location).roundTripTest()
        Execution.Startup(id = execution, location = location).roundTripTest()
        Execution.PreDeploy(id = execution, location = location).roundTripTest()
        Execution.Direct(id = execution).roundTripTest()
    }

    /** Polymorphic dispatch is what makes the persisted form readable back as the right subtype. */
    @Test
    fun `a subtype survives a round trip through the sealed interface`() {
        val initiator: Execution = Execution.Task(id = execution, causedBy = root, rootExecution = root, location = location)
        initiator.roundTripTest()
    }
}
