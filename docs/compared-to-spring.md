# Lightning Server for Spring Boot Developers

Last updated January 2025 (`version-5`)

<!-- by Claude -->

This guide helps Spring Boot developers understand Lightning Server by mapping familiar concepts to their equivalents. While both frameworks solve similar problems, Lightning Server takes a different philosophical approach: **compile-time configuration over runtime magic**.

## Philosophy Differences

| Aspect | Spring Boot | Lightning Server |
|--------|-------------|------------------|
| Configuration | Runtime dependency injection | Compile-time object references |
| Discovery | Classpath scanning, annotations | Explicit registration in code |
| Beans | Container-managed singletons | Kotlin objects and lazy values |
| Magic | Convention over configuration | Explicit over implicit |
| Type safety | Runtime checks | Compile-time guarantees |

The core insight: Spring Boot uses annotations and runtime reflection to wire things together. Lightning Server uses Kotlin's type system and explicit code. This means fewer surprises at runtime, but more explicit code.

## Quick Reference Table

| Spring Boot | Lightning Server | Notes |
|-------------|------------------|-------|
| `@RestController` | `ServerBuilder` object | Define endpoints explicitly |
| `@Autowired` | Direct object reference | No DI container needed |
| `@ConfigurationProperties` | `setting()` | Type-safe, auto-generated defaults |
| `@Repository` | `database().table<T>()` | Type-safe query DSL |
| `@Scheduled` | `schedule()` | Cron, frequency, or daily time |
| `@Async` | `task()` | Fire-and-forget async tasks |
| Spring Security | `PrincipalType` + `AuthEndpoints` | JWT-based, multiple proof methods |
| `@Cacheable` | Explicit cache calls | `cache().get()` / `cache().set()` |
| Actuator | `MetaEndpoints` | Health, metrics, OpenAPI |
| Profiles | URL-based service selection | `ram://` vs `mongodb://` etc. |

## Dependency Injection

### Spring Boot
```java
@Service
public class UserService {
    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmailService emailService;
}
```

### Lightning Server
```kotlin
object Server : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val email = setting("email", EmailService.Settings())

    // Access services directly - no injection needed
    val createUser = path.path("users").post.api(
        summary = "Create user",
        authOptions = noAuth,
        implementation = { input: CreateUserRequest ->
            val db = database()
            val emailSvc = email()
            // Use services directly
        }
    )
}
```

**Key difference**: Instead of a runtime DI container managing bean lifecycles, you access services through settings that resolve at startup. Services are accessed via function calls (`database()`, `email()`) which return cached instances.

## Controllers and Endpoints

### Spring Boot
```java
@RestController
@RequestMapping("/api/users")
public class UserController {

    @GetMapping("/{id}")
    public User getUser(@PathVariable String id) {
        return userRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public User createUser(@RequestBody @Valid CreateUserRequest request) {
        return userRepository.save(request.toUser());
    }
}
```

### Lightning Server
```kotlin
object UserEndpoints : ServerBuilder() {

    val getUser = path.path("users").arg<String>("id").get.api(
        summary = "Get user by ID",
        authOptions = noAuth,
        errorCases = listOf(LSError(http = 404, detail = "not-found", message = "User not found")),
        implementation = { _: Unit ->
            val id = path.arg1
            database().table<User>().get(Uuid.parse(id))
                ?: throw NotFoundException("User not found")
        }
    )

    val createUser = path.path("users").post.api(
        summary = "Create user",
        authOptions = noAuth,
        successCode = HttpStatus.Created,
        implementation = { request: CreateUserRequest ->
            database().table<User>().insertOne(request.toUser())
        }
    )
}

// Include in main server
object Server : ServerBuilder() {
    val api = path.path("api") include UserEndpoints
}
```

**Key differences**:
- Endpoint definitions are stored as values, making them easy to reference in tests
- Path arguments are type-safe (`arg1` has the type you declared)
- Typed endpoints (`api()`) generate OpenAPI docs automatically

## Repository Pattern / Data Access

### Spring Boot (Spring Data JPA)
```java
public interface UserRepository extends JpaRepository<User, UUID> {
    List<User> findByEmailContaining(String email);
    List<User> findByCreatedAtAfter(Instant date);
    Optional<User> findByEmailAndActiveTrue(String email);
}
```

### Lightning Server
```kotlin
@Serializable
@GenerateDataClassPaths  // Enables the type-safe query DSL
data class User(
    override val _id: Uuid = Uuid.random(),
    val email: String,
    val active: Boolean = true,
    val createdAt: Instant = Clock.System.now()
) : HasId<Uuid>

// Usage - no interface to define!
val users = database().table<User>()

// Find by email containing
users.find(condition { it.email.contains("@example.com") }).toList()

// Find by date
users.find(condition { it.createdAt gt someDate }).toList()

// Compound conditions
users.findOne(condition { (it.email eq email) and (it.active eq true) })

// Updates with type-safe modifications
users.updateOne(
    condition { it._id eq userId },
    modification { it.active assign false }
)
```

