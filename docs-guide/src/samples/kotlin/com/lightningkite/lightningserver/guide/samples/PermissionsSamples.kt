package com.lightningkite.lightningserver.guide.samples

// region perm-imports
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
// endregion perm-imports

// region perm-models
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
// endregion perm-models

// region perm-server
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
// endregion perm-server

// region perm-test
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
// endregion perm-test

fun permissionsExtrasTest() = ArticleServer.testBlocking(settings = { database set Database.Settings("ram") }) {
    val alice = ArticleServer.writers().insertOne(Writer(name = "Alice"))
    val bob = ArticleServer.writers().insertOne(Writer(name = "Bob"))
    val rest = ArticleServer.rest

    // Anonymous callers can read but not create.
    val anonymous = runCatching { rest.insert.test(null, Article(authorId = alice._id, title = "Spoof")) }
    check(anonymous.exceptionOrNull() is ForbiddenException) { anonymous.toString() }
    // Creating as someone else is refused the same way.
    val impersonation = runCatching { rest.insert.test(WriterAuth.testAuth(bob), Article(authorId = alice._id, title = "Spoof")) }
    check(impersonation.exceptionOrNull() is ForbiddenException)

    rest.insert.test(WriterAuth.testAuth(alice), Article(authorId = alice._id, title = "Mine"))
    check(ArticleServer.myDrafts.test(WriterAuth.testAuth(alice), Unit).map { it.title } == listOf("Mine"))
    check(ArticleServer.myDrafts.test(WriterAuth.testAuth(bob), Unit).isEmpty())

    // A replace writes every field, so a cannotBeModified field blocks it even when unchanged.
    val mine = ArticleServer.myDrafts.test(WriterAuth.testAuth(alice), Unit).single()
    val replaced = runCatching { rest.replace.test(mine._id, WriterAuth.testAuth(alice), mine.copy(title = "Renamed")) }
    check(replaced.exceptionOrNull() is NotFoundException) { replaced.toString() }

    // The permissions endpoint tells a client what this caller may do.
    val bobsPermissions = rest.permissions.test(WriterAuth.testAuth(bob), Unit)
    check(bobsPermissions.delete != Condition.Never)

    // System access skips permissions entirely.
    check(execute { ArticleServer.articles.table().count() } == 1)
}

// region perm-whitelist
val writerProfileRestrictions: UpdateRestrictions<Writer> = updateRestrictions(mode = UpdateRestrictions.Mode.Whitelist) {
    it.name.canBeModified()
}
// endregion perm-whitelist

// region perm-value-rules
val articleRules: UpdateRestrictions<Article> = updateRestrictions {
    it.title.mustBe { title -> title neq "" }
    it.published.requires(requires = Article.title neq "", valueMust = { published -> published eq true })
}
// endregion perm-value-rules

fun permissionsRestrictionsTest() {
    check(writerProfileRestrictions(Modification<Writer> { it.name assign "New" }) == Condition.Always)
    check(writerProfileRestrictions(Modification<Writer> { it.isEditor assign true }) == Condition.Never)

    check(articleRules(Modification<Article> { it.title assign "" }) == Condition.Never)
    check(articleRules(Modification<Article> { it.title assign "Ok" }) == Condition.Always)
    check(articleRules(Modification<Article> { it.published assign false }) == Condition.Never)
    check(articleRules(Modification<Article> { it.published assign true }) == (Article.title neq ""))
}

