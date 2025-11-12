package com.lightningkite.lightningserver.definition

import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.Setting
import com.lightningkite.services.SettingContext
import com.lightningkite.services.terraform.TerraformNeed
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Represents a computation that can be deferred until runtime and executed asynchronously.
 *
 * This is similar to [Runtime] but supports suspend functions, allowing for async operations
 * like network calls or file I/O during initialization.
 *
 * @param T The type of value produced when awaited
 */
public fun interface RuntimeDeferred<out T> {
    /**
     * Executes the deferred computation and returns its result.
     *
     * @return The computed value
     */
    context(server: ServerRuntime)
    public suspend fun await(): T

    /**
     * A caching wrapper that executes the deferred computation only once.
     *
     * Subsequent calls to [await] return the cached value without re-executing.
     *
     * **Thread Safety**: The cache is thread-safe. Concurrent calls will only execute
     * the computation once, with other threads waiting for the result.
     */
    @OptIn(ExperimentalAtomicApi::class)
    public data class Cached<out T>(private val wraps: RuntimeDeferred<T>) : RuntimeDeferred<T> {
        @kotlin.concurrent.Volatile private var deferred: Deferred<T>? = null
        private val mutex = Mutex()
        context(server: ServerRuntime)
        override suspend fun await(): T {
            return mutex.withLock {
                deferred?.let { return@withLock it }
                val n = GlobalScope.async { wraps.await() }
                deferred = n
                n
            }.await()
        }
    }
}

/**
 * Represents a computation that can be deferred until runtime and executed synchronously.
 *
 * Runtime is the core abstraction for lazy initialization and dependency injection in Lightning Server.
 * It allows values to be computed on-demand within a [ServerRuntime] context, providing access to
 * settings, services, and other runtime resources.
 *
 * Example:
 * ```kotlin
 * val database = Runtime { databaseSetting().connect() }
 * // Later, when needed:
 * with(serverRuntime) {
 *     val db = database() // Computed here
 * }
 * ```
 *
 * @param T The type of value produced when invoked
 */
public fun interface Runtime<out T> : RuntimeDeferred<T> {
    /**
     * Executes the computation and returns its result.
     *
     * @return The computed value
     */
    context(server: ServerRuntime)
    public operator fun invoke(): T

    context(server: ServerRuntime)
    override suspend fun await(): T = invoke()

    /**
     * A caching wrapper that executes the computation only once.
     *
     * Subsequent calls to [invoke] return the cached value without re-executing.
     * This is useful for expensive operations that should only run once per server instance.
     *
     * **Thread Safety**: The cache is thread-safe. Concurrent calls will only execute
     * the computation once, with other threads waiting for the result.
     */
    public data class Cached<out T>(private val wraps: Runtime<T>) : Runtime<T> {
        @Volatile
        private var cache: NullWrapper<T>? = null
        private val lock = Any()

        context(server: ServerRuntime)
        override operator fun invoke(): T =
            cache?.value ?: synchronized(lock) {
                cache?.value ?: wraps.invoke().also { cache = NullWrapper(it) }
            }
    }

    /**
     * A Runtime wrapper for a constant value.
     *
     * Always returns the same value without requiring a ServerRuntime context.
     * Useful for testing or when a value is known at compile time.
     */
    public data class Constant<out T>(public val value: T) : Runtime<T> {
        context(server: ServerRuntime)
        override operator fun invoke(): T = value

        public operator fun invoke(): T = value
    }
}

/**
 * Transforms the result of this Runtime using the given function.
 *
 * @param transform Function to apply to the Runtime's value
 * @return A new Runtime that applies the transformation
 */
public fun <T, R> Runtime<T>.map(transform: context(ServerRuntime) (T) -> R): Runtime<R> = Runtime { transform(this()) }

/**
 * Transforms the result of this RuntimeDeferred using an async function.
 *
 * @param transform Suspend function to apply to the RuntimeDeferred's value
 * @return A new RuntimeDeferred that applies the transformation
 */
