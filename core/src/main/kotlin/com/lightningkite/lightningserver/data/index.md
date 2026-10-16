# Data Package

The `com.lightningkite.lightningserver.data` package provides utility classes and functions for common data handling
tasks in Lightning Server applications.

## Files

### Cron.kt

Cron-style scheduling patterns for recurring tasks.

- **CronPattern**: Define minute/hour/day/month patterns for task scheduling
- **CronDays**: Specify days by weekday or day-of-month
- **DayOfWeekRange**: Range support for weekday specifications

**Key Features**:

- Standard cron syntax with builder-style API
- Calculate next occurrence from a given datetime
- Validation of pattern components

- Last day of the month (`L`), nearest weekday (`W`), and nth weekday of the month (`#`, e.g. "2nd Monday")

### Expiring.kt

Wrapper for values with expiration times.

- **Expiring\<T\>**: Wraps a value with an optional expiration instant
- Provides `expired` property to check if value has expired
- Factory function for creating with relative durations

**Usage**: Cache values that should expire after a certain time period.

### KFile.ext.kt

Conversion utilities between Java `File` and multiplatform `KFile`.

- `KFile.toJavaFile()`: Convert to Java File
- `File.toKFile()`: Convert to multiplatform KFile

**Usage**: Bridge between Java APIs and Lightning Server's multiplatform file handling.

### LongBits.kt

Compact bit set stored in a single Long, supporting indices 0-63.

- **LongBits**: Value class wrapping a Long as a bit set
- Iterable interface for enumerating set bits
- String representation using ranges (e.g., "0-5,10,15-20")

**Usage**: Efficiently store small sets of integers, particularly for cron minute/hour patterns.

### Request.kt

Base class for HTTP requests with caching support.

- **Request\<PATH\>**: Abstract base for request objects
- Implements `Caching` interface for built-in cache
- Provides access to path, headers, query parameters, domain, protocol, and source IP

**Usage**: Extended by framework-provided request implementations. Users interact with Request objects in endpoint
handlers.

### Schedule.kt

Different scheduling strategies for recurring tasks.

- **Schedule.Frequency**: Run at fixed time intervals
- **Schedule.Daily**: Run once per day at specific time
- **Schedule.Cron**: Run based on cron pattern

All schedules support timezone specification.

**Usage**: Define when background tasks should run.

### SerializableCache.kt

Type-safe, serializable cache with optional expiration.

- **SerializableCache\<SCOPE\>**: Main cache class. The scope names what the cache is attached to, so a key made for
  one kind of cache cannot be used on another
- **Key\<SCOPE, T\>**: Simple cache key
- **CalculatingKey\<SCOPE, INPUT, T\>**: Key that automatically calculates values on miss
- **Caching**: Interface for objects with attached caches

**Key Features**:

- Type-safe keys with serializers
- Optional expiration per key
- Local-only mode for non-serializable values
- Automatic calculation on cache miss
- Request-scoped caching support

**Usage**: General-purpose caching with persistence support. Attached to `Request` objects, and used as an
`Execution`'s `context`, which is copied to the executions it starts and serialized into queued tasks.

## Common Use Cases

1. **Request Caching**: Cache computed values within a request to avoid redundant calculations
2. **Task Scheduling**: Define when background tasks should run using cron patterns or schedules
3. **Data Expiration**: Wrap cached data with expiration times
4. **Bit Operations**: Use LongBits for compact storage of small integer sets

## Related Documentation

See [Data Utilities](../../../../../../../../docs/data-utilities.md) for usage examples and best practices.
