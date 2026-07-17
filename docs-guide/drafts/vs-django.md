> # ⚠️ UNREVIEWED FIRST DRAFT — DO NOT PUBLISH
> Auto-generated first pass. Code samples are modeled on the current source but have **NOT** been compiled, drift-checked, or reviewed. This page is intentionally **not** in the site nav. Before publishing: port samples into the drift-checked `docs-guide/src/samples` module, run the fresh-eyes judge, and delete this banner.

# Lightning Server vs Django

**Who this page is for:** Python/Django developers evaluating Lightning Server, or teams deciding which framework fits
a new API project.  The goal is an honest side-by-side — not a sales pitch.

---

## Framing

Django is a batteries-included, full-stack Python framework with templates, an ORM, an admin panel, migrations, and
a vast ecosystem.  Lightning Server is a Kotlin framework for API-first backends: no templating, no migrations for the
default database, but strong compile-time guarantees and a "define once, run anywhere" deployment model.

The closest analogue in Django-land is **Django REST Framework** on top of **Django API mode** — that is roughly the
problem space Lightning Server targets.

---

## Feature Comparison

| Feature | Django (+ DRF) | Lightning Server | Notes |
|---|---|---|---|
| **Language** | Python 3 | Kotlin (JVM) | Static vs dynamic typing |
| **Models / ORM** | `class Post(models.Model)` with field descriptors | `@Serializable data class Post : HasId<Uuid>` + `@GenerateDataClassPaths` | LS uses a DSL; no SQL ORM by default |
| **Default database** | SQL (PostgreSQL recommended) | MongoDB (RAM / JSON-file for dev) | LS has partial Postgres support |
| **Migrations** | Required for every schema change | Not needed for MongoDB (schemaless) | Adding fields is zero-effort in LS |
| **Auto-admin panel** | Built-in Django Admin | Via `lightning-server-kiteui` (separate package) | Both auto-generate from model definitions |
| **Auto-CRUD REST** | Django REST Framework `ModelViewSet` | `ModelRestEndpoints` (illustrative; not yet in guide) | LS generates 8 endpoints including query/count/aggregate |
| **Auth / sessions** | `django.contrib.auth` + session middleware | `PrincipalType` + proof/session modules | LS supports email OTP, SMS PIN, password, TOTP, OAuth |
| **Typed API / serialization** | DRF `Serializer` classes (manual) | `ApiHttpHandler` with `@Serializable` types (automatic) | LS generates OpenAPI + client SDKs |
| **Middleware** | `MIDDLEWARE` list in `settings.py` | `HttpInterceptor` installed in `ServerBuilder.init {}` | Conceptually identical |
| **Background tasks** | Celery (third-party) | `Task` declared on `ServerBuilder`, `task.launch(input)` | LS tasks are first-class; AWS uses SQS automatically |
| **Scheduled jobs** | APScheduler / celery-beat (third-party) | `ScheduledTask` with `Schedule.Frequency`, `Schedule.Daily`, `Schedule.Cron` | Built-in; engine handles distributed locking |
| **WebSockets / realtime** | Django Channels (third-party) | `ApiWebsocketHandler` (built-in) | LS WebSockets are first-class endpoint citizens |
| **Typed client SDKs** | Not built-in | `FetcherSdk` (Kotlin/Multiplatform), `TypescriptFetcherSdk` | Generated from typed endpoint definitions |
| **Testing** | `django.test.TestCase` + `Client` | `ServerBuilder.testBlocking {}` + `endpoint.test(auth, input)` | LS tests use RAM services; no external infra needed |
| **Configuration** | `settings.py` (Python module) | `settings.json` (auto-generated on first run) | LS: swap implementations by changing a URL string |
| **Deployment** | WSGI/ASGI (Gunicorn, Uvicorn) | Ktor, Netty, JDK Server, or AWS Lambda | LS: same `ServerBuilder` runs everywhere |
| **Serverless / AWS** | Third-party (Zappa, Mangum) | Built-in `AwsAdapter` — generates Terraform | LS generates API Gateway + Lambda + S3 + DynamoDB |

---

## Key Concept Mappings

### Models

Django uses class-based field descriptors.  Lightning Server uses plain Kotlin data classes annotated for
serialization and compile-time query path generation:

```kotlin
// Illustrative — not drift-checked
@Serializable
@GenerateDataClassPaths
data class Post(
    override val _id: Uuid = Uuid.random(),
    val title: String,
    val author: String,
    val body: String,
) : HasId<Uuid>
```

`@GenerateDataClassPaths` is processed by KSP at build time.  It produces typed field references
(`Post.path.title`, etc.) used by `condition {}` and `modification {}`.

### Query DSL vs QuerySet API

Django's QuerySet API chains method calls on a manager.  Lightning Server's DSL uses typed lambdas:

```python
# Django
Post.objects.filter(author="user@example.com").order_by("-created_at")
```

```kotlin
// Lightning Server — illustrative
posts.find(
    condition { it.author eq "user@example.com" },
    orderBy = listOf(SortPart(Post.path.createdAt, ascending = false))
).toList()
```

Typos in field names are compile errors.  Rename `author` and every query referencing it breaks at build time, not at
runtime.

Common operator mapping:

| Django QuerySet | Lightning Server `condition {}` |
|---|---|
| `filter(field=value)` | `it.field eq value` |
| `exclude(field=value)` | `it.field neq value` |
| `filter(field__gt=value)` | `it.field gt value` |
| `filter(field__gte=value)` | `it.field gte value` |
| `filter(field__in=[...])` | `it.field inside listOf(...)` |
| `filter(field__icontains='x')` | `it.field containsIgnoreCase "x"` |
| `Q(a) \| Q(b)` | `(condA) or (condB)` |

