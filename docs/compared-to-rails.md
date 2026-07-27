# Lightning Server for Ruby on Rails Developers

<!-- by Claude -->

Last updated January 2025 (`version-5`)

Welcome! If you're coming from Ruby on Rails, you already have excellent instincts for building web applications. This
guide maps your Rails knowledge to Lightning Server concepts, helping you get productive quickly.

## Philosophy Differences

Before diving into specifics, it's worth understanding how Lightning Server's philosophy differs from Rails:

| Rails                                      | Lightning Server                              |
|--------------------------------------------|-----------------------------------------------|
| Full-stack framework (views, assets, etc.) | API-focused backend framework                 |
| Convention over configuration              | Explicit configuration with sensible defaults |
| Ruby (interpreted, dynamic typing)         | Kotlin (compiled, static typing)              |
| ActiveRecord ORM                           | Type-safe query DSL                           |
| Code generation (scaffolding)              | Runtime-generated admin UI                    |
| Migration-based schema changes             | Schemaless (MongoDB) or additive changes      |

Lightning Server is designed for modern API-first architectures where your frontend (React, mobile apps, etc.) handles
presentation. Think of it as "Rails API mode" but with compile-time safety.

## Quick Reference Table

| Rails Concept             | Lightning Server Equivalent                   |
|---------------------------|-----------------------------------------------|
| `rails console`           | IntelliJ debugger + auto-generated admin UI   |
| `rails generate scaffold` | `ModelRestEndpoints` + runtime admin UI       |
| ActiveRecord validations  | `@MaxLength`, `@IntegerRange`, etc.           |
| ActiveRecord callbacks    | `interceptCreate`, `postChange`, etc.         |
| Scopes                    | Kotlin extension functions                    |
| Active Job                | `Schedule` (recurring) + `task` (async)       |
| Action Mailer             | Email service abstraction                     |
| Fixtures/factories        | Data class default parameters                 |
| `config/environments/`    | URL-based settings (`ram://` vs `mongodb://`) |
| Rake tasks                | CLI commands via kotlinercli                  |
| Asset pipeline            | Not applicable (API-focused)                  |
| Database migrations       | Not needed (see below)                        |

## Detailed Comparisons

### Models and Database Access

**Rails ActiveRecord:**

```ruby
class Post < ApplicationRecord
  belongs_to :author, class_name: 'User'
  validates :title, presence: true, length: { maximum: 100 }

  scope :published, -> { where(published: true) }
  scope :recent, -> { order(created_at: :desc).limit(10) }
end

Post.where(author: current_user).published.recent
```

**Lightning Server:**

```kotlin
@Serializable
@GenerateDataClassPaths
data class Post(
    override val _id: Uuid = Uuid.random(),
    @MaxLength(100) val title: String,
    val authorId: Uuid,
    val published: Boolean = false,
    val createdAt: Instant = Clock.System.now()
) : HasId<Uuid>

// Scopes as extension functions
fun FieldCollection<Post>.published() = find(condition { it.published eq true })
fun Query<Post>.recent() = sort(Post::createdAt.descending()).take(10)

// Usage
posts.find(condition { it.authorId eq currentUser._id }).published().recent()
```

The `@GenerateDataClassPaths` annotation enables the type-safe query DSL. Typos in field names become compile errors,
not runtime bugs.

### Database Migrations

**Rails approach:** Schema migrations track every change to your database structure.

**Lightning Server approach:** No migrations needed because:

1. **MongoDB is schemaless** - Add fields to your Kotlin model, and they just work
2. **Postgres changes are typically additive** - New nullable columns don't require migration files
3. **Data migrations** - When you need to transform existing data, use scheduled tasks:

```kotlin
// One-time data migration as a scheduled task
val migrateData = schedule("migrate-posts-2025-01", Schedule.Frequency(Duration.INFINITE)) {
    posts.updateMany(
        condition { it.newField eq null },
        modification { it.newField assign "default" }
    )
}
```

### Validations

**Rails:**

```ruby
class User < ApplicationRecord
  validates :email, format: { with: URI::MailTo::EMAIL_REGEXP }
  validates :name, length: { maximum: 100 }
  validates :age, numericality: { greater_than: 0, less_than: 150 }
end
```

**Lightning Server (via service-abstractions):**

```kotlin
@Serializable
data class User(
    @ExpectedPattern("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
    val email: String,
    @MaxLength(100) val name: String,
    @IntegerRange(min = 1, max = 149) val age: Int
)
```

Available validation annotations:

- `@MaxLength(size)` - Maximum string length
- `@MaxSize(size)` - Maximum collection size
- `@ExpectedPattern(regex)` - Regex pattern matching
- `@IntegerRange(min, max)` - Integer bounds
- `@FloatRange(min, max)` - Float bounds

Typed endpoints validate input automatically and return appropriate error responses.

