# Data Utilities

The `com.lightningkite.lightningserver.data` package provides several utility classes and functions for common
server-side data handling needs.

## SerializableCache

A type-safe cache whose contents can be serialized, so it can travel with a request or an execution, including into a
queued task.

### Basic Usage

A cache is typed by its *scope*, what it is attached to, so a key made for one kind of cache cannot be used with
another. Requests (`SerializableCache<Request<*>>`) and executions (`execution.context`, a
`SerializableCache<Execution>`) each have one.

```kotlin
val userKey = SerializableCache.Key<Execution, User>("my-app/user", User.serializer(), expireAfter = 5.minutes)

val context = serverRuntime.execution.context
context[userKey] = currentUser
val user = context[userKey]  // Retrieve from cache
```

An execution's context is copied to every execution it starts, including queued tasks, so keep values small. A key with
`localOnly = true` is copied within the process but dropped when the context is serialized.

### Calculating Keys

For values that should be calculated on cache miss:

```kotlin
val expensiveKey = object : SerializableCache.CalculatingKey<Execution, String, Result> {
    override val id = "expensive-calculation"
    override val serializer = Result.serializer()

    context(server: ServerRuntime)
    override suspend fun calculate(input: String): Result {
        // Perform expensive calculation
        return performCalculation(input)
    }
}

val result = serverRuntime.execution.context.get(expensiveKey, "input")  // Calculates if not cached
```

### Features

- **Expiration**: Set `expireAfter` to automatically expire values
- **Local-only**: Use `localOnly = true` for values that shouldn't be serialized
- **Request-scoped**: Attach to Request objects via the `Caching` interface

## Expiring

Wraps a value with an optional expiration time.

```kotlin
// Needs an Engine in context, for its clock
val cached = Expiring("cached data", expireAfter = 5.minutes)
if (!cached.expired) {
    // Use cached.value
}
```

## Cron Patterns

Define cron-like scheduling patterns for recurring tasks.

### Examples

```kotlin
// Every day at 3:30 AM
val daily = CronPattern(
    minutes = listOf(30),
    hours = listOf(3)
)

// Every Monday-Friday at 9 AM and 5 PM
val workdays = CronPattern(
    minutes = listOf(0),
    hours = listOf(9, 17),
    days = CronDays.DaysOfWeek(DayOfWeek.MONDAY..DayOfWeek.FRIDAY)
)

// First and 15th of every month at noon
val bimonthly = CronPattern(
    minutes = listOf(0),
    hours = listOf(12),
    days = CronDays.DaysOfMonth(1, 15)
)
```

### Calculating Next Occurrence

```kotlin
val nextRun = Clock.System.now().toLocalDateTime(TimeZone.UTC) + cronPattern
```

## Schedule

Represents different scheduling strategies:

```kotlin
// Fixed intervals
Schedule.Frequency(gap = 5.minutes)

// Daily at specific time
Schedule.Daily(
    time = LocalTime(3, 0),
    zone = TimeZone.of("America/New_York")
)

// Cron-based
Schedule.Cron(
    cron = myCronPattern,
    zone = TimeZone.UTC
)
```

## LongBits

A compact bit set for storing small sets of integers (0-62).

```kotlin
val bits = LongBits(listOf(0, 5, 10, 15))
println(0 in bits)  // true
println(1 in bits)  // false
println(bits.toString())  // "0,5,10,15"

// Find next set bit
val next = bits.lowestAfter(5)  // Returns 10
```

## File Utilities

Convert between Java File and multiplatform KFile:

```kotlin
// Java File to KFile
val kFile = javaFile.toKFile()

// KFile to Java File
val javaFile = kFile.toJavaFile()
```

## Request

Base class for HTTP requests with built-in caching support.

```kotlin
object CurrentUserKey : Request.CacheKey<PathSpec, User?> {
    override val id = "my-app/current-user"
    override val serializer = User.serializer().nullable

    context(server: ServerRuntime)
    override suspend fun calculate(input: Request<*>): User? = /* look the user up */
}

val user = request[CurrentUserKey]  // Calculated once per request, then cached
```

## Best Practices

1. **Cache Keys**: Use unique IDs for cache keys to avoid collisions
2. **Expiration**: Always set expiration times for cached data to prevent stale data
3. **Cron Patterns**: Test your cron patterns thoroughly - timezone handling can be tricky
4. **LongBits**: Only use for small sets of integers (0-62) - use Set for larger ranges
5. **SerializableCache**: For request-scoped caching, implement the `Caching` interface

## See Also

- [Cache](cache.md) - General caching documentation
- [Tasks](tasks.md) - Background task scheduling
- [Settings](settings.md) - Server configuration
