# Extension System

The Lightning Server extension system provides a type-safe way to attach arbitrary data to server components without modifying their core interfaces. This system is defined in the `definition` package and is used throughout Lightning Server for attaching metadata to endpoints, builders, and definitions.

## Core Concepts

The extension system is built around strongly-typed keys and values:

- **Extensions** - Read-only access to extension values via keys
- **MutableExtensions** - Read-write access to extension values
- **Extended** - Interface for types that provide `Extensions`
- **Extendable** - Interface for types that provide `MutableExtensions`

## Basic Usage

### Defining a Key

Extension keys are defined as singleton objects:

```kotlin
object MyDataKey : MutableExtensions.Key<String>
```

### Using Extensions Directly

```kotlin
// On an Extendable type (like ServerBuilder)
val builder = ServerBuilder()
builder.extensions[MyDataKey] = "Hello"
val value = builder.extensions[MyDataKey] // "Hello"

// Removing an extension
builder.extensions[MyDataKey] = null
```

### Using Property Delegation

The recommended approach is to define extension properties using delegation:

```kotlin
object NameKey : MutableExtensions.Key<String>

// Using the delegation operator (recommended)
var ServerBuilder.name: String? by NameKey

// Manual delegation (if you need custom logic)
var ServerBuilder.name: String?
    get() = extensions[NameKey]
    set(value) { extensions[NameKey] = value }

// Usage
val builder = ServerBuilder()
builder.name = "MyServer"
println(builder.name) // "MyServer"
```

## Real Example: Logger Extension

From `core/src/main/kotlin/com/lightningkite/lightningserver/logging.kt`:

```kotlin
private object LoggerKey : MutableExtensions.Key<KLogger> {
    fun default(): KLogger = KotlinLogging.logger("com.lightningkite.lightningserver")
}

public var ServerBuilder.logger: KLogger
    get() = extensions[LoggerKey] ?: LoggerKey.default()
    set(value) { extensions[LoggerKey] = value }

public val ServerDefinition.logger: KLogger
    get() = extensions[LoggerKey] ?: LoggerKey.default()
```

This allows users to customize the logger:

```kotlin
object Server : ServerBuilder() {
    init {
        logger = KotlinLogging.logger("myapp")
    }
}
```

## Degrading Keys

`DegradingKey` provides different types for read vs. write access. This is useful when you want mutable access during building, but read-only access at runtime.

### Example: Mutable List to Read-Only List

```kotlin
object ArgumentsKey : MutableExtensions.DegradingKey<MutableList<String>, List<String>> {
    override fun default(): MutableList<String> = mutableListOf()
    override fun MutableList<String>.include(other: List<String>, pathSpec: PathSpec0) {
        addAll(other)
    }
}

// On Extendable types (like ServerBuilder), you get MutableList
val ServerBuilder.arguments: MutableList<String> by ArgumentsKey

// On Extended types (like ServerDefinition), you get read-only List
val ServerDefinition.arguments: List<String> by ArgumentsKey
```

When building:
```kotlin
object Server : ServerBuilder() {
    init {
        arguments.add("--verbose")
        arguments.add("--port=8080")
    }
}

val definition = Server.build()
// definition.arguments is now List<String> (immutable)
```

## Registry Extensions

The framework provides convenience interfaces for registry-based extensions:

### MapRegistryExtension

```kotlin
object HandlersKey : MapRegistryExtension<String, HttpHandler<*>>

val ServerBuilder.handlers: MapRegistry<String, HttpHandler<*>> by HandlersKey
val ServerDefinition.handlers: Map<String, HttpHandler<*>> by HandlersKey
```

This gives you a mutable `MapRegistry` during building and an immutable `Map` at runtime.

### ListRegistryExtension

```kotlin
object InterceptorsKey : ListRegistryExtension<HttpInterceptor>

val ServerBuilder.interceptors: ListRegistry<HttpInterceptor> by InterceptorsKey
val ServerDefinition.interceptors: List<HttpInterceptor> by InterceptorsKey
```

This gives you a mutable `ListRegistry` during building and an immutable `List` at runtime.

## Cached Properties

Use `.cache()` to create properties with lazy initialization:

```kotlin
object ConfigKey : MutableExtensions.Key<Config>

var ServerBuilder.config: Config by ConfigKey.cache {
    Config(defaultName = "server-${hashCode()}")
}

// First access creates the default
val builder = ServerBuilder()
val config1 = builder.config // Creates default Config
val config2 = builder.config // Returns same instance
assert(config1 === config2)

// Can still be overridden
builder.config = Config("custom")
```

## Extension Merging

When modules are included in `ServerBuilder`, their extensions are merged:

- **Regular keys**: Last value wins (putIfAbsent behavior)
- **Degrading keys**: Values are combined using the `include` function

```kotlin
object TagsKey : MutableExtensions.DegradingKey<MutableList<String>, List<String>> {
    override fun default(): MutableList<String> = mutableListOf()
    override fun MutableList<String>.include(other: List<String>, pathSpec: PathSpec0) {
        addAll(other) // Combine tags from included modules
    }
}
```

## Utility Functions

### getOrPut

```kotlin
val value = extensions.getOrPut(MyKey) {
    // Compute default value
    ExpensiveObject()
}
```

### toSealedExtensions

Convert mutable extensions to immutable:

```kotlin
val mutable = MutableExtensions()
mutable[MyKey] = "value"

val sealed = mutable.toSealedExtensions()
// sealed is now immutable Extensions
```

### toMutableExtensions

Copy extensions to a new mutable instance:

```kotlin
val copy = extensions.toMutableExtensions()
copy[MyKey] = "modified" // Doesn't affect original
```

## Best Practices

1. **Define keys as objects** - Use singleton objects for keys
   ```kotlin
   object MyKey : MutableExtensions.Key<String>
   ```

2. **Create extension properties** - Make extensions discoverable
   ```kotlin
   var ServerBuilder.myProperty: String? by MyKey
   ```

3. **Provide defaults** - Handle missing values gracefully
   ```kotlin
   val value = extensions[MyKey] ?: "default"
   ```

4. **Use degrading keys for builder patterns** - Mutable during building, immutable at runtime
   ```kotlin
   object ListKey : MutableExtensions.DegradingKey<MutableList<T>, List<T>>
   ```

5. **Document your extensions** - Add KDoc to extension properties
   ```kotlin
   /**
    * Custom timeout for this endpoint in milliseconds.
    */
   var HttpEndpoint<*>.timeout: Long? by TimeoutKey
   ```

## See Also

- `core/src/main/kotlin/com/lightningkite/lightningserver/definition/Extensions.kt` - Core extension types
- `core/src/main/kotlin/com/lightningkite/lightningserver/definition/Extensions.ext.kt` - Extension utilities
- `core/src/main/kotlin/com/lightningkite/lightningserver/logging.kt` - Real-world example
