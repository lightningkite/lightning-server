> # ⚠️ UNREVIEWED FIRST DRAFT — DO NOT PUBLISH
> Auto-generated first pass. Code samples are modeled on the current source but have **NOT** been compiled, drift-checked, or reviewed. This page is intentionally **not** in the site nav. Before publishing: port samples into the drift-checked `docs-guide/src/samples` module, run the fresh-eyes judge, and delete this banner.

# Lightning Server vs Spring Boot

**Who this page is for:** Spring Boot developers evaluating Lightning Server, or teams deciding which framework fits
a new API project.  The goal is an honest side-by-side — not a sales pitch.

---

## Framing

Spring Boot is a mature, annotation-driven framework backed by a massive ecosystem (Spring Data, Spring Security,
Spring Batch, Actuator, …).  Lightning Server is a Kotlin framework for API-first backends that trades annotation
magic for compile-time explicitness: endpoints are Kotlin values, dependencies are plain function calls, and the
same `ServerBuilder` definition runs in a unit test, a JVM server, or an AWS Lambda without any adapters.

Both frameworks target production-ready REST APIs and background work on the JVM.  The comparison below focuses
on that intersection.

---

## Feature Comparison

| Feature | Spring Boot | Lightning Server | Notes |
|---|---|---|---|
| **Language** | Java / Kotlin | Kotlin | LS is Kotlin-only |
| **Endpoint style** | `@RestController` + `@GetMapping` / `@PostMapping` | `ServerBuilder` object with `path.method bind ApiHttpHandler(...)` | LS endpoints are stored as values — directly testable |
| **Dependency injection** | `@Autowired` / constructor injection via IoC container | Direct Kotlin object access; services injected via `context(server: ServerRuntime)` | LS has no DI container |
| **Configuration** | `application.yml` / `@ConfigurationProperties` | `settings.json` auto-generated on first run; `setting("key", Default)` in code | LS defaults are Kotlin types, not YAML strings |
| **Data models / ORM** | Spring Data JPA — `@Entity` + `JpaRepository<T, ID>` | `@Serializable data class T : HasId<ID>` + `@GenerateDataClassPaths`; query DSL via `condition {}` / `modification {}` | LS default backend is MongoDB; partial Postgres support exists |
| **Migrations** | Flyway / Liquibase required for every schema change | Not required for MongoDB (schemaless) | Adding fields to a model is zero-effort in LS |
| **Auto-CRUD** | Spring Data REST `@RepositoryRestResource` | `ModelRestEndpoints` (generates 8 endpoints: query, get, insert, update, delete, count, bulk upsert, bulk delete) | LS also generates typed client SDKs for the CRUD surface |
| **Auto-admin panel** | None built-in (community projects exist) | Via `lightning-server-kiteui` companion package | Both auto-generate from model definitions |
| **Auth / sessions** | Spring Security — filter chains, `UserDetailsService` | `PrincipalType<SUBJECT, ID>` + proof/session modules | LS: email OTP, SMS PIN, password, TOTP, OAuth; JWT bearer tokens |
| **Input validation** | `@Valid` + JSR-380 Bean Validation (`@NotNull`, `@Size`, …) | `validators` framework wired into `ApiHttpHandler` automatically | LS validators are Kotlin functions, not annotations |
| **Typed client SDKs** | Not built-in (Swagger Codegen is a separate tool) | `FetcherSdk` (Kotlin/Multiplatform) and `TypescriptFetcherSdk` built-in | Generated from typed endpoint definitions at build time or live via `MetaEndpoints` |
| **OpenAPI / docs** | SpringDoc / SpringFox (third-party) | `MetaEndpoints` exposes `/meta/openapi`, `/meta/docs` live | LS generates from the same typed metadata used for SDK generation |
| **Background tasks** | `@Async` + `CompletableFuture`; Quartz for reliable queues | `Task` declared on `ServerBuilder`; `task.launch(input)` enqueues it | In AWS deployments LS tasks automatically use SQS |
| **Scheduled jobs** | `@Scheduled` on `@Component` | `ScheduledTask` with `Schedule.Frequency`, `Schedule.Daily`, or `Schedule.Cron` | LS engine handles distributed locking across replicas |
| **WebSockets / realtime** | Spring WebSocket + STOMP (separate config) | `ApiWebsocketHandler` — first-class endpoint, same path system | LS WebSocket endpoints participate in SDK generation |
| **Caching** | `@Cacheable` / `@CacheEvict` via Spring Cache | Explicit `cache().get<T>(key)` / `cache().set(key, value, ttl)` | LS: RAM, Redis, Memcached, DynamoDB backends swappable via URL |
| **Health / actuator** | Spring Boot Actuator — auto-configured | `MetaEndpoints` — `/meta/health`, `/meta/paths`, `/meta/openapi` | LS health checks aggregate across all registered services |
| **Environment profiles** | `application-dev.yml` / `application-prod.yml` | Deploy different `settings.json`; URL scheme selects backend (`ram://`, `mongodb://`, …) | LS: explicit backend selection, not profile names |
| **Deployment targets** | WAR / JAR + embedded Tomcat; Spring Cloud Functions for serverless | Same `ServerBuilder` runs on Ktor, Netty, JDK Server, or AWS Lambda | LS AWS engine generates Terraform automatically |
| **Startup time** | Seconds (classpath scanning + reflection) | Fast (no classpath scanning) | LS pays a Kotlin compilation cost instead |
| **Ecosystem size** | Very large; decades of community libraries | Small; newer framework | Spring wins decisively on available integrations |

