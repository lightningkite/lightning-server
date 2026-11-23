package com.lightningkite.lightningserver.guide.samples

// region db-imports
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.data.*
import com.lightningkite.services.database.*
import com.lightningkite.services.database.optics.*
import kotlinx.coroutines.flow.*
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.*
// endregion db-imports

// region note-model
@Queryable
data class Note(
    override val _id: Uuid = Uuid.random(),
    val title: String,
    val body: String = "",
    val tags: Set<String> = setOf(),
    val views: Int = 0,
    val archivedAt: Instant? = null,
) : HasId<Uuid>
// endregion note-model

// region note-server
object NoteDbServer : ServerBuilder() {
    init { registerBasicMediaTypeCoders() }

    val database = setting("database", Database.Settings())

    val notes = database.registerTable<Note>("Note")

    val list = path.path("notes").get bind ApiHttpHandler(
        summary = "List notes that are not archived",
        auth = noAuth,
        implementation = { _: Unit ->
            notes().find(Note.archivedAt eq null).toList()
        }
    )

    val create = path.path("notes").post bind ApiHttpHandler(
        summary = "Create a note",
        auth = noAuth,
        successCode = HttpStatus.Created,
        implementation = { input: Note ->
            notes().insertOne(input)
        }
    )
}
// endregion note-server

// region db-crud
fun databaseTest() = NoteDbServer.testBlocking(settings = { database set Database.Settings("ram") }) {
    val notes = NoteDbServer.notes()

    val shopping = notes.insertOne(Note(title = "Shopping", body = "Eggs, milk", tags = setOf("home")))
    notes.insert(listOf(Note(title = "Ideas"), Note(title = "Old", archivedAt = Clock.System.now())))

    check(notes.find(Note.tags.any { it eq "home" }).toList() == listOf(shopping))
    check(notes.get(shopping._id) == shopping)
    check(notes.count(Note.archivedAt eq null) == 2)

    notes.updateOneById(shopping._id, Modification {
        it.body assign "Eggs, milk, bread"
        it.views += 1
    })
    check(notes.get(shopping._id)!!.views == 1)

    notes.deleteMany(Note.archivedAt neq null)
    check(notes.count() == 2)

    check(NoteDbServer.list.test(null, Unit).size == 2)
}
// endregion db-crud

// region db-conditions
val noteConditions: List<Condition<Note>> = listOf(
    Note.title eq "Shopping",
    Note.views gte 10,
    Note.title inside listOf("Shopping", "Ideas"),
    Note.body contains "milk",                           // case-insensitive by default
    Note.tags.any { it eq "home" },
    Note.archivedAt.notNull lt Clock.System.now(),       // false when archivedAt is null
    (Note.views gt 0) and (Note.archivedAt eq null),
    (Note.title eq "A") or (Note.title eq "B"),
    !Note.tags.any { it eq "spam" },
    Condition { (it.views gt 0) and (it.title neq "") }, // lambda form
    Condition.Always,
)
// endregion db-conditions

// region db-modifications
val tidyUp: Modification<Note> = Modification {
    it.title assign "Groceries"
    it.views += 1
    it.tags.removeAll { tag -> tag eq "home" }
    it.archivedAt assign null
}
// endregion db-modifications

fun databaseDslTest() {
    val note = Note(title = "Shopping", body = "Eggs, milk", tags = setOf("home"), views = 3)
    val matched = noteConditions.map { it(note) }
    check(matched == listOf(true, false, true, true, true, false, true, false, true, true, true)) { matched.toString() }
    val changed = tidyUp(note)
    check(changed.title == "Groceries")
    check(changed.views == 4)
    check(changed.tags.isEmpty())
}