public fun <T, R> RuntimeDeferred<T>.mapSuspending(transform: suspend context(ServerRuntime) (T) -> R): RuntimeDeferred<R> =
    RuntimeDeferred { transform(this.await()) }

/**
 * Represents a server configuration setting with serialization and Terraform integration.
 *
 * ServerSetting bridges the gap between configuration (in settings.json) and runtime values.
 * It handles:
 * - Serialization/deserialization of settings
 * - Default values when settings are not provided
 * - Transformation from raw settings to usable runtime values
 * - Terraform resource generation
 *
 * Example:
 * ```kotlin
 * val database = setting("database", Database.Settings())
 *
 * // In a handler:
 * val db = database() // Returns the initialized database connection
 * ```
 *
 * @param SETTING The raw setting type (what's in settings.json)
 * @param RESULT The runtime result type (what handlers use)
 */
public interface ServerSetting<SETTING, RESULT> : Runtime<RESULT>, TerraformNeed<SETTING> {
    /** The unique name of this setting (used as the key in settings.json). */
    override val name: String

    /** Serializer for reading/writing the setting from/to JSON. */
    override val serializer: KSerializer<SETTING>

    /** The default value used when the setting is not provided in settings.json. */
    override val default: SETTING

    /** Whether this setting can be omitted from settings.json. */
    public val optional: Boolean get() = false

    /**
     * Transforms the raw setting value into the runtime result.
     *
     * This is where you initialize services, connect to databases, etc.
     *
     * @param setting The deserialized setting value
     * @return The runtime value (e.g., database connection, API client)
     */
    context(settings: SettingContext)
    public fun get(setting: SETTING): RESULT

    /**
     * A ServerSetting where the setting and result are the same type.
     *
     * Use this when no transformation is needed - the setting value is used directly.
     */
    public interface Direct<Setting> : ServerSetting<Setting, Setting> {
        context(settings: SettingContext)
        override fun get(setting: Setting): Setting = setting
    }

    context(server: ServerRuntime)
    override fun invoke(): RESULT = server.settings.get(this)
}

/** Internal wrapper to allow caching of nullable values. */
@JvmInline
private value class NullWrapper<T>(val value: T)

/** Internal implementation of ServerSetting with custom getter logic. */
private data class BasicServerSetting<SETTING, RESULT>(
    override val name: String,
    override val default: SETTING,
    override val serializer: KSerializer<SETTING>,
    override val instructions: String = "No instructions",
    override val optional: Boolean,
    private val getter: SettingContext.(SETTING) -> RESULT
) : ServerSetting<SETTING, RESULT> {
    @Volatile
    private var cached: NullWrapper<RESULT>? = null
    private val lock = Any()

    context(settings: SettingContext)
    override fun get(setting: SETTING): RESULT =
        cached?.value ?: synchronized(lock) {
            cached?.value ?: getter(settings, setting).also { cached = NullWrapper(it) }
        }

    context(server: ServerRuntime)
    override fun invoke(): RESULT =
        cached?.value ?: synchronized(lock) {
            cached?.value ?: server.settings.get(this)
        }
}

/**
 * Creates a ServerSetting with a custom transformation function.
 *
 * Use this when the setting needs to be transformed into a different runtime value.
 *
 * Example:
 * ```kotlin
 * val database = ServerSetting(
 *     name = "database",
 *     default = DatabaseConfig("localhost", 5432),
 *     serializer = DatabaseConfig.serializer(),
 *     instructions = "Database connection settings"
 * ) { config ->
 *     // Transform config into actual database connection
 *     DatabaseConnection.connect(config)
 * }
 * ```
 *
 * @param name Unique setting name
 * @param default Default value when not in settings.json
 * @param serializer KSerializer for the setting type
 * @param instructions User-facing instructions for configuring this setting
 * @param optional Whether the setting can be omitted
 * @param getter Function to transform setting into runtime value
 */