### Signals vs Lifecycle Hooks

Django signals (`post_save`, `post_delete`) attach listeners to model events.  Lightning Server exposes hooks
directly on the `FieldCollection` (table reference):

```kotlin
// Illustrative
val posts = database().table<Post>()
    .interceptCreate { value -> value.copy(slug = slugify(value.title)) }
    .postCreate  { value -> notifySubscribers(value) }
    .postChange  { value -> invalidateCache(value._id) }
    .postDelete  { value -> cleanupFiles(value._id) }
```

Available hooks: `interceptCreate`, `interceptChange`, `postCreate`, `postChange`, `postDelete`, `postNewValue`.

### Authentication

Django's auth is session-cookie-based by default, with DRF adding token support.  Lightning Server issues bearer
tokens after a user accumulates enough **proofs** (email OTP, SMS PIN, password, TOTP, OAuth):

```kotlin
// Illustrative — define your principal type
object UserAuth : PrincipalType<User, Uuid> {
    override val idSerializer = Uuid.serializer()
    override val subjectSerializer = User.serializer()

    context(server: ServerRuntime)
    override suspend fun fetch(id: Uuid): User =
        database().table<User>().get(id) ?: throw NotFoundException()
}

// Require auth on an endpoint
val profile = path.path("profile").get bind ApiHttpHandler(
    summary = "Get current user",
    auth = UserAuth.require(),
    successCode = HttpStatus.OK,
    errorCases = emptyList(),
    implementation = { _: Unit -> auth.fetch() }
)
```

### Settings / Configuration

Django configures everything in `settings.py`.  Lightning Server declares service dependencies in code; the
implementation is selected at runtime from a URL string in `settings.json`:

```kotlin
// In your ServerBuilder
val database = setting("database", Database.Settings())
val cache    = setting("cache",    Cache.Settings())
val email    = setting("email",    Email.Settings())
```

```json
// settings.json (auto-generated on first run)
{
  "database": { "url": "mongodb://localhost:27017/myapp" },
  "cache":    { "url": "ram" },
  "email":    { "url": "console" }
}
```

Swap `ram` for `redis://...` in one line; no code changes required.

### SDK Generation

This is a Lightning Server feature that has no Django equivalent.  Every `ApiHttpHandler` participates in SDK
generation:

```kotlin
// Illustrative — from demo/main.kt pattern
FetcherSdk("com.example.api").writeUsingDefaultSettings(Server, KFile("output/sdk/kotlin"))
TypescriptFetcherSdk().writeUsingDefaultSettings(Server, KFile("output/sdk/typescript"))
```

Rename an endpoint's output field and the generated SDK breaks at compile time on the client — before deployment.

Live SDK downloads are also available at runtime via `MetaEndpoints` at `/meta/docs/sdk.ts` and `/meta/docs/sdk.kt`.

### Deployment

Django deploys behind a WSGI/ASGI server (Gunicorn, Uvicorn).  The same Lightning Server `ServerBuilder` compiles
to multiple targets without code changes:

- `KtorEngine` / `NettyEngine` / `JdkServerEngine` — traditional process-based deployment
- `AwsAdapter` — generates Terraform that provisions API Gateway, Lambda, DynamoDB, S3, and Secrets Manager from
  your declared `setting(...)` calls.  Add a `setting("files", Files.Settings())` and the S3 bucket appears in
  the generated Terraform plan.

---

## Where Each Framework Wins

**Django / DRF wins when:**

- You need server-side HTML templating, form rendering, or a full-stack web application
- Your team is in Python and benefits from its ecosystem (data science libs, etc.)
- You want a proven SQL ORM with complex join support and mature migrations
- You need the vast Django package ecosystem (django-storages, django-channels, etc.)
- Your team prefers dynamic typing and rapid prototyping without a compilation step

**Lightning Server wins when:**

- You are building a pure API backend and want end-to-end type safety (model → handler → generated SDK)
- Compile-time query validation (field renames caught before deployment) is valuable to your team
- You want background tasks and schedules built in without Celery/Redis
- You need serverless AWS deployment with auto-generated infrastructure
- Multiple deployment targets (local test, dev server, Lambda) from one definition is important
- You want real-time WebSocket endpoints as first-class citizens alongside HTTP endpoints

---

## Migration Mindset

The hardest mental shift for Django developers is not the syntax — it is moving from **runtime discovery** to
**compile-time definition**.  In Django, models register themselves, signals connect at import time, and the ORM
knows about your fields through introspection.  In Lightning Server, every endpoint, task, schedule, and service
is declared explicitly in a `ServerBuilder`; the compiler enforces it.

Concretely:

- Django's `Post.objects.filter(author='x')` is a string internally.  LS's `condition { it.author eq "x" }` is
  type-checked — rename `author` to `authorEmail` and the build fails until you update the query.
- Django migrations are required for every schema change.  With MongoDB as the backend, LS models are schemaless —
  add a nullable field, and existing documents just return `null` for it.
- Django's admin at `/admin` is always on.  LS's admin UI is in the separate `lightning-server-kiteui` package.

---

## See Also

- [Overview](../guide/overview.md) — the complete building-block inventory
- [Typed Endpoints](../guide/typed-endpoints.md) — `ApiHttpHandler`, error cases, SDK generation
- [Database](../guide/database.md) — `condition {}`, `modification {}`, model setup
- [Authentication & Sessions](../guide/auth.md) — `PrincipalType`, `testAuth`, proof system
- [Tasks](../guide/tasks.md) — `Task`, `launch()`
- [Schedules](../guide/schedules.md) — `ScheduledTask`, schedule types