**Key differences**:
- No repository interfaces to define - just use the table directly
- Queries are type-safe Kotlin DSL, not method naming conventions
- IDE autocomplete works on field names
- Refactoring a field name updates all queries automatically
- More expressive: you can write any condition, not just what Spring Data method naming supports

## Configuration Properties

### Spring Boot
```java
@ConfigurationProperties(prefix = "myapp")
public class MyAppProperties {
    private String webUrl;
    private FeatureFlags features = new FeatureFlags();

    // getters and setters...
}

// application.yml
myapp:
  webUrl: http://localhost:8080
  features:
    darkMode: true
```

### Lightning Server
```kotlin
@Serializable
data class FeatureFlags(
    val darkMode: Boolean = false,
    val betaFeatures: Boolean = false
)

object Server : ServerBuilder() {
    val webUrl = setting("webUrl", "http://localhost:8080")
    val features = setting("features", FeatureFlags())

    // Access settings with ()
    val someEndpoint = path.get bind HttpHandler {
        if (features().darkMode) { /* ... */ }
        HttpResponse.plainText("URL: ${webUrl()}")
    }
}
```

```json
// settings.json
{
  "webUrl": "http://localhost:8080",
  "features": {
    "darkMode": true,
    "betaFeatures": false
  }
}
```

**Key differences**:
- Settings use KotlinX Serialization - any `@Serializable` type works
- Default values are required and used to auto-generate `settings.json`
- First run generates `settings.suggested.json` with all needed settings
- Server won't start until all required settings are configured

## Spring Security vs Lightning Server Auth

### Spring Boot
```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/public/**").permitAll()
            .requestMatchers("/api/admin/**").hasRole("ADMIN")
            .anyRequest().authenticated()
        );
        return http.build();
    }
}
```

### Lightning Server
```kotlin
@Serializable
@GenerateDataClassPaths
data class User(
    override val _id: Uuid = Uuid.random(),
    override val email: String,
    val isAdmin: Boolean = false
) : HasId<Uuid>, HasEmail

object UserAuth : PrincipalType<User, Uuid> {
    override val idSerializer = Uuid.serializer()
    override val subjectSerializer = User.serializer()
    override val name = "User"

    context(server: ServerRuntime)
    override suspend fun fetch(id: Uuid): User =
        database().table<User>().get(id) ?: throw NotFoundException()
}

object Server : ServerBuilder() {
    // Public endpoint - no auth required
    val publicEndpoint = path.path("api").path("public").get.api(
        summary = "Public endpoint",
        authOptions = noAuth,
        implementation = { _: Unit -> "Hello!" }
    )

    // Authenticated endpoint
    val protectedEndpoint = path.path("api").path("protected").get.api(
        summary = "Protected endpoint",
        authOptions = authOptions<User>(),
        implementation = { _: Unit ->
            val user = auth.fetch()
            "Hello, ${user.email}!"
        }
    )

    // Admin-only endpoint
    val adminEndpoint = path.path("api").path("admin").get.api(
        summary = "Admin endpoint",
        authOptions = authOptions<User>(),
        implementation = { _: Unit ->
            val user = auth.fetch()
            if (!user.isAdmin) throw ForbiddenException("Admin access required")
            "Admin panel"
        }
    )
}
```

**Key differences**:
- Auth requirements are per-endpoint, not centralized
- User fetching is explicit (`auth.fetch()`)
- Multiple authentication methods: email PIN, SMS, password, TOTP, OAuth
- JWT-based sessions by default

## Actuator / Health Monitoring

### Spring Boot
```java
// application.properties
management.endpoints.web.exposure.include=health,info,metrics
management.endpoint.health.show-details=always

// Custom health indicator
@Component
public class CustomHealthIndicator implements HealthIndicator {
    @Override
    public Health health() {
        return Health.up()
            .withDetail("custom", "value")
            .build();
    }
}
```

### Lightning Server
```kotlin
object Server : ServerBuilder() {
    val meta = path.path("meta") module MetaEndpoints(
        packageName = "com.yourcompany.yourproject",
        database = database,
        cache = cache
    )
}
```

This provides:
- `/meta/health` - Aggregated health from all services (database, cache, email, etc.)
- `/meta/online` - Simple "Server is running" check
- `/meta/openapi` - Swagger UI
- `/meta/openapi.json` - OpenAPI spec
- `/meta/docs` - Plain text API documentation
- `/meta/admin` - React admin panel for your models

Health checks automatically include:
- Memory usage and CPU load
- Per-service health status (OK, WARNING, ERROR, URGENT)
- Results cached with configurable TTL

