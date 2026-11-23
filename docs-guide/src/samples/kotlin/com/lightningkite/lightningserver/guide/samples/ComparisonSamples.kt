package com.lightningkite.lightningserver.guide.samples.comparison

// Shared by the appendix comparison pages (vs-django.md, vs-rails.md, vs-spring.md).

// region comparison-imports
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.cache.*
import com.lightningkite.services.data.*
import com.lightningkite.services.database.*
import kotlinx.coroutines.flow.toList
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid
// endregion comparison-imports

// region comparison-model
@Queryable
data class Article(
    override val _id: Uuid = Uuid.random(),
    @MaxLength(200) val title: String,
    val author: String,
    val published: Boolean = false,
    val createdAt: Instant = Clock.System.now(),
) : HasId<Uuid>
// endregion comparison-model

// region comparison-server
object ArticleServer : ServerBuilder() {
    init { registerBasicMediaTypeCoders() }

    val database = setting("database", Database.Settings())
    val cache = setting("cache", Cache.Settings())

    val notifyFollowers = path.path("tasks").path("notify-followers") bind Task { articleId: Uuid ->
        cache().set("last-notified", articleId)
    }

    val articleInfo = database.modelInfo<HasId<*>?, Article, Uuid>(
        auth = noAuth,
        tableName = "Article",
        signals = { table -> table.postCreate { notifyFollowers.launch(it._id) } },
        permissions = { ModelPermissions(read = Condition { it.published eq true }) },
    )

    val articles = path.path("articles") include ModelRestEndpoints(articleInfo)

    val recent = path.path("recent-articles").get bind ApiHttpHandler(
        summary = "List published articles, newest first",
        auth = noAuth,
        successCode = HttpStatus.OK,
        errorCases = emptyList(),
        implementation = { _: Unit ->
            articleInfo.table().find(
                Condition { it.published eq true },
                orderBy = listOf(SortPart(Article.createdAt, ascending = false)),
            ).toList()
        }
    )

    val byAuthor = path.path("authors").arg<String>("author").path("articles").get bind ApiHttpHandler(
        summary = "List an author's published articles",
        auth = noAuth,
        successCode = HttpStatus.OK,
        errorCases = emptyList(),
        implementation = { _: Unit ->
            articleInfo.table().find(Condition { (it.author eq route.arg1) and (it.published eq true) }).toList()
        }
    )

    val purgeDrafts = path.path("schedules").path("purge-drafts") bind ScheduledTask(frequency = 1.days) {
        articleInfo.table().deleteMany(Condition { (it.published eq false) and (it.createdAt lt now() - 90.days) })
    }
}
// endregion comparison-server

// region comparison-test
class ArticleServerTest {
    @Test
    fun recentListsOnlyPublished() = ArticleServer.testBlocking(settings = { database set Database.Settings("ram") }) {
        val live = execute {
            ArticleServer.articleInfo.table().insertOne(Article(title = "Draft", author = "ana"))
            ArticleServer.articleInfo.table().insertOne(Article(title = "Live", author = "ana", published = true))
        }

        assertEquals(listOf("Live"), ArticleServer.recent.test(null, Unit).map { it.title })
        assertEquals(live._id, ArticleServer.cache().get<Uuid>("last-notified"))
    }
}
// endregion comparison-test