public fun <SETTING, RESULT> ServerSetting(
    name: String,
    default: SETTING,
    serializer: KSerializer<SETTING>,
    instructions: String = "No instructions",
    optional: Boolean = false,
    getter: SettingContext.(SETTING) -> RESULT
) : ServerSetting<SETTING, RESULT> =
    BasicServerSetting(name, default, serializer, instructions, optional, getter)

/**
 * Creates a ServerSetting from a [Setting] instance.
 *
 * This overload is for settings that implement the Setting interface and handle
 * their own transformation logic.
 *
 * @param name Unique setting name
 * @param default Default Setting instance
 * @param serializer KSerializer for the setting type
 * @param instructions User-facing instructions
 * @param optional Whether the setting can be omitted
 */
public fun <SETTING : Setting<RESULT>, RESULT> ServerSetting(
    name: String,
    default: SETTING,
    serializer: KSerializer<SETTING>,
    instructions: String = "No instructions",
    optional: Boolean = false
): ServerSetting<SETTING, RESULT> =
    ServerSetting(name, default, serializer, instructions, optional) { it.invoke(name, this) }

/** Internal implementation of Direct ServerSetting (no transformation). */
private data class BasicDirectServerSetting<SETTING>(
    override val name: String,
    override val default: SETTING,
    override val serializer: KSerializer<SETTING>,
    override val instructions: String = "No instructions",
    override val optional: Boolean,
) : ServerSetting.Direct<SETTING> {
    @Volatile
    private var cached: NullWrapper<SETTING>? = null
    private val lock = Any()

    context(server: ServerRuntime)
    override fun invoke(): SETTING =
        cached?.value ?: synchronized(lock) {
            cached?.value ?: server.settings.get(this).also { cached = NullWrapper(it) }
        }
}

/**
 * Creates a Direct ServerSetting where the setting value is used as-is.
 *
 * Use this when no transformation is needed - the setting JSON directly represents
 * the value you want to use at runtime.
 *
 * Example:
 * ```kotlin
 * val debugMode = ServerSetting(
 *     name = "debug",
 *     default = false,
 *     serializer = Boolean.serializer(),
 *     instructions = "Enable debug mode"
 * )
 * ```
 *
 * @param name Unique setting name
 * @param default Default value when not in settings.json
 * @param serializer KSerializer for the setting type
 * @param instructions User-facing instructions
 * @param optional Whether the setting can be omitted
 */
public fun <SETTING> ServerSetting(
    name: String,
    default: SETTING,
    serializer: KSerializer<SETTING>,
    instructions: String = "No instructions",
    optional: Boolean = false
) : ServerSetting.Direct<SETTING> =
    BasicDirectServerSetting(name, default, serializer, instructions, optional)

/*
 * TODO: API Recommendations for ServerSetting.kt
 *
 * 1. **THREAD SAFETY**: The Cached implementations use mutable var without synchronization.
 *    Document that these are not thread-safe, or add synchronization for concurrent access.
 *    In a typical server, settings are initialized once during startup, but this should be documented.
 *
 * 2. The default instructions "No instructions" is not helpful. Consider making instructions
 *    a required parameter or using a more descriptive default like "No configuration instructions provided".
 *
 * 3. Add validation support to ServerSetting to catch invalid configurations early:
 *    - fun validate(setting: SETTING): List<String> // Returns validation errors
 *
 * 4. Consider adding a ServerSetting.lazy() variant that defers initialization until first use
 *    rather than during settings loading. Useful for optional services.
 *
 * 5. The NullWrapper is used to cache nullable values, but the pattern could be clearer.
 *    Consider using a sealed class: sealed class CacheState<out T> { object Empty, data class Filled<T>(val value: T) }
 *
 * 6. Add a way to observe setting changes for hot-reloading:
 *    - interface SettingObserver<T> { fun onSettingChanged(old: T, new: T) }
 */