**Key difference**: Health checks are automatic for registered services. No custom indicators needed unless you have non-standard dependencies.

## Caching

### Spring Boot
```java
@Service
public class UserService {
    @Cacheable(value = "users", key = "#id")
    public User getUser(String id) {
        return userRepository.findById(id).orElseThrow();
    }

    @CacheEvict(value = "users", key = "#user.id")
    public User updateUser(User user) {
        return userRepository.save(user);
    }
}
```

### Lightning Server
```kotlin
object Server : ServerBuilder() {
    val cache = setting("cache", Cache.Settings())

    val getUser = path.path("users").arg<String>("id").get.api(
        summary = "Get user",
        authOptions = noAuth,
        implementation = { _: Unit ->
            val id = path.arg1
            val cacheKey = "user:$id"

            // Explicit caching - no magic
            cache().get<User>(cacheKey) ?: run {
                val user = database().table<User>().get(Uuid.parse(id))
                    ?: throw NotFoundException()
                cache().set(cacheKey, user, ttl = 5.minutes)
                user
            }
        }
    )
}
```

**Key difference**: Caching is explicit. You see exactly what's cached and for how long. No annotation magic that might surprise you.

Cache backends available:
- `local` - RAM (single instance only, good for testing)
- `redis://` - Redis
- `memcached://` - Memcached
- `dynamodb://` - DynamoDB

## Scheduled Tasks

### Spring Boot
```java
@Component
public class ScheduledTasks {
    @Scheduled(fixedRate = 900000)  // 15 minutes
    public void runEvery15Minutes() {
        // task logic
    }

    @Scheduled(cron = "0 0 12 * * ?")  // noon daily
    public void runAtNoon() {
        // task logic
    }
}
```

### Lightning Server
```kotlin
object Server : ServerBuilder() {
    // Every 15 minutes
    val periodicTask = schedule("cleanup-task", Schedule.Frequency(15.minutes)) {
        println("Running every 15 minutes")
    }

    // Daily at noon
    val dailyTask = schedule("daily-report", Schedule.Daily(LocalTime(12, 0))) {
        println("Daily report at noon")
    }

    // Full cron expression with timezone
    val cronTask = schedule(
        "complex-schedule",
        Schedule.Cron("0 30 9 * * MON-FRI", TimeZone.of("America/New_York"))
    ) {
        println("Weekdays at 9:30 AM Eastern")
    }
}
```

**Key difference**: Schedules are defined alongside your server, not scattered in component classes.

## Async Tasks

### Spring Boot
```java
@Service
public class NotificationService {
    @Async
    public CompletableFuture<Void> sendNotification(String userId, String message) {
        // async logic
        return CompletableFuture.completedFuture(null);
    }
}
```

### Lightning Server
```kotlin
object Server : ServerBuilder() {
    // Define the task
    val sendNotification = task("send-notification") { input: NotificationRequest ->
        // This runs asynchronously
        emailService().send(input.toEmail())
    }

    // Call it from an endpoint
    val createOrder = path.path("orders").post.api(
        summary = "Create order",
        authOptions = authOptions<User>(),
        implementation = { order: CreateOrderRequest ->
            val saved = database().table<Order>().insertOne(order.toOrder())

            // Fire and forget
            sendNotification(NotificationRequest(order.userId, "Order created!"))

            saved
        }
    )
}
```

**Key difference**: Tasks are first-class citizens with proper serialization. In AWS Lambda deployments, they automatically use SQS for reliable delivery.

## Profiles / Environments

### Spring Boot
```java
// application-dev.properties
spring.datasource.url=jdbc:h2:mem:testdb

// application-prod.properties
spring.datasource.url=jdbc:postgresql://prod-server:5432/mydb
```

### Lightning Server

Different environments use different `settings.json` files:

```json
// settings.local.json
{
  "database": { "url": "ram" },
  "cache": { "url": "local" },
  "email": { "url": "console" }
}

// settings.production.json
{
  "database": { "url": "mongodb+srv://..." },
  "cache": { "url": "redis://..." },
  "email": { "url": "smtp://..." }
}
```

**Key difference**: Service backends are selected by URL scheme, not profile name. `ram://` vs `mongodb://` is explicit about what's being used.

## Conditional Beans

### Spring Boot
```java
@Bean
@ConditionalOnProperty(name = "features.debug", havingValue = "true")
public DebugController debugController() {
    return new DebugController();
}
```

### Lightning Server
```kotlin
object Server : ServerBuilder() {
    val features = setting("features", FeatureFlags())

    // Plain Kotlin conditional
    init {
        if (features().debug) {
            // Include debug endpoints
        }
    }

    // Or include conditionally
    val debugEndpoints = if (isDevelopment) {
        path.path("debug") include DebugEndpoints
    } else null
}
```

