# Permissions

A model's permissions say, per caller, which rows they may create, read, update, and delete, which fields they see,
and which fields they may change.  You write them once in `modelInfo`; every access through `info.table(auth)`,
including the [generated REST endpoints](model-rest.md) and [live updates](model-realtime.md), enforces them in the
database query itself.

| Layer | Type | Controls |
|---|---|---|
| Rows | `Condition<T>` per operation in `ModelPermissions<T>` | which rows each operation may touch |
| Field reads | `Mask<T>` (`readMask`) | which field values a caller sees |
| Field writes | `UpdateRestrictions<T>` (`updateRestrictions`) | which fields may change, when, and to what |

## Minimal example

<!-- sample: com/lightningkite/lightningserver/guide/samples/PermissionsSamples.kt#perm-imports -->
```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.data.*
import com.lightningkite.services.database.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.*
import kotlinx.serialization.builtins.serializer
import kotlin.uuid.*
```

Writers own articles; editors can do anything; everyone, signed in or not, can read published articles:

<!-- sample: com/lightningkite/lightningserver/guide/samples/PermissionsSamples.kt#perm-models -->
```kotlin
@Queryable
data class Writer(
    override val _id: Uuid = Uuid.random(),
    val name: String,
    val isEditor: Boolean = false,
) : HasId<Uuid>

@Queryable
data class Article(
    override val _id: Uuid = Uuid.random(),
    val authorId: Uuid,
    val title: String,
    val published: Boolean = false,
    val internalNotes: String = "",
) : HasId<Uuid>

object WriterAuth : PrincipalType<Writer, Uuid> {
    override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
    override val subjectSerializer: KSerializer<Writer> = Writer.serializer()

    context(server: ServerRuntime)
    override suspend fun fetch(id: Uuid): Writer =
        ArticleServer.writers().get(id) ?: throw NotFoundException("No such writer")
}
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/PermissionsSamples.kt#perm-server -->
```kotlin
object ArticleServer : ServerBuilder() {
    init {
        registerBasicMediaTypeCoders()
        register(WriterAuth)
    }

    val database = setting("database", Database.Settings())
    val writers = database.registerTable<Writer>("Writer")

    val articles = database.modelInfo<Writer?, Article, Uuid>(
        auth = WriterAuth.require() or AuthRequirement.None,
        tableName = "Article",
        permissions = {
            val writer: Writer? = authOrNull?.fetch()
            val editor: Condition<Article> = Condition(writer?.isEditor == true)
            val own: Condition<Article> = Article.authorId eqNn writer?._id

            ModelPermissions(
                create = own or editor,
                read = (Article.published eq true) or own or editor,
                readMask = mask {
                    it.internalNotes.mask("", unless = own or editor)
                },
                update = own or editor,
                updateRestrictions = updateRestrictions {
                    it.authorId.cannotBeModified()
                    it.published requires editor
                },
                delete = own or editor,
            )
        },
    )

    val rest = path.path("articles") include ModelRestEndpoints(articles)

