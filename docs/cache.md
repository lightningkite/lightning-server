# Cache

**OUT OF DATE**

### What is a Cache

A cache is a high speed storage server. Much like a database, but much simpler, and hopefully faster.

### Why is it necessary
When building servers, it is frequently necessary to keep some information on hand that is shared between every instance.

That's where the cache comes in.  It uses `kotlinx.serialization` to serialize values in and out of the cache, which 
could be local, Memcached, Redis, or DynamoDB.

### Example Need for a Cache
An example in Lightning Server is with the Pin-Based authentication. We must
store the pin and the key between the start and prove requests. These values are short lived but cannot be stored in the
server process, so they go out to the cache. With the values in the cache the prove request can land on any instance of
the server and the data will be available for validating.

## Declaring the need for a cache

Add a setting as follows:

```kotlin
object Server {
    //...
    val cache = setting(name = "cache", default = CacheSettings())
    //...
}
```

## Using the cache

Make sure you import the shortcuts (alt + enter).

```kotlin
Server.cache().set("value", 1)
Server.cache().get<Int>("value")
Server.cache().remove("value")

@Serializable data class Example(val x: Int, val y: String)
Server.cache().set("value2", Example(x = 1, y = "hi"))
Server.cache().get<Example>("value2")
Server.cache().remove("value2")
```

## More operations

You can increment numerical types, set if it doesn't exist, and set values that expire as well.

TODO: Document further

## Available Backends

### Local

Simply use RAM as the cache in the server process. There will be no external connections made. Will only work if there 
is strictly one instance of the server. The moment you have multiple instances you MUST use another option for a Cache 
or requests may not behave as expected. This is used most often for local development and unit tests. Because this cache
just uses the ram of the process itself, if you restart your server your cache will be emptied.

```json5
// settings.json
{
  "cache": { "url": "local" }
}
```

### DynamoDB

```kotlin
// Server.kt
object Server: ServerPathGroup(ServerPath.root) {
    // Adds DynamoDbCache to the possible database loaders
    init { DynamoDbCache }
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
object Server: ServerPathGroup(ServerPath.root) {
    // Adds RedisCache to the possible database loaders
    init { RedisCache }
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
  "cache": { "url": "redis-test" }
}
```

### Memcached

```kotlin
// Server.kt
object Server: ServerPathGroup(ServerPath.root) {
    // Adds MemcachedCache to the possible database loaders
    init { MemcachedCache }
}
```

```json5
// settings.json
{
  "cache": { "url": "memcached://host:port" }
}
```

#### Testing locally

```json5
// settings.json
{
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
