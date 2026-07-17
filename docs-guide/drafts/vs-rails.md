> # ⚠️ UNREVIEWED FIRST DRAFT — DO NOT PUBLISH
> Auto-generated first pass. Code samples are modeled on the current source but have **NOT** been compiled, drift-checked, or reviewed. This page is intentionally **not** in the site nav. Before publishing: port samples into the drift-checked `docs-guide/src/samples` module, run the fresh-eyes judge, and delete this banner.

# Lightning Server vs Ruby on Rails

**Who this page is for:** Rails developers evaluating Lightning Server, or teams choosing between the two for a new
API project.

---

## Framing

Rails is famous for its "convention over configuration" philosophy and scaffolding-driven workflow.  Lightning Server
takes the opposite stance on conventions: it is **explicit over implicit**.  There is no class-path scanning, no
magic `before_action` inheritance chain, and no generator that writes code for you — but there is also nothing hidden
from the compiler.

The closest Rails mode is **`rails new --api`**.  Think of Lightning Server as "Rails API mode, but Kotlin, with
compile-time field-access safety and zero-config serverless deployment."

---

## Feature Comparison

| Feature | Ruby on Rails (API mode) | Lightning Server | Notes |
|---|---|---|---|
| **Language** | Ruby (interpreted, dynamic) | Kotlin (compiled, static) | LS catches field-rename bugs at build time |
| **Models** | `class Post < ApplicationRecord` (ActiveRecord) | `@Serializable data class Post : HasId<Uuid>` + DSL | LS has no SQL ORM by default |
| **Default database** | SQL (SQLite → PostgreSQL) | MongoDB (RAM / JSON-file for dev) | |
| **Migrations** | Required for all schema changes | Not needed for MongoDB | Adding fields to LS models is zero-effort |
| **Scaffolding** | `rails generate scaffold Post title:string` | `ModelRestEndpoints` wired to a `ServerBuilder` (illustrative) | LS: no generated code; endpoints are declared, not generated |
| **Auto-admin panel** | Rails Admin / ActiveAdmin (gems) | `lightning-server-kiteui` (separate package) | Both require adding a dependency |
| **Serialization** | `jbuilder`, `fast_jsonapi`, or DRF | `@Serializable` data classes (kotlinx.serialization) | LS serialization is compile-time checked |
| **Auth / sessions** | Devise (gem) | `PrincipalType` + proof/session modules (built-in) | LS: email OTP, SMS PIN, password, TOTP, OAuth |
| **Typed API docs** | Grape-Swagger, rswag (gems) | `ApiHttpHandler` → OpenAPI spec auto-generated | LS: summary + errorCases declared alongside the handler |
| **Typed client SDKs** | Not built-in | `FetcherSdk` (Kotlin/Multiplatform), `TypescriptFetcherSdk` | Generated from `ApiHttpHandler` definitions |
| **Background jobs** | Active Job + Sidekiq / Resque | `Task` declared on `ServerBuilder`, launched via `task.launch(input)` | LS tasks serialize input; AWS deployment uses SQS |
| **Scheduled jobs** | Whenever gem / Sidekiq-Cron | `ScheduledTask` (built-in) | LS engine handles distributed locking |
| **WebSockets** | Action Cable | `ApiWebsocketHandler` (built-in) | LS WebSockets are first-class endpoints |
| **Caching** | Rails cache API | `cache()` service (explicit calls) | LS caching is explicit, not annotation-driven |
| **Middleware** | `ActionDispatch` middleware stack | `HttpInterceptor` installed in `ServerBuilder.init {}` | |
| **Testing** | RSpec / Minitest + fixtures | `ServerBuilder.testBlocking {}` + RAM services | LS: no external infra needed for tests |
| **Deployment** | Puma + traditional VM/container | Ktor, Netty, JDK Server, or AWS Lambda | LS: same definition → multiple targets |
| **Serverless** | Third-party adapters | Built-in `AwsAdapter` — generates Terraform | LS auto-provisions API Gateway + Lambda + S3 |

---

## Key Concept Mappings

### Models and the Database

Rails ActiveRecord defines fields implicitly from the database schema, with validations and scopes on the class.
Lightning Server models are plain data classes; validations are annotations; and "scopes" are extension functions:

```ruby
# Rails
class Post < ApplicationRecord
  validates :title, presence: true, length: { maximum: 100 }
  scope :published, -> { where(published: true) }
end
```

```kotlin
// Lightning Server — illustrative
@Serializable
@GenerateDataClassPaths
data class Post(
    override val _id: Uuid = Uuid.random(),
    @MaxLength(100) val title: String,
    val published: Boolean = false,
    val createdAt: Instant = Clock.System.now(),
) : HasId<Uuid>

// "Scope" as an extension function
fun FieldCollection<Post>.published() = find(condition { it.published eq true })
```

`@GenerateDataClassPaths` is processed by KSP at build time.  It produces typed field references
used by `condition {}` and `modification {}`.  A typo like `it.titel` is a compile error, not a runtime bug.