### Model Callbacks

**Rails:**

```ruby
class Post < ApplicationRecord
  before_create :set_defaults
  after_create :notify_subscribers
  before_destroy :cleanup_attachments
  after_update :clear_cache
end
```

**Lightning Server:**

```kotlin
val posts = database().table(postTable)
    .interceptCreate { value ->
        // Modify before insertion (like before_create)
        value.copy(slug = value.title.slugify())
    }
    .postCreate { value ->
        // After successful insertion
        notificationService.notifySubscribers(value)
    }
    .postChange { value ->
        // After successful update
        cache.invalidate("post:${value._id}")
    }
    .postDelete { value ->
        // After successful deletion
        fileStorage.deleteAttachments(value._id)
    }
```

Available lifecycle hooks:

- `interceptCreate` - Modify value before creation
- `interceptChange` - Modify the modification before application
- `postCreate` - After successful creation
- `postChange` - After successful update
- `postDelete` - After successful deletion
- `postNewValue` - After creation or update

### Scaffolding and Admin

**Rails:** `rails generate scaffold Post title:string body:text`

**Lightning Server:** No code generation needed. Define your model and use `ModelRestEndpoints`:

```kotlin
@Serializable
@GenerateDataClassPaths
@AdminTableColumns(["title", "author", "createdAt"])
data class Post(
    override val _id: Uuid = Uuid.random(),
    val title: String,
    val author: String,
    @Multiline val body: String,
    val createdAt: Instant = Clock.System.now()
) : HasId<Uuid>

object Server : ServerBuilder() {
    val database = setting("database", Database.Settings())

    val posts = path.path("posts") include object : ServerBuilder() {
        val info = database.modelInfo(
            auth = UserAuth.require(),
            permissions = { /* permission rules */ }
        )
        val rest = path.path("rest") module ModelRestEndpoints(info)
    }
}
```

This generates:

- `GET /posts/rest` - List with filtering, sorting, pagination
- `POST /posts/rest` - Create
- `GET /posts/rest/{id}` - Read
- `PATCH /posts/rest/{id}` - Update
- `DELETE /posts/rest/{id}` - Delete
- `POST /posts/rest/query` - Advanced queries
- `POST /posts/rest/count` - Count matching records