    val myDrafts = path.path("my-drafts").get bind ApiHttpHandler(
        summary = "My unpublished articles",
        auth = WriterAuth.require(),
        implementation = { _: Unit ->
            articles.table(auth)
                .find((Article.authorId eq auth.id) and (Article.published eq false))
                .toList()
        }
    )
}
```

The `permissions` lambda runs for every request with the caller's `AuthAccess` as receiver: `authOrNull` is the
`Authentication` or `null` for anonymous callers (`auth` when the requirement guarantees one).  `fetch()` loads the
user and caches it on that `Authentication`.  Conditions are built from what you know about the caller:
`Condition(boolean)` turns a Kotlin fact into `Always`/`Never`, and `eqNn` gives `Never` when the value is `null`, so
`own` matches nothing for anonymous callers.

What it does:

<!-- sample: com/lightningkite/lightningserver/guide/samples/PermissionsSamples.kt#perm-test -->
```kotlin
fun permissionsTest() = ArticleServer.testBlocking(settings = { database set Database.Settings("ram") }) {
    val alice = ArticleServer.writers().insertOne(Writer(name = "Alice"))
    val bob = ArticleServer.writers().insertOne(Writer(name = "Bob"))
    val eve = ArticleServer.writers().insertOne(Writer(name = "Eve", isEditor = true))
    val rest = ArticleServer.rest

    val draft = rest.insert.test(
        WriterAuth.testAuth(alice),
        Article(authorId = alice._id, title = "Draft", internalNotes = "needs a better title"),
    )

    // Bob can't see an unpublished article, so to him it doesn't exist.
    check(rest.query.test(WriterAuth.testAuth(bob), Query()).isEmpty())

    // Alice may edit her article, but not publish it.
    rest.modify.test(draft._id, WriterAuth.testAuth(alice), Modification { it.title assign "Launch" })
    val publishAttempt = runCatching {
        rest.modify.test(draft._id, WriterAuth.testAuth(alice), Modification { it.published assign true })
    }
    check(publishAttempt.exceptionOrNull() is NotFoundException)

    rest.modify.test(draft._id, WriterAuth.testAuth(eve), Modification { it.published assign true })

    // Published: Bob can read it now, with the notes masked.
    val asBob = rest.detail.test(draft._id, WriterAuth.testAuth(bob), Unit)
    check(asBob.title == "Launch" && asBob.internalNotes == "")
    check(rest.detail.test(draft._id, WriterAuth.testAuth(alice), Unit).internalNotes == "needs a better title")

    // Filtering on a masked field only matches rows where the caller could see it.
    val probe = Query<Article>(Article.internalNotes contains "title")
    check(rest.query.test(WriterAuth.testAuth(bob), probe).isEmpty())
    check(rest.query.test(WriterAuth.testAuth(alice), probe).size == 1)
}
```

## Row rules

`ModelPermissions(create, read, readMask, update, updateRestrictions, delete)`: every condition defaults to
`Condition.Never`, so an operation you leave out is denied.  Shorthands:

| Constructor | Meaning |
|---|---|
| `ModelPermissions(read = r, manage = m)` | `m` for create, update, and delete |
| `ModelPermissions(all = c)` | `c` for every operation |
| `ModelPermissions.allowAll()` | everything for everyone; only behind an admin check or for trusted internal callers |

How each operation applies them, on the permissioned table:

| Operation | Effect |
|---|---|
| find, count, aggregate | the caller's condition is ANDed with `read` |
| update, replace | ANDed with `read`, `update`, and the update restrictions; a disallowed change matches no rows |
| delete | ANDed with `read` and `delete` |
| insert | models failing `create` are dropped; the REST `insert` then answers 403 |
| upsert | throws `SecurityException` if the model fails `create` |

Because a forbidden update or delete simply matches nothing, the REST endpoints answer 404 for it, the same as for
a row the caller can't see.  That is deliberate: a caller learns nothing about rows outside their permissions.

## Hiding fields

`mask { }` lists fields to replace with a placeholder unless a condition on the row holds:

```kotlin
readMask = mask {
    it.internalNotes.mask("", unless = own or editor)
}
```

The mask is applied to every row returned, and it also guards queries: a filter, sort, group, or aggregate that
reads a masked field only considers rows where the caller could see that field.  So Bob can't find articles by
searching their notes, and `aggregate` over a masked number totals only the visible rows, with no sign that others
were skipped.  For a nullable field, mask to `null`.

## Restricting field writes

`updateRestrictions { }` is a blacklist by default: every field may change unless you restrict it.

| Rule | Effect |
|---|---|
| `it.field.cannotBeModified()` | never writable |
| `it.field requires condition` | writable only on rows that currently match `condition` |
| `it.field.mustBe { value -> condition }` | the new value must satisfy `condition` |
| `it.field.requires(requires = c, valueMust = { ... })` | both |
| `it.field.canBeModified()` | allowed (only meaningful in whitelist mode) |
| `include(otherRestrictions)` | add another `UpdateRestrictions`' rules |

<!-- sample: com/lightningkite/lightningserver/guide/samples/PermissionsSamples.kt#perm-value-rules -->
```kotlin
val articleRules: UpdateRestrictions<Article> = updateRestrictions {
    it.title.mustBe { title -> title neq "" }
    it.published.requires(requires = Article.title neq "", valueMust = { published -> published eq true })
}
```

Here a title can't be blanked, and `published` can only be set to `true` (never back), and only on an article that
already has a title.

Whitelist mode blocks every field you don't allow:

<!-- sample: com/lightningkite/lightningserver/guide/samples/PermissionsSamples.kt#perm-whitelist -->
```kotlin
val writerProfileRestrictions: UpdateRestrictions<Writer> = updateRestrictions(mode = UpdateRestrictions.Mode.Whitelist) {
    it.name.canBeModified()
}
```

To add rules to an existing `ModelPermissions`, use `permissions.withAdditionalUpdateRestrictions { ... }`.

Restrictions apply to updates.  A `replace` counts as writing every field, so any `cannotBeModified` field blocks
replaces entirely (even with the value unchanged); clients should `modify` instead.  Creation is governed by `create` alone: validate new rows with a `create` condition or with
[validation](validation.md).

## Custom endpoints and system access

`myDrafts` above gets `articles.table(auth)`: the same permissioned table the generated endpoints use, so a custom
query can't leak more than the REST API would.  Code that must see everything, such as background tasks and
migrations, uses `articles.table()`, which applies signals but no permissions.  See
[Model REST endpoints](model-rest.md#custom-endpoints-next-to-generated-ones) for the full list of table accessors.

The generated `GET /_permissions_` returns the caller's resolved `ModelPermissions` as JSON.  Conditions are
evaluable values (`condition(model)` returns a `Boolean`), so a client can use them to show or hide edit and delete
buttons per row.

## Gotchas

- **The lambda runs on every request.**  Keep it to what you need: `fetch()` is cached per `Authentication`, but
  other lookups you add (memberships, roles) are not.  Load them in one query, or [cache](caching.md) them.
- **`requires` conditions are not masked.**  If `it.x requires (it.secret eq ...)` tests a masked field, whether an
  update matched reveals something about it.
- **Raw tables bypass everything.**  `registerTable`'s accessor and `info.baseTable()` have no permissions; don't
  use them to answer callers.
- **Websockets cache permissions.**  A live-update connection re-derives them periodically, not per message; see
  [Realtime model updates](model-realtime.md#permissions).

## Next

- [Authentication](auth.md): principal types, `require()`, and sessions.
- [Model REST endpoints](model-rest.md): the endpoints these rules protect.
- [Database](database.md#conditions): the full condition operator list.
