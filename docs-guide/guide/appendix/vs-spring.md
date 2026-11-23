# Lightning Server for Spring Boot developers

A map from Spring Boot concepts to their Lightning Server equivalents.  Both run on the JVM; the main shift is
from annotations discovered at runtime to Kotlin values you register explicitly.  There is no classpath scanning
and no DI container: if it isn't bound in a `ServerBuilder`, it doesn't exist.

## Concept map

| Spring Boot | Lightning Server | See |
|---|---|---|
| `@RestController`, `@GetMapping("/{id}")` | `path.path("articles").arg<Uuid>("id").get bind ApiHttpHandler(...)`, stored as a `val` | [Routing](../routing.md), [Typed endpoints](../typed-endpoints.md) |
| `@PathVariable`, `@RequestBody` | `route.arg1`, the handler's typed `input` | [Typed endpoints](../typed-endpoints.md) |
| `@Autowired` services | Settings invoked where used: `database()`, `cache()` | [Services](../services.md) |
| `@ConfigurationProperties`, `application.yml` | `setting("name", default)`, values in `settings.json` | [Settings](../settings.md) |
| Profiles | A different `settings.json` per environment | [Settings](../settings.md) |
| `@ConditionalOnProperty` | Plain Kotlin `if` around a registration | |
| `@Entity` | `@Queryable data class ... : HasId<ID>` | [Database](../database.md) |
| `JpaRepository`, derived queries | The table itself: `find(Condition { })`, `updateOne`, `deleteMany` | [Database](../database.md) |
| Flyway / Liquibase | None for schemaless backends; tables and indexes are prepared by pre-deploy tasks | [Advanced database](../advanced-database.md) |
| JPA entity listeners | Table wrappers in `modelInfo(signals = ...)`: `postCreate`, `postChange`, `postDelete`, ... | [Model REST](../model-rest.md) |
| Spring Data REST | `ModelRestEndpoints(modelInfo)` | [Model REST](../model-rest.md) |
| Bean Validation (`@Size`, `@Pattern`) | `@MaxLength`, `@ExpectedPattern`, `@IntegerRange`, ... | [Validation](../validation.md) |
| `@ControllerAdvice`, `ResponseStatusException` | Throw `NotFoundException`, `BadRequestException`, ...; clients get an `LSError` | [Error handling](../error-handling.md) |
| Filters, `HandlerInterceptor` | `HttpInterceptor`, `ExecutionInterceptor` | [CORS & interceptors](../cors-interceptors.md) |
| Spring Security filter chain | `auth = UserAuth.require()` per endpoint, `ModelPermissions` per model | [Auth](../auth.md), [Permissions](../permissions.md) |
| `@Cacheable` | Explicit `cache().get(key)` / `cache().set(key, value, ttl)` | [Caching](../caching.md) |
| `@Async` | `Task`, `task.launch(input)` | [Tasks](../tasks.md) |
| `@Scheduled` | `ScheduledTask` | [Schedules](../schedules.md) |
| `CommandLineRunner` | `StartupTask` (per instance) or `PreDeployTask` (once per deploy) | [Tasks](../tasks.md) |
| Spring WebSocket / STOMP | `ApiWebsocketHandler`, topics | [WebSockets](../websockets.md) |
| Actuator | `MetaEndpoints`: `/meta/health`, `/meta/online`, `/meta/openapi` | [Meta & admin](../meta-admin.md) |
| SpringDoc, OpenAPI Generator | `/meta/openapi`, `FetcherSdk` (Kotlin) and `TypescriptFetcherSdk` | [Client SDKs](../client-sdks.md) |
| Micrometer, tracing | Built-in OpenTelemetry spans and metrics | [Observability](../observability.md) |
| `@SpringBootTest`, `MockMvc`, `@MockBean` | `Server.testBlocking { }` with in-memory services | [Testing](../testing.md) |
| Embedded Tomcat | `KtorEngine`, `NettyEngine`, `JdkEngine`, or AWS Lambda | [Engines](../engines.md) |

## Entities

```java
@Entity
public class Article {
    @Id @GeneratedValue private UUID id;
    @Size(max = 200) private String title;
    private String author;
    private boolean published = false;
    private Instant createdAt = Instant.now();
}
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

`@Queryable` is processed at compile time (KSP), generating typed fields such as `Article.createdAt`.  There are no
derived query names to get wrong.

## Controllers, repositories, async, scheduling

The Spring version would be an entity, a `JpaRepository`, a `@RepositoryRestResource`, a controller with two
methods, an entity listener calling an `@Async` service, and a `@Scheduled` component.  Here it is one object:

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

- `database()` and `cache()` replace injection.  Each is a setting resolved at startup from `settings.json`, and
  invoking it inside a handler returns the live service.
- `ModelRestEndpoints` is Spring Data REST with permissions built in: anyone may read published articles, and
  create, update and delete default to `Condition.Never`.
- `notifyFollowers.launch(...)` is the `@Async` call, but its input is serialized, so on AWS it runs as its own
  Lambda invocation instead of on a thread pool.
- `ScheduledTask` is `@Scheduled`; with several instances, the engine ensures only one runs each tick.
- Every `ApiHttpHandler` feeds `/meta/openapi` and the generated client SDKs; no annotations needed.

## Tests

```java
@SpringBootTest @AutoConfigureMockMvc
class ArticleTest {
    @Autowired MockMvc mvc;
    @Autowired ArticleRepository repo;

    @Test void recentListsOnlyPublished() throws Exception {
        repo.save(new Article("Draft", "ana", false));
        repo.save(new Article("Live", "ana", true));
        mvc.perform(get("/recent-articles")).andExpect(jsonPath("$[*].title").value(contains("Live")));
    }
}
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

No application context to start and nothing to mock: the `ram` database is the test double, and
`ArticleServer.recent.test(...)` returns the typed output directly.

## Differences that matter

- **Explicit over discovered.** Registration order and wiring are visible in code; there's no "why didn't Spring
  pick this up?".  The cost is that you write the registrations yourself.
- **Backends by URL.** The database, cache, email, files and more are chosen in `settings.json` (`ram` for
  development, MongoDB or PostgreSQL, Redis, S3, ... in production).
- **Kotlin only**, with Kotlin Multiplatform models shared with clients.
- **Smaller ecosystem.** Spring has an integration for almost everything; Lightning Server covers the common
  services through Service Abstractions and leaves the rest to plain Kotlin libraries.
- **Deploy targets.** The same definition runs on a JVM host or on AWS Lambda with generated Terraform.  See
  [AWS deployment](../aws-deployment.md).
