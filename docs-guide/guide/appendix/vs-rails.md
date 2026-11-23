# Lightning Server for Rails developers

A map from Rails (API mode) concepts to their Lightning Server equivalents.  The main shift: Rails is convention
over configuration; Lightning Server is explicit declarations the compiler checks.  Nothing is found by naming
convention or generated into your source tree.

## Concept map

| Rails | Lightning Server | See |
|---|---|---|
| `ApplicationRecord` model | `@Queryable data class ... : HasId<ID>` | [Database](../database.md) |
| `where`, `order`, scopes | `Condition { }`, `SortPart`; a scope is a plain function returning a `Condition` | [Database](../database.md) |
| `update_all` | `updateMany(condition, Modification { })` | [Database](../database.md) |
| Migrations | None for schemaless backends; tables and indexes are prepared by pre-deploy tasks.  Data backfills are a `PreDeployTask` | [Advanced database](../advanced-database.md) |
| `validates` | `@MaxLength`, `@ExpectedPattern`, `@IntegerRange`, `@FloatRange`, `@MaxSize` | [Validation](../validation.md) |
| Callbacks (`after_create`, ...) | Table wrappers in `modelInfo(signals = ...)`: `postCreate`, `postChange`, `postDelete`, `interceptCreate`, `interceptChange` | [Model REST](../model-rest.md) |
| `resources :articles` + scaffold controller | `ModelRestEndpoints(modelInfo)` | [Model REST](../model-rest.md) |
| Pundit / CanCanCan policies | `ModelPermissions` conditions per operation | [Permissions](../permissions.md) |
| `routes.rb`, `get "/articles/:id"` | `path.path("articles").arg<Uuid>("id").get bind ...` | [Routing](../routing.md) |
| Controllers, jbuilder | `ApiHttpHandler` with `@Serializable` input and output | [Typed endpoints](../typed-endpoints.md) |
| Rack middleware | `HttpInterceptor`, `ExecutionInterceptor` | [CORS & interceptors](../cors-interceptors.md) |
| Devise | `PrincipalType` plus proof and session modules | [Auth](../auth.md), [Proofs & sessions](../proof-session.md) |
| `config/environments`, `database.yml`, credentials | `settings.json`, generated with working defaults on first run | [Settings](../settings.md) |
| `Rails.cache` | `cache()` from a `Cache.Settings` setting | [Caching](../caching.md) |
| Active Job + Sidekiq | `Task`, `task.launch(input)` | [Tasks](../tasks.md) |
| whenever / sidekiq-cron | `ScheduledTask` | [Schedules](../schedules.md) |
| Action Cable | `ApiWebsocketHandler`, topics | [WebSockets](../websockets.md) |
| Action Mailer | `EmailService` setting | [Email](../email.md) |
| Active Storage | `ExternalFileSystem` setting and `ServerFile` | [Files](../files.md) |
| RSpec request specs | `Server.testBlocking { }`, `endpoint.test(auth, input)` | [Testing](../testing.md) |
| FactoryBot | Data class default parameters: `Article(title = "x", author = "ana")` | |
| `rails console` | No equivalent; use the admin at `/meta/admin` or a debugger | [Meta & admin](../meta-admin.md) |
| Rake tasks | Subcommands of your `main()` (`serve`, `predeploy`, `sdk`, ...) | [Running](../running.md) |
| Puma | `KtorEngine`, `NettyEngine`, `JdkEngine`, or AWS Lambda | [Engines](../engines.md) |

## Models

```ruby
class Article < ApplicationRecord
  validates :title, length: { maximum: 200 }
  scope :published, -> { where(published: true) }
end
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

Fields come from the class, not the database schema.  `@Queryable` generates typed fields (`Article.createdAt`)
for queries, so `it.titel` is a compile error rather than a runtime one.

## Controllers, callbacks, jobs

The Rails version: `resources :articles`, two extra controller actions, an `after_create` callback enqueueing an
Active Job, and a cron entry.  In Lightning Server it is one `ServerBuilder`:

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

- `ModelRestEndpoints` is the scaffolded controller, without generated files to maintain.  `ModelPermissions`
  is the policy: anyone may read published articles; create, update and delete default to `Condition.Never`.
- Callbacks live in `signals`, on the table rather than on the model, so the model stays a plain data class.
- `notifyFollowers.launch(...)` is `perform_later`.  No separate worker process: the engine runs tasks in the
  background locally, and as separate Lambda invocations on AWS.
- `route.arg1` is the `:author` segment, typed by `arg<String>`.

## Tests

```ruby
RSpec.describe "recent articles", type: :request do
  it "lists only published" do
    Article.create!(title: "Draft", author: "ana")
    Article.create!(title: "Live", author: "ana", published: true)
    get "/recent-articles"
    expect(JSON.parse(response.body).map { _1["title"] }).to eq(["Live"])
  end
end
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

The in-memory `ram` database needs no fixtures or cleanup; each `testBlocking` starts empty.  Tasks run inline, so
the callback's job has already run when `insertOne` returns.

## Differences that matter

- **No generators.** Endpoints, tasks and schedules are values in a `ServerBuilder`; tests reference them by name.
- **Settings select implementations.** `"database": {"url": "ram"}` in development, a MongoDB or PostgreSQL URL in
  production; the same goes for cache, email and files.
- **API only.** No views or asset pipeline; pair it with a client such as KiteUI.
- **Deploy targets.** The same definition runs on a JVM host or on AWS Lambda with generated Terraform.  See
  [AWS deployment](../aws-deployment.md).