---

## Key Concept Mappings

### Controllers and Endpoints

Spring uses class-level `@RestController` and method-level `@GetMapping` / `@PostMapping` annotations discovered
at runtime.  Lightning Server uses plain Kotlin values stored as properties of a `ServerBuilder` object.

```java
// Spring Boot — illustrative
@RestController
@RequestMapping("/api/users")
public class UserController {

    @GetMapping("/{id}")
    public ResponseEntity<User> getUser(@PathVariable String id) {
        return userRepository.findById(id)
            .map(ResponseEntity::ok)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public User createUser(@RequestBody @Valid CreateUserRequest request) {
        return userRepository.save(request.toUser());
    }
}
```

```kotlin
// Lightning Server — illustrative
object UserEndpoints : ServerBuilder() {

    val getUser = path.path("users").arg<String>("id").get bind ApiHttpHandler(
        summary    = "Get user by ID",
        auth       = noAuth,
        errorCases = listOf(LSError(http = 404, detail = "not-found", message = "User not found")),
        implementation = { _: Unit ->
            val id = path.arg1
            userTable().get(Uuid.parse(id))
                ?: throw NotFoundException(detail = "not-found", message = "User not found")
        }
    )

    val createUser = path.path("users").post bind ApiHttpHandler(
        summary     = "Create user",
        auth        = noAuth,
        successCode = HttpStatus.Created,
        implementation = { request: CreateUserRequest ->
            userTable().insertOne(request.toUser())
        }
    )
}
```

The key difference: LS endpoints are stored as `val` properties.  You can reference `UserEndpoints.getUser`
directly in tests and internal server-to-server calls — no HTTP mock needed.

### Data Access

Spring Data JPA requires interface declarations; the framework generates implementations at runtime via
reflection.  Lightning Server uses a type-safe query DSL generated at compile time from `@GenerateDataClassPaths`.

```java
// Spring — illustrative
public interface UserRepository extends JpaRepository<User, UUID> {
    List<User> findByEmailContaining(String email);
    Optional<User> findByEmailAndActiveTrue(String email);
}
```

```kotlin
// Lightning Server — illustrative
@Serializable
@GenerateDataClassPaths   // generates compile-time query paths
data class User(
    override val _id: Uuid = Uuid.random(),
    val email: String,
    val active: Boolean = true,
) : HasId<Uuid>

// No repository interface — use the table directly:
val users = userTable()

users.find(condition { it.email.contains("@example.com") }).toList()
users.findOne(condition { (it.email eq email) and (it.active eq true) })
users.updateOne(condition { it._id eq id }, modification { it.active assign false })
```

Renaming `email` to `emailAddress` in the data class will cause every `condition { it.email … }` call to fail
at compile time — no runtime surprises.

### Configuration Properties

```java
// Spring — illustrative
@ConfigurationProperties(prefix = "myapp")
public class MyAppProperties {
    private String webUrl = "http://localhost:8080";
}
```

```kotlin
// Lightning Server — illustrative
object Server : ServerBuilder() {
    val webUrl = setting("webUrl", "http://localhost:8080")

    val root = path.get bind HttpHandler {
        HttpResponse.plainText("Serving from ${webUrl()}")
    }
}
```

LS generates `settings.json` on first run using the defaults.  The `setting()` call returns a lazy accessor;
calling `webUrl()` inside a handler reads the value injected at startup.

