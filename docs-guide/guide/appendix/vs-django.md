# Lightning Server for Django developers

A map from Django (with Django REST Framework) concepts to their Lightning Server equivalents.  Lightning Server is
API-first: no templates or forms, but typed endpoints, generated client SDKs, and background work built in.

## Concept map

| Django / DRF | Lightning Server | See |
|---|---|---|
| `models.Model` | `@Queryable data class ... : HasId<ID>` | [Database](../database.md) |
| QuerySet (`filter`, `exclude`, `order_by`) | `Condition { }`, `SortPart` | [Database](../database.md) |
| `update()` | `Modification { }` | [Database](../database.md) |
| Migrations | None for schemaless backends; tables and indexes are prepared by pre-deploy tasks | [Advanced database](../advanced-database.md) |
| Signals (`post_save`, `post_delete`) | Table wrappers: `postCreate`, `postChange`, `postDelete`, `postNewValue`, `interceptCreate`, `interceptChange` | [Model REST](../model-rest.md) |
| `ModelViewSet` + `ModelSerializer` | `ModelRestEndpoints(modelInfo)` | [Model REST](../model-rest.md) |
| `permission_classes`, queryset filtering | `ModelPermissions` conditions per operation | [Permissions](../permissions.md) |
| `urls.py`, `path("<int:pk>/")` | `path.path("articles").arg<Uuid>("id")`, stored as `val`s | [Routing](../routing.md) |
| `reverse("name")` | Reference the endpoint `val` directly | [Routing](../routing.md) |
| Function / class views | `HttpHandler`, typed `ApiHttpHandler` | [Typed endpoints](../typed-endpoints.md) |
| Field validators | `@MaxLength`, `@ExpectedPattern`, `@IntegerRange`, ... | [Validation](../validation.md) |
| Middleware | `HttpInterceptor`, `ExecutionInterceptor` | [CORS & interceptors](../cors-interceptors.md) |
| `django.contrib.auth`, `@login_required` | `PrincipalType`, `auth = UserAuth.require()` | [Auth](../auth.md) |
| `settings.py` | `setting("name", default)` in code, values in `settings.json` | [Settings](../settings.md) |
| `DATABASES`, `CACHES` | `Database.Settings("mongodb://...")`, `Cache.Settings("redis://...")` | [Services](../services.md) |
| Django admin | `/meta/admin` from `MetaEndpoints` | [Meta & admin](../meta-admin.md) |
| Celery tasks | `Task`, `task.launch(input)` | [Tasks](../tasks.md) |
| celery-beat | `ScheduledTask` | [Schedules](../schedules.md) |
| Channels | `ApiWebsocketHandler`, topics | [WebSockets](../websockets.md) |
| `django.core.mail` | `EmailService` setting | [Email](../email.md) |
| `TestCase` + `Client` | `Server.testBlocking { }`, `endpoint.test(auth, input)` | [Testing](../testing.md) |
| Gunicorn / Uvicorn | `KtorEngine`, `NettyEngine`, `JdkEngine`, or AWS Lambda | [Engines](../engines.md) |
| drf-spectacular | `/meta/openapi`, plus generated Kotlin and TypeScript SDKs | [Client SDKs](../client-sdks.md) |

## Models

```python
class Article(models.Model):
    title = models.CharField(max_length=200)
    author = models.CharField(max_length=100)
    published = models.BooleanField(default=False)
    created_at = models.DateTimeField(auto_now_add=True)
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/ComparisonSamples.kt#comparison-model -->
```kotlin
@Queryable
data class Article(
    override val _id: Uuid = Uuid.random(),
    @MaxLength(200) val title: String,
    val author: String,
    val published: Boolean = false,
    val createdAt: Instant = Clock.System.now(),
) : HasId<Uuid>
```

`@Queryable` makes the class serializable and generates typed fields (`Article.createdAt`) for queries.  A renamed
field breaks every query that uses it at compile time.

## Views, viewsets, signals, Celery

The whole Django app (a `ModelViewSet`, two custom views, a `post_save` signal that queues a Celery task, and a
beat schedule) is one `ServerBuilder`:

<!-- sample: com/lightningkite/lightningserver/guide/samples/ComparisonSamples.kt#comparison-server -->
```kotlin
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
```

What maps to what:

- `ModelRestEndpoints` is the viewset: list, query, detail, insert, replace, modify, delete, count and aggregate
  endpoints under `/articles`.  `ModelPermissions` replaces both `permission_classes` and `get_queryset()`: here
  anyone may read published articles, and every other operation defaults to `Condition.Never`.
- `signals` is where table hooks go, so they run for REST and server code alike.
- `notifyFollowers.launch(...)` is `delay()`: it runs in the background locally and on its own Lambda invocation on
  AWS.  Its input must be serializable.
- `ScheduledTask` is a beat entry; the engine ensures one instance runs it.

## QuerySets

| Django | Lightning Server |
|---|---|
| `filter(author="ana")` | `Condition { it.author eq "ana" }` |
| `exclude(author="ana")` | `Condition { it.author neq "ana" }` |
| `filter(views__gt=10)` / `__gte` / `__lt` / `__lte` | `it.views gt 10` / `gte` / `lt` / `lte` |
| `filter(author__in=[...])` | `it.author inside listOf(...)` |
| `filter(title__contains="x")` | `it.title contains "x"` |
| `filter(title__icontains="x")` | `it.title.contains("x", ignoreCase = true)` |
| `Q(a) \| Q(b)`, `Q(a) & Q(b)` | `a or b`, `a and b` |
| `order_by("-created_at")` | `orderBy = listOf(SortPart(Article.createdAt, ascending = false))` |
| `.all()` | `Condition.Always` |
| `.update(title="x")` | `updateMany(condition, Modification { it.title assign "x" })` |
| `.delete()` | `deleteMany(condition)` |

## Tests

```python
class ArticleTests(TestCase):
    def test_recent(self):
        Article.objects.create(title="Draft", author="ana")
        Article.objects.create(title="Live", author="ana", published=True)
        response = self.client.get("/recent-articles")
        self.assertEqual([a["title"] for a in response.json()], ["Live"])
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/ComparisonSamples.kt#comparison-test -->
```kotlin
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
```

Tests run in-process against in-memory services, with no database or HTTP server.  Tasks run inline, so the
signal's effect is visible immediately.

## Differences that matter

- **Explicit registration.** No app discovery or import-time registration: an endpoint exists because a
  `ServerBuilder` binds it, and its `val` is how tests and other code refer to it.
- **Services are settings.** The database, cache, email and file storage are chosen by URL in `settings.json`
  (`ram` and `json` files for development, MongoDB or PostgreSQL in production) with no code change.
- **No templates or forms.** Render HTML in a client; KiteUI (with `lightning-server-kiteui`) is the companion.
- **One definition, several targets.** The same server runs in tests, on a JVM host, or on AWS Lambda with
  generated Terraform.  See [AWS deployment](../aws-deployment.md).
