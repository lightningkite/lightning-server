package com.lightningkite.lightningserver.guide.samples

// region db-imports
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.settings.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.data.*
import com.lightningkite.services.database.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.*
import kotlin.uuid.*
// endregion db-imports

// region note-model
@Serializable
@GenerateDataClassPaths
data class Note(
    override val _id: Uuid = Uuid.random(),
    val title: String,
    val body: String,
) : HasId<Uuid>
// endregion note-model

// region note-server
object NoteDbServer : ServerBuilder() {
    val database = setting("database", Database.Settings())

    // GET /notes — list all notes
    val list = path.path("notes").get bind ApiHttpHandler(
        summary = "List all notes",
        auth = noAuth,
        successCode = HttpStatus.OK,
        errorCases = emptyList(),
        implementation = { _: Unit ->
            database().table<Note>().find(Condition.Always).toList()
        }
    )

    // POST /notes — create a new note
    val create = path.path("notes").post bind ApiHttpHandler(
        summary = "Create a note",
        auth = noAuth,
        successCode = HttpStatus.Created,
        errorCases = emptyList(),
        implementation = { input: Note ->
            database().table<Note>().insertOne(input)
        }
    )
}
// endregion note-server

// region db-test
fun databaseTest() = NoteDbServer.testBlocking(settings = { database set Database.Settings("ram") }) {
    // Insert two notes
    val first = NoteDbServer.create.test(null, Note(title = "Shopping", body = "Eggs, milk"))
    val second = NoteDbServer.create.test(null, Note(title = "Ideas", body = "Start a blog"))

    // List returns both
    val all = NoteDbServer.list.test(null, Unit)
    check(all.size == 2)

    // Direct table access for condition / modification / delete
    val table = NoteDbServer.database().table<Note>()

    // condition { } builds a type-safe query using generated path extensions
    val found = table.find(condition { it.title eq "Shopping" }).toList()
    check(found.size == 1)
    check(found[0]._id == first!!._id)

    // modification { } builds a type-safe update
    table.updateOneIgnoringResult(
        condition { it._id eq first._id },
        modification { it.body assign "Eggs, milk, bread" }
    )
    val updated = table.get(first._id)!!
    check(updated.body == "Eggs, milk, bread")

    // delete
    table.deleteOneIgnoringOld(condition { it._id eq second!!._id })
    check(table.count() == 1)
}
// endregion db-test