### Migrations

Rails schema migrations track every change.  With MongoDB as the default backend, Lightning Server models
are **schemaless**: add a nullable field to your data class and existing documents simply return `null` for it.
No migration file required.

For data transforms on existing rows (what Rails calls data migrations), Lightning Server uses a one-off `Task`
or a `ScheduledTask` that detects the unfilled field and backfills it, then disables itself.

### Scaffolding → Explicit Declarations

`rails generate scaffold Post` writes controller, model, migration, routes, views, and test files.  Lightning
Server has no generator — but also no files to maintain.  You declare a `ServerBuilder` with a `ModelRestEndpoints`
block and the endpoints are live:

```kotlin
// Illustrative — ModelRestEndpoints wiring pattern from old docs; not yet in the guide
object PostApi : ServerBuilder() {
    val database = setting("database", Database.Settings())

    val posts = path.path("posts") include ModelRestEndpoints(
        database.modelInfo(auth = UserAuth.require(), permissions = { /* ... */ })
    )
}
```

This exposes GET/POST/PUT/PATCH/DELETE plus query, count, and aggregate endpoints — all type-checked.

### Model Callbacks

Rails `before_create`, `after_update`, etc. are methods on the model class.  Lightning Server attaches hooks to the
`FieldCollection` (the table accessor), keeping the model class a plain data structure:

```ruby
# Rails
class Post < ApplicationRecord
  before_create :set_slug
  after_create  :notify_subscribers
  after_update  :clear_cache
  before_destroy :cleanup_files
end
```

```kotlin
// Lightning Server — illustrative
val posts = database().table<Post>()
    .interceptCreate { value -> value.copy(slug = slugify(value.title)) }
    .postCreate  { value -> sendNotification(value) }
    .postChange  { value -> invalidateCache(value._id) }
    .postDelete  { value -> cleanupFiles(value._id) }
```

Available hooks: `interceptCreate`, `interceptChange`, `postCreate`, `postChange`, `postDelete`, `postNewValue`.

### Background Jobs

Rails Active Job + Sidekiq/Resque:

```ruby
class WelcomeEmailJob < ApplicationJob
  def perform(user_id)
    user = User.find(user_id)
    UserMailer.welcome(user).deliver_now
  end
end
WelcomeEmailJob.perform_later(user.id)
```

Lightning Server `Task`:

```kotlin
// Illustrative
val sendWelcomeEmail = path.path("tasks").path("welcome-email") bind Task { input: WelcomeEmailInput ->
    val user = database().table<User>().get(input.userId) ?: return@Task
    email().send(Email(subject = "Welcome!", to = listOf(input.address), html = "<h1>Hi ${user.name}!</h1>"))
}

// Launch from any handler
sendWelcomeEmail.launch(WelcomeEmailInput(userId = user._id, address = user.email))
```

`Task` input is `@Serializable` — in a Lambda deployment the engine serializes it to SQS automatically.  In a
local test `LocalEngine` runs it synchronously, so effects are immediately observable.

### Scheduled Jobs

Rails requires a gem (whenever, Sidekiq-Cron):

```ruby
# config/schedule.rb (whenever)
every 15.minutes { runner "CleanupJob.perform" }
every 1.day, at: '3:00 am' { runner "DailyReport.generate" }
```

Lightning Server `ScheduledTask` is built in, with distributed locking handled by the engine:

```kotlin
// Illustrative
val cleanup = path.path("schedules").path("cleanup") bind ScheduledTask(frequency = 15.minutes) {
    deleteExpiredSessions()
}

val dailyReport = path.path("schedules").path("report") bind ScheduledTask(
    schedule = Schedule.Daily(LocalTime(3, 0), TimeZone.of("America/Denver"))
) {
    generateAndEmailReport()
}

val complexSchedule = path.path("schedules").path("business-hours") bind ScheduledTask(
    schedule = Schedule.Cron(
        CronPattern(minutes = listOf(0), hours = listOf(9, 17),
                    days = CronDays.DaysOfWeek(DayOfWeek.MONDAY..DayOfWeek.FRIDAY)),
        TimeZone.of("America/New_York")
    )
) { checkBusinessMetrics() }
```

### Authentication

Devise gives you session-cookie auth with a dozen strategies.  Lightning Server's auth is JWT-based and built
around **proofs** — signed, time-limited assertions (email OTP, SMS PIN, password, TOTP, OAuth token).  A user
accumulates proofs until a per-user strength threshold is met, then a session token is issued.

```kotlin
// Illustrative — defining a principal type
object UserAuth : PrincipalType<User, Uuid> {
    override val idSerializer = Uuid.serializer()
    override val subjectSerializer = User.serializer()

    context(server: ServerRuntime)
    override suspend fun fetch(id: Uuid): User =
        database().table<User>().get(id) ?: throw NotFoundException()
}

// Requiring auth on an endpoint
val profile = path.path("profile").get bind ApiHttpHandler(
    summary = "Get current user",
    auth = UserAuth.require(),
    successCode = HttpStatus.OK,
    errorCases = emptyList(),
    implementation = { _: Unit -> auth.fetch() }
)
```

