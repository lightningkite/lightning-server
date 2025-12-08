# Cache

Last updated January 2025 (`version-5`)

When building servers, it is frequently necessary to keep some information on hand that is shared between every instance.

That's where the cache comes in.  It uses `kotlinx.serialization` to serialize values in and out of the cache, which could be local, Memcached, Redis, or DynamoDB.

## Declaring the need for a cache

Add a setting as follows:

```kotlin
object Server : ServerBuilder() {
    //...
    val cache = setting("cache", Cache.Settings())
    //...
}
```

## Using the cache

Make sure you import the shortcuts (alt + enter).

```kotlin
import kotlinx.serialization.Serializable

val c = cache()
c.set("value", 1)
c.get<Int>("value")
c.remove("value")

@Serializable data class Example(val x: Int, val y: String)
c.set("value2", Example(x = 1, y = "hi"))
c.get<Example>("value2")
c.remove("value2")
```

## More operations

You can increment numerical types, set if it doesn't exist, and set values that expire as well.

TODO: Document further

## Available Backends

### Local

Simply use RAM as the cache.  Will only work if there is strictly one instance of the server, so practically speaking it's useful for testing only.

```json5
// settings.json
{
  "cache": { "url": "local" }
}
```

### DynamoDB

```kotlin
// Server.kt
import com.lightningkite.services.cache.dynamodb.DynamoDbCache

object Server: ServerBuilder() {
    // Register DynamoDB cache implementation
    init { DynamoDbCache }

    val cache = setting("cache", Cache.Settings())
}
```

```json5
// settings.json
{
  "cache": { "url": "dynamodb://accessKey:secretKey@us-west-2/tableName" }
}
```

### Redis

```kotlin
// Server.kt
import com.lightningkite.services.cache.redis.RedisCache

object Server: ServerBuilder() {
    // Register Redis cache implementation
    init { RedisCache }

    val cache = setting("cache", Cache.Settings())
}
```

```json5
// settings.json
{
  // Standard redis connection string
  "cache": { "url": "redis://" }
}
```

#### Testing locally

```json5
// settings.json
{
  // Standard redis connection string
  "cache": { "url": "redis-test" }
}
```

### Memcached

```kotlin
// Server.kt
import com.lightningkite.services.cache.memcached.MemcachedCache

object Server: ServerBuilder() {
    // Register Memcached cache implementation
    init { MemcachedCache }

    val cache = setting("cache", Cache.Settings())
}
```

```json5
// settings.json
{
  // Standard redis connection string
  "cache": { "url": "memcached://host:port" }
}
```

#### Testing locally

```json5
// settings.json
{
  // Standard redis connection string
  "cache": { "url": "memcached-test" }
}
```

#### AWS Support

Specifically uses `AWSElasticCacheClient` under the hood from the `xmemcached` library.

```json5
// settings.json
{
  "cache": { "url": "memcached-aws://host:port" }
}
```