### Auth and Authorization

Spring Security uses a centralized filter chain.  Lightning Server declares auth requirements per-endpoint.

```java
// Spring — illustrative
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(auth -> auth
        .requestMatchers("/api/public/**").permitAll()
        .requestMatchers("/api/admin/**").hasRole("ADMIN")
        .anyRequest().authenticated()
    );
    return http.build();
}
```

```kotlin
// Lightning Server — illustrative
object Server : ServerBuilder() {

    // Public — no auth
    val publicEndpoint = path.path("public").get bind ApiHttpHandler(
        summary = "Public endpoint",
        auth    = noAuth,
        implementation = { _: Unit -> "Hello!" }
    )

    // Authenticated — framework enforces a valid session token
    val protectedEndpoint = path.path("protected").get bind ApiHttpHandler(
        summary = "Protected endpoint",
        auth    = UserAuth.require(),
        implementation = { _: Unit ->
            val user = auth.fetch()  // fetch() loads the User from the database
            "Hello, ${user.email}!"
        }
    )

    // Role check — explicit Kotlin if, not a Spring Security role annotation
    val adminEndpoint = path.path("admin").get bind ApiHttpHandler(
        summary = "Admin endpoint",
        auth    = UserAuth.require(),
        implementation = { _: Unit ->
            val user = auth.fetch()
            if (!user.isAdmin) throw ForbiddenException("Admin access required")
            "Admin panel"
        }
    )
}
```

Per-endpoint auth declarations also flow into the generated SDK — clients know which endpoints require a
session token without reading the source.

### Caching

Spring Cache hides cache interaction behind annotations; Lightning Server makes it explicit.

```java
// Spring — illustrative
@Cacheable(value = "users", key = "#id")
public User getUser(String id) { ... }

@CacheEvict(value = "users", key = "#user.id")
public User updateUser(User user) { ... }
```

```kotlin
// Lightning Server — illustrative
val getUser = path.path("users").arg<String>("id").get bind ApiHttpHandler(
    summary = "Get user",
    auth    = noAuth,
    implementation = { _: Unit ->
        val id = path.arg1
        val key = "user:$id"
        cache().get<User>(key) ?: run {
            val user = userTable().get(Uuid.parse(id)) ?: throw NotFoundException()
            cache().set(key, user, ttl = 5.minutes)
            user
        }
    }
)
```

The cache backend (`ram://`, `redis://`, `memcached://`, `dynamodb://`) is selected in `settings.json`.
Swapping from RAM to Redis in production requires no code change.

### Scheduled Tasks

```java
// Spring — illustrative
@Scheduled(fixedRate = 900_000)  // 15 minutes
public void cleanup() { ... }

@Scheduled(cron = "0 0 12 * * ?")
public void dailyReport() { ... }
```

```kotlin
// Lightning Server — illustrative
object Server : ServerBuilder() {

    val cleanup = schedule("cleanup", Schedule.Frequency(15.minutes)) {
        // runs every 15 minutes; engine handles distributed locking
    }

    val dailyReport = schedule("daily-report", Schedule.Daily(LocalTime(12, 0))) {
        // runs once per day at noon
    }

    val cronJob = schedule(
        "complex",
        Schedule.Cron("0 30 9 * * MON-FRI", TimeZone.of("America/New_York"))
    ) {
        // weekdays at 09:30 Eastern
    }
}
```

LS schedules are co-located with the rest of your server definition rather than scattered across `@Component`
classes.

### Background Tasks

```java
// Spring — illustrative
@Async
public CompletableFuture<Void> sendWelcomeEmail(String userId) { ... }
```

```kotlin
// Lightning Server — illustrative
object Server : ServerBuilder() {

    val sendWelcomeEmail = task("send-welcome-email") { userId: Uuid ->
        val user = userTable().get(userId) ?: return@task
        email().send(welcomeEmail(user))
    }

    val register = path.path("register").post bind ApiHttpHandler(
        summary = "Register",
        auth    = noAuth,
        implementation = { req: RegistrationRequest ->
            val user = userTable().insertOne(req.toUser())
            sendWelcomeEmail.launch(user._id)   // fire and forget
            user
        }
    )
}
```

In AWS Lambda deployments, `task.launch(input)` automatically enqueues onto SQS for reliable delivery.

### Testing

Spring Boot tests spin up an application context, mock beans, and send requests through `MockMvc`.  LS tests
call endpoint logic directly in-process with no ports, no classpath scanning, and mock services configured
inline.

