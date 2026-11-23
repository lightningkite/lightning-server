package com.lightningkite.lightningserver.guide.samples

// region rt-imports
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.lightningserver.typed.ModelRestEndpointsAndUpdatesWebSocket.Companion.plus
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.services.data.*
import com.lightningkite.services.database.*
import kotlinx.serialization.builtins.serializer
import kotlin.uuid.*
// endregion rt-imports

// region rt-server
@Queryable
data class Ticket(
    override val _id: Uuid = Uuid.random(),
    val title: String,
    val assignee: String,
    val open: Boolean = true,
) : HasId<Uuid>

object TicketServer : ServerBuilder() {
    init { registerBasicMediaTypeCoders() }

    val database = setting("database", Database.Settings())

    val tickets = database.modelInfo<HasId<*>?, Ticket, Uuid>(
        auth = noAuth,
        tableName = "Ticket",
        permissions = { ModelPermissions(read = Ticket.open eq true, manage = Condition.Always) },
    )

    val rest = path.path("tickets") include
        ModelRestEndpoints(tickets) + ModelRestUpdatesWebSocket(tickets, key = Ticket.assignee)
}
// endregion rt-server

// region rt-test
fun ticketUpdatesTest() = TicketServer.testBlocking(settings = { database set Database.Settings("ram") }) {
    val json = engine.externalSerialization.json
    val updatesSerializer = CollectionUpdates.serializer(Ticket.serializer(), Uuid.serializer())
    val received = mutableListOf<CollectionUpdates<Ticket, Uuid>>()

    val socket = TicketServer.rest.webSocket.webSocket.test()
    socket.onMessageSent = { frame -> received += json.decodeFromString(updatesSerializer, frame.text) }

    val watch: Condition<Ticket> = Ticket.assignee eq "alice"
    socket.send(WebSocketFrame.Text(json.encodeToString(Condition.serializer(Ticket.serializer()), watch)))

    val rest = TicketServer.rest.endpoints
    val jam = rest.insert.test(null, Ticket(title = "Printer jam", assignee = "alice"))
    rest.insert.test(null, Ticket(title = "VPN down", assignee = "bob"))
    rest.modify.test(jam._id, null, Modification { it.open assign false })

    check(received.size == 3)
    check(received[0].condition == watch)              // subscription acknowledged
    check(received[1].updates == setOf(jam))           // alice's new ticket; bob's is never sent
    check(received[2].remove == setOf(jam._id))        // closed, so no longer readable
}
// endregion rt-test
