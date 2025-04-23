# Pub/Sub


### What is a Pub/Sub

A Pub/Sub is a Messaging Broker that facilitates high speed sending and listening to messages.

[Google What is a Pub/Sub](https://cloud.google.com/pubsub/docs/overview)\
[Google Pub/Sub Overview](https://cloud.google.com/pubsub/docs/pubsub-basics)

### Why is it necessary

Lightning Server needs a Pub/Sub service for a distributed compute environment. What does that mean?

If you have multiple instances of your server running on separate machines to distribute the load of requests from your
clients, there may be some required messaging in between these server instance, and the Pub/Sub is the means of this
communication.

Unlike a cache, where data is simply made available to multiple server, a Pub/Sub is an active send and listen process.
An instance can subscribe to specific channels, and any messages sent on the channel by other servers will be received 
and can be processed.

It uses `kotlinx.serialization` to serialize message to and from the pub/sub.

### Example Need for a Pub/Sub
An example of inter-server communication requirement is for the `restApiWebsocket`. Connecting to a websocket and
listening for updates to models will require this communication. If you are connected to one server instance, A,
listening to changes on a specific object, but a PATCH was made on another instance, B, to this same object, you will
need to get these updates. This is done by instance B sending through the Pub/Sub the model difference, where instance A
picks it up and forwards it to you.

### Available Pub/subs

Currently supported Pub/Subs in Lightning Server are:
- [Redis](https://redis.io/)
- Local


## Declaring the need for a cache

Add a setting as follows:

```kotlin
object Server {
    //...
    val cache = setting(name = "pubsub", default = PubSubSettings())
    //...
}
```

## Using the cache

Make sure you import the shortcuts (alt + enter).

```kotlin
@Serializable data class Example(val x: Int, val y: String)
Server.pubsub().get("topic", ExampleSerializer).collect{ item:Example -> }
Server.pubsub.get("topic", TypeSerializer).emit(Example(x = 1, y = "hi"))
```

## Available Backends

### Local

Simply redirects messages back to itself. There will be no external connections. Will only work if there is strictly one 
instance of the server. The moment you have multiple instances you MUST use another option for a Pub/Sub or server 
behavior may be as expected. This is used most often for local development and unit tests.

```json5
// settings.json
{
  "pubsub": { "url": "local" }
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
  "pubsub": { "url": "redis://" }
}
```

#### Testing locally

```json5
// settings.json
{
  // Standard redis connection string
  "pubsub": { "url": "redis-test" }
}
```