```java
// Spring — illustrative
@SpringBootTest
@AutoConfigureMockMvc
class UserControllerTest {
    @Autowired MockMvc mvc;
    @MockBean UserRepository repo;

    @Test void testGetUser() throws Exception {
        when(repo.findById("123")).thenReturn(Optional.of(new User("123", "test@example.com")));
        mvc.perform(get("/api/users/123"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("test@example.com"));
    }
}
```

```kotlin
// Lightning Server — illustrative
class UserEndpointsTest {
    @Test
    fun testGetUser() = Server.testBlocking(settings = { database.set("ram") }) {
        val user = User(_id = Uuid.random(), email = "test@example.com")
        userTable().insertOne(user)

        val result = Server.getUser.test(null, Unit)  // typed output, no HTTP round-trip
        check(result.email == "test@example.com")
    }
}
```

No mocking frameworks, no context loaders, no `@MockBean`.  The RAM database backend is the mock — it resets
between test runs automatically.

---

## Where Each Wins

### Spring Boot is the stronger choice when:

- Your team is primarily Java-focused and migrating to Kotlin is not on the roadmap.
- You need a vast integration ecosystem: Spring Data Redis, Spring Batch, Spring Cloud, Spring AMQP, and
  hundreds of auto-configurations have no direct equivalents in Lightning Server today.
- You need SQL-first with mature migration tooling (Flyway, Liquibase) and battle-tested ORM semantics.
- You require enterprise features like Spring Security SAML, Spring Integration, or Spring State Machine.
- Hiring is a factor — Spring Boot developers are plentiful; Lightning Server is a smaller community.

### Lightning Server is the stronger choice when:

- **End-to-end type safety matters** — renaming a model field breaks the API handler, the query DSL, and the
  generated SDK simultaneously at compile time.  Spring annotations are checked at runtime.
- **"Define once, run anywhere" is a goal** — the same `ServerBuilder` runs in JUnit (no ports), Ktor (local
  dev), Netty/JDK (production), and AWS Lambda (serverless) without adapters or environment-specific wiring.
- **Serverless on AWS is a target** — the `AwsAdapter` generates the Terraform for API Gateway, Lambda, S3,
  DynamoDB, and Secrets Manager directly from your declared settings.  Spring Cloud Function + SAM achieves
  something similar but requires significantly more configuration.
- **You want generated client SDKs** — `FetcherSdk` and `TypescriptFetcherSdk` are first-class and regenerate
  automatically from your typed endpoint definitions.
- **Fast test cycles are important** — LS tests start in milliseconds; Spring application-context tests take
  seconds to warm up.
- **Small team, new Kotlin-first project** — less framework ceremony means less surface area to maintain.

---

## Migration Mindset

If you're porting a Spring Boot service to Lightning Server, the largest conceptual shift is:
**if you don't see it in Kotlin code, it doesn't happen**.

| Spring Boot pattern | Lightning Server equivalent |
|---|---|
| Classpath scanning finds `@Component` / `@Controller` | You add endpoints to `ServerBuilder` explicitly |
| `@Autowired` injects the dependency | You call `database()` / `cache()` / `email()` directly |
| `@Cacheable` / `@CacheEvict` annotates a method | You write `cache().get(key) ?: ...` / `cache().set(key, ...)` |
| Method-name conventions generate JPA queries | You write `condition { it.field eq value }` |
| `@Scheduled` annotates a component method | You declare `schedule("name", Schedule.Frequency(...)) { }` |
| Profiles (`dev`, `prod`) switch configurations | You deploy different `settings.json` files |

There is no annotation processor discovering your classes; add an endpoint and it appears because you added it.
This makes the code more verbose in places, but removes an entire class of "why didn't Spring pick this up?"
debugging sessions.

---

## See Also

- [What is a Lightning Server Made Of?](../guide/overview.md) — building blocks overview
- [Typed Endpoints](../guide/typed-endpoints.md) — `ApiHttpHandler`, `errorCases`, SDK generation
- [Services & Settings](../guide/services.md) — swapping database, cache, email backends
- [Authentication & Sessions](../guide/auth.md) — `PrincipalType`, proofs, bearer tokens
- [Schedules](../guide/schedules.md) — `Schedule.Frequency`, `Schedule.Daily`, `Schedule.Cron`
- [Tasks](../guide/tasks.md) — background task declaration and SQS integration