The [lightning-server-kiteui](https://github.com/lightningkite/lightning-server-kiteui) package provides an
auto-generated admin UI at runtime, similar to Rails Admin but without any additional configuration.

### Background Jobs

**Rails Active Job:**

```ruby
class SendWelcomeEmailJob < ApplicationJob
  queue_as :default

  def perform(user)
    UserMailer.welcome(user).deliver_now
  end
end

# Enqueue
SendWelcomeEmailJob.perform_later(user)
```

**Lightning Server async tasks:**

```kotlin
val sendWelcomeEmail = task("send-welcome-email") { userId: Uuid ->
    val user = users.findOne(condition { it._id eq userId })!!
    email().send(Email(
        subject = "Welcome!",
        to = listOf(EmailAddressWithName(user.email.toEmailAddress())),
        html = "<h1>Welcome, ${user.name}!</h1>"
    ))
}

// Enqueue (call it like a function)
sendWelcomeEmail(user._id)
```

Tasks automatically integrate with AWS SQS when deployed to Lambda.

### Scheduled Tasks (Cron Jobs)

**Rails (with whenever gem):**

```ruby
# schedule.rb
every 15.minutes do
  runner "CleanupJob.perform"
end

every 1.day, at: '3:00 am' do
  runner "DailyReport.generate"
end
```

**Lightning Server:**

```kotlin
// Every 15 minutes
val cleanup = schedule("cleanup", Schedule.Frequency(15.minutes)) {
    expiredSessions.deleteMany(condition { it.expiresAt lt Clock.System.now() })
}

// Daily at 3 AM in a specific timezone
val dailyReport = schedule("daily-report", Schedule.Daily(
    time = LocalTime(3, 0),
    zone = TimeZone.of("America/Denver")
)) {
    generateAndSendReport()
}

// Complex cron pattern (weekdays at 9 AM and 5 PM)
val businessHoursTask = schedule("business-hours", Schedule.Cron(
    cron = CronPattern(
        minutes = listOf(0),
        hours = listOf(9, 17),
        days = CronDays.DaysOfWeek(DayOfWeek.MONDAY..DayOfWeek.FRIDAY)
    ),
    zone = TimeZone.of("America/New_York")
)) {
    checkBusinessMetrics()
}
```

### Action Mailer

**Rails:**

```ruby
class UserMailer < ApplicationMailer
  def welcome(user)
    @user = user
    mail(to: user.email, subject: 'Welcome!')
  end
end

UserMailer.welcome(user).deliver_later
```

**Lightning Server:**

```kotlin
object Server : ServerBuilder() {
    val email = setting("email", EmailService.Settings())

    suspend fun sendWelcome(user: User) {
        email().send(Email(
            subject = "Welcome!",
            to = listOf(EmailAddressWithName(user.email.toEmailAddress(), user.name)),
            html = """
                <h1>Welcome, ${user.name}!</h1>
                <p>Thanks for joining us.</p>
            """.trimIndent()
        ))
    }
}
```

Email backends (SMTP, Amazon SES, console mock) are configured via settings, not code changes.

### Test Fixtures and Factories

**Rails (with FactoryBot):**

```ruby
FactoryBot.define do
  factory :post do
    title { "Test Post" }
    body { "Some content" }
    author { association :user }
  end
end

create(:post, title: "Custom Title")
```

**Lightning Server:** Use Kotlin data class default parameters:

```kotlin
@Serializable
data class Post(
    override val _id: Uuid = Uuid.random(),
    val title: String = "Test Post",
    val body: String = "Some content",
    val authorId: Uuid = Uuid.random(),
    val createdAt: Instant = Clock.System.now()
) : HasId<Uuid>

// In tests
val post = Post(title = "Custom Title")
val anotherPost = Post(title = "Another", authorId = testUser._id)
```

No factory library needed. Named parameters make test data creation clear and type-safe.

### Environment Configuration

**Rails:**

```yaml
# config/database.yml
development:
  adapter: postgresql
  database: myapp_development

production:
  adapter: postgresql
  url: <%= ENV['DATABASE_URL'] %>
```

**Lightning Server:** URL-based configuration in `settings.json`:

```json
// Development (in-memory)
{
  "database": { "url": "ram" },
  "cache": { "url": "ram" },
  "email": { "url": "console" }
}

// Development (local MongoDB)
{
  "database": { "url": "mongodb-file://./data" },
  "cache": { "url": "ram" },
  "email": { "url": "console" }
}

// Production
{
  "database": { "url": "mongodb://user:pass@mongodb.example.com/myapp" },
  "cache": { "url": "redis://redis.example.com:6379" },
  "email": { "url": "smtp://smtp.sendgrid.net:587?user=apikey&password=..." }
}
```

Deploy different `settings.json` files per environment. The URL scheme determines which backend implementation is used.

### Rails Console

**Rails:** `rails console` gives you an interactive REPL with your models loaded.

**Lightning Server alternatives:**

1. **IntelliJ Debugger** - Set breakpoints and use "Evaluate Expression" to run arbitrary Kotlin code against your
   running server

2. **Auto-generated Admin UI** - The lightning-server-kiteui package provides a web-based admin panel for data
   exploration and manipulation

3. **Custom REPL endpoint** (for development only):

```kotlin
// Development-only endpoint for quick queries
val devQuery = path.path("dev").path("query").post.api(
    summary = "Dev query",
    authOptions = AdminAuth.require(),  // Protect this!
    implementation = { query: String ->
        // Execute and return results
    }
)
```

### Rake Tasks

**Rails:** `rake db:seed`, `rake custom:task`

**Lightning Server:** Use kotlinercli or your own CLI structure. The demo module shows this pattern:

```kotlin
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "serve" -> startServer()
        "sdk" -> generateSdk()
        "seed" -> seedDatabase()
        else -> println("Usage: serve | sdk | seed")
    }
}
```

## Things Lightning Server Does Differently

### Compile-Time Safety

The biggest difference you'll notice is how many runtime errors become compile-time errors:

- **Query typos** - `condition { it.titel eq "x" }` won't compile
- **Type mismatches** - `condition { it.age eq "five" }` won't compile
- **Missing fields** - Forgetting a required field in a data class won't compile

This catches bugs before your code ever runs.

### No Asset Pipeline

Lightning Server is API-only. Your frontend application (React, Vue, mobile apps) handles assets, bundling, and
presentation. This is intentional - it enables:

- True separation of concerns
- Different frontend technologies for different clients
- Independent deployment of frontend and backend
- Better scalability

### Settings Auto-Generation

Run your server twice:

1. First run generates a default `settings.json`
2. Second run uses those settings

This ensures your app works out-of-the-box with sensible defaults - a core Lightning Server principle.

## Getting Started

See [Setup Guide](setup.md) for a complete walkthrough. The key steps are:

1. Create a Kotlin/Gradle project
2. Add Lightning Server dependencies
3. Define your `ServerBuilder` object
4. Run twice (generate settings, then serve)
5. Visit `http://localhost:8080`

## Next Steps

- [Endpoints](endpoints.md) - Defining HTTP endpoints
- [Typed Endpoints](typed-endpoints.md) - Type-safe API development
- [Database](database.md) - Query DSL and model definitions
- [Authentication](authentication.md) - User auth patterns
- [Auto REST](autorest.md) - Automatic CRUD generation