**Key difference**: Use regular Kotlin `if` statements. No special annotations needed.

## Testing

### Spring Boot
```java
@SpringBootTest
@AutoConfigureMockMvc
public class UserControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserRepository userRepository;

    @Test
    public void testGetUser() throws Exception {
        when(userRepository.findById("123"))
            .thenReturn(Optional.of(new User("123", "test@example.com")));

        mockMvc.perform(get("/api/users/123"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("test@example.com"));
    }
}
```

### Lightning Server
```kotlin
class UserEndpointsTest {
    companion object {
        val testRunner by lazy { TestRunner(Server.build()) }

        @BeforeAll
        @JvmStatic
        fun setup() {
            JsonFileDatabase  // Use in-memory database for tests
        }
    }

    @Test
    fun testGetUser() = runBlocking {
        with(testRunner) {
            // Insert test data
            Server.database().table<User>().insertOne(
                User(_id = Uuid.parse("..."), email = "test@example.com")
            )

            // Test the endpoint directly
            val response = Server.getUser.test(pathArgs = listOf("..."))

            assertEquals(HttpStatus.OK, response.status)
            val user = response.body!!.parse<User>()
            assertEquals("test@example.com", user.email)
        }
    }
}
```

**Key difference**: Endpoint references are stored in variables, making them directly testable. Use mock service implementations (`JsonFileDatabase`, RAM cache) instead of mocking.

## OpenAPI / Swagger

### Spring Boot
```java
@Operation(summary = "Get user by ID")
@ApiResponses({
    @ApiResponse(responseCode = "200", description = "Found the user"),
    @ApiResponse(responseCode = "404", description = "User not found")
})
@GetMapping("/{id}")
public User getUser(@PathVariable String id) { ... }
```

### Lightning Server
```kotlin
val getUser = path.path("users").arg<String>("id").get.api(
    summary = "Get user by ID",
    description = "Retrieves a user by their unique identifier",
    errorCases = listOf(
        LSError(http = 404, detail = "not-found", message = "User not found")
    ),
    authOptions = noAuth,
    implementation = { _: Unit -> /* ... */ }
)
```

OpenAPI is generated automatically from typed endpoints. Access at `/meta/openapi` for Swagger UI or `/meta/openapi.json` for the raw spec.

## Retry / Circuit Breaker

### Spring Boot (with Resilience4j)
```java
@Retry(name = "external-service")
@CircuitBreaker(name = "external-service")
public String callExternalService() {
    return restTemplate.getForObject("...", String.class);
}
```

### Lightning Server

Use Kotlin coroutines patterns and Ktor client plugins:

```kotlin
// Simple retry with coroutines
suspend fun <T> retry(times: Int, block: suspend () -> T): T {
    repeat(times - 1) {
        try { return block() }
        catch (e: Exception) { delay(1000) }
    }
    return block()
}

// Or use Ktor client with plugins
val client = HttpClient {
    install(HttpRequestRetry) {
        retryOnServerErrors(maxRetries = 3)
        exponentialDelay()
    }
}
```

For AWS Lambda deployments:
- Lambda automatically retries failed invocations
- Use Step Functions for circuit breaker patterns
- SQS dead-letter queues handle persistent failures

## DevTools / Hot Reload

### Spring Boot
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-devtools</artifactId>
</dependency>
```

### Lightning Server

Use Gradle continuous build:
```bash
./gradlew -t :your-module:run --args="serve"
```

Or run from IntelliJ with automatic recompilation on save. Kotlin compilation is fast enough that hot reload is rarely needed.

## Summary

Lightning Server trades runtime "magic" for compile-time explicitness:

| Spring Boot Magic | Lightning Server Explicit |
|-------------------|---------------------------|
| Classpath scanning finds beans | You list what you use |
| `@Autowired` injects dependencies | You call `service()` directly |
| `@Cacheable` magically caches | You call `cache().get()` |
| Method naming creates queries | You write query conditions |
| Profiles switch configurations | You deploy different `settings.json` |

This approach has tradeoffs:
- **More explicit code** - You write more, but understand what happens
- **Faster startup** - No classpath scanning or reflection
- **Type safety** - Compiler catches errors Spring finds at runtime
- **Serverless friendly** - Designed for AWS Lambda from the start
- **Learning curve** - Less documentation online than Spring

For Spring developers, the key mental shift is: **if you don't see it in code, it doesn't happen**. There's no annotation processor wiring things together behind the scenes.

## See Also

- [Setup Guide](setup.md) - Get started with Lightning Server
- [Endpoints](endpoints.md) - Full endpoint definition guide
- [Typed Endpoints](typed-endpoints.md) - API endpoints with auto-generated docs
- [Database](database.md) - Query DSL and model definitions
- [Authentication](authentication.md) - Auth setup and patterns
- [Settings](settings.md) - Configuration management