### Environment Configuration

Rails uses `config/environments/*.rb` and `database.yml`.  Lightning Server uses a `settings.json` whose URL
scheme selects the backend implementation:

```json
// Development
{ "database": { "url": "ram" }, "cache": { "url": "ram" }, "email": { "url": "console" } }

// Production
{ "database": { "url": "mongodb+srv://..." }, "cache": { "url": "redis://..." }, "email": { "url": "smtp://..." } }
```

On first run the framework writes `settings.json` with working defaults.  Change the URL to switch backends;
no code changes required.

### Testing

Rails `rspec-rails` mounts the app with `let(:headers)` and fixture data.  Lightning Server tests spin up a
RAM-backed server in-process with no ports or external infrastructure:

```kotlin
// Illustrative — core pattern from guide/testing.md
@Test
fun testGetProfile() = UserProfileServer.testBlocking(settings = { database set Database.Settings("ram") }) {
    val alice = UserProfileServer.database().table<User>()
        .insertOne(User(name = "Alice", email = "alice@example.com"))
    val auth = UserAuth.testAuth(alice)
    val result = UserProfileServer.getProfile.test(auth, Unit)
    check(result.name == "Alice")
}
```

`testAuth` creates a synthetic `Authentication<User>` without hitting any auth endpoint.

### The Rails Console

`rails console` gives you a live REPL against the running app.  Lightning Server does not ship an equivalent.
Alternatives:

1. **IntelliJ debugger "Evaluate Expression"** — set a breakpoint, evaluate arbitrary Kotlin against live state
2. **Auto-generated admin UI** — the `lightning-server-kiteui` package exposes a browser-based data management UI
3. **Custom CLI subcommand** — add a `when ("seed") -> seedDatabase()` branch in `main()` (see [Running Your Server](../guide/running.md))

### Rake Tasks → CLI Subcommands

Rails `rake db:seed` pattern:

```kotlin
// Illustrative — demo/main.kt pattern
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "serve" -> serve()
        "sdk"   -> generateSdk()
        "seed"  -> seedDatabase()
        else    -> println("Usage: serve | sdk | seed")
    }
}
```

---

## Where Each Framework Wins

**Rails wins when:**

- Convention over configuration is your team's preference — less explicit code to write
- You need a mature SQL ORM with joins, eager loading, and migration tooling
- Your stack includes server-rendered HTML, Turbo Streams, or Action Cable for full-stack features
- The Ruby gem ecosystem has something you need (Devise, Pundit, etc.)
- Rapid prototyping speed matters more than compile-time guarantees
- You want `rails console` for live data inspection

**Lightning Server wins when:**

- End-to-end type safety — from the model field to the generated TypeScript SDK — is a priority
- You want background tasks and scheduled jobs without Sidekiq, Redis, and separate worker processes
- Serverless deployment (AWS Lambda) with auto-generated Terraform is a goal
- WebSocket endpoints alongside HTTP endpoints — first-class, not a separate framework layer
- The same `ServerBuilder` running in a JUnit test (no ports, no infrastructure) would save your team time
- Kotlin Multiplatform shared models between server and client are appealing

---

## Migration Mindset

The biggest adjustment for Rails developers is moving from **convention-driven** to **explicitly-declared**.

In Rails, `class Post < ApplicationRecord` automatically knows its columns from the schema, callbacks are inherited,
routes are generated from `resources :posts`, and Devise wires up auth with a line in `routes.rb`.

In Lightning Server, each of those things is a named declaration in a `ServerBuilder`:

- Endpoints are stored as `val` properties — reference them by name in tests, link them from other endpoints
- Tasks and schedules live alongside endpoints in the same object, not in separate `app/jobs/` files
- Auth requirements are declared per-endpoint, not set globally in a `before_action` chain
- Services (database, cache, email) are `setting(...)` calls resolved from `settings.json`

The trade-off: more lines of explicit Kotlin, but the compiler enforces every connection.  Rename a model field and
every query, every endpoint input type, and every generated SDK call that referenced it breaks at build time.

---

## See Also

- [Overview](../guide/overview.md) — complete building-block inventory
- [Typed Endpoints](../guide/typed-endpoints.md) — `ApiHttpHandler`, error cases, SDK generation
- [Database](../guide/database.md) — `condition {}`, `modification {}`, model setup
- [Authentication & Sessions](../guide/auth.md) — `PrincipalType`, proofs, `testAuth`
- [Tasks](../guide/tasks.md) — `Task`, `launch()`
- [Schedules](../guide/schedules.md) — `ScheduledTask`, schedule types
- [AWS Deployment](../guide/aws-deployment.md) — Terraform generation from `ServerBuilder`
