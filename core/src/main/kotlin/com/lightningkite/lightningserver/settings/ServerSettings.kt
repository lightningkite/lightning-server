package com.lightningkite.lightningserver.settings

import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.logger
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.otel.applyToLogback
import kotlin.contracts.*

/**
 * Manages server configuration settings with a two-phase lifecycle (configuration → ready).
 *
 * ServerSettings handles the complete lifecycle of server configuration:
 * 1. **Configuration phase**: Settings are loaded from files or set programmatically
 * 2. **Ready phase**: Settings are validated, transformed, and made available for runtime use
 *
 * The class maintains two separate registries:
 * - **serializable**: Raw serialized values from configuration files or `set()` calls
 * - **goal**: Transformed result values from applying the setting's getter function
 *
 * **Typical usage pattern:**
 * ```kotlin
 * val serverSettings = ServerSettings(Server.build().settings)
 *
 * // Configuration phase
 * serverSettings.loadFromFile(KFile("settings.json"), mySerializersModule)
 *
 * // Ready phase
 * with(serverRuntime) {
 *     serverSettings.ready()  // Validates and transforms all settings
 *
 *     // Now settings can be accessed
 *     val dbInstance = serverSettings.get(Server.database)
 * }
 * ```
 *
 * @property settings The complete set of [ServerSetting] instances to manage
 * @property ready Indicates whether settings have been validated and are ready for use
 */
public class ServerSettings private constructor(
    // duplicate settings can be ignored
    public val settings: Set<ServerSetting<*, *>>,
    public val overrides: Map<ServerSetting<*, *>, Runtime<*>>,
) {
    public constructor(
        settings: Collection<ServerSetting<*, *>>,
        vararg overrides: Override<*, *>,    // ensure override type safety
    ) : this(
        (settings + overrides.map { it.deferTo }.filterIsInstance<ServerSetting<*, *>>()).toSet(),
        buildMapRegistry {
            overrides.forEach { register(it.override, it.deferTo) }
        }
    )

    public constructor(definition: ServerDefinition) : this(
        definition.settings.toSet(),
        definition.settingOverrides
    )   // We can trust internal definitions to be safe, as they are registered through controlled dsl

    init {
        this.settings
            .groupBy { it.name }
            .filterValues { it.size > 1 }
            .takeIf { it.isNotEmpty() }
            ?.let { conflicts ->
                throw ConflictingSettingsException(conflicts)
            }

        // Check for circular references in overrides
        fun findCycle(
            start: ServerSetting<*, *>,
            visited: Set<ServerSetting<*, *>> = emptySet(),
        ): List<ServerSetting<*, *>>? {
            if (start in visited) return listOf(start)
            val target = overrides[start] as? ServerSetting<*, *> ?: return null
            val cycle = findCycle(target, visited + start)
            return cycle?.let { listOf(start) + it }
        }
        overrides.keys.forEach { setting ->
            findCycle(setting)?.let { cycle ->
                throw CircularOverrideException(cycle)
            }
        }
    }

    public var ready: Boolean = false
        private set

    private var usingDefaults: Boolean = false

    private val serializable: MapRegistry<ServerSetting<*, *>, Any?> = MapRegistry()

    // Transformed results, published as an immutable snapshot. Reads are lock-free: once a setting is resolved,
    // its value is visible here for good (the @Volatile guarantees other threads see the new snapshot). Writes
    // replace the whole map under [resolveLock] (copy-on-write) — cheap because each setting is resolved at most
    // once, and in production every setting is pre-resolved single-threaded during ready(). An immutable Map
    // (not a ConcurrentHashMap) is used so a null result is stored naturally, with no sentinel.
    @Volatile
    private var goal: Map<ServerSetting<*, *>, Any?> = emptyMap()

    // Guards resolution so each setting is transformed exactly once, even if several threads first request it
    // at once. A single reentrant lock — not per-setting — is deliberate: a setting's getter may resolve its
    // dependencies by calling get() again on the same thread, which re-enters this lock harmlessly, whereas
    // per-setting locks could deadlock between two mutually-dependent settings.
    private val resolveLock = Any()

    // Track settings currently being resolved to detect circular dependencies at runtime
    private val resolving: ThreadLocal<MutableSet<ServerSetting<*, *>>>? =
        if (overrides.isNotEmpty()) ThreadLocal.withInitial { mutableSetOf() } else null


    /**
     * Sets the serializable value for this setting.
     *
     * This stores the raw configuration value that will later be transformed by the
     * setting's getter function when [ready] is called.
     *
     * @param value The serializable value to store
     * @receiver The setting to configure
     * @throws IllegalStateException if settings are already marked as ready
     */
    public infix fun <SERIALIZABLE> ServerSetting<SERIALIZABLE, *>.set(value: SERIALIZABLE) {
        if (ready) throw IllegalStateException("Settings are marked as ready.")
        serializable.register(this, value as Any?)
    }

    /**
     * Explicitly sets this setting to use its default value.
     *
     * This is typically unnecessary as defaults are used automatically for unset optional settings,
     * but can be useful for clarity or to reset a previously set value.
     *
     * @receiver The setting to configure
     * @throws IllegalStateException if settings are already marked as ready
     */
    public fun <SERIALIZABLE> ServerSetting<SERIALIZABLE, *>.useDefault() {
        if (ready) throw IllegalStateException("Settings are marked as ready.")
        serializable.register(this, default)
    }

    /**
     * Directly sets the transformed result value for this setting, bypassing serialization.
     *
     * This is primarily useful for testing where you want to inject mock implementations
     * directly rather than going through the normal serialization/deserialization flow.
     *
     * @param value The transformed result value to store
     * @receiver The setting to configure
     * @throws IllegalStateException if settings are already marked as ready
     */
    public infix fun <RESULT> ServerSetting<*, RESULT>.setStatic(value: RESULT) {
        if (ready) throw IllegalStateException("Settings are marked as ready.")
        // Pre-ready configuration is single-threaded, so a plain copy-on-write assignment is enough.
        goal = goal + (this to value)
    }

    /**
     * Includes a map of settings values, typically from a configuration file.
     *
     * This is called internally by [loadFromFile] but can also be used to programmatically
     * load settings from other sources.
     *
     * @param map A map of settings to their serializable values
     * @throws IllegalStateException if settings are already marked as ready
     */
    public fun include(map: Map<ServerSetting<*, *>, Any?>) {
        if (ready) throw IllegalStateException("Settings are marked as ready.")
        serializable.include(map)
    }

    /**
     * Includes a single key value, typically from a configuration file.
     *
     * This is called internally when instantiating settings but can also be used to programmatically
     * load settings from other sources.
     *
     * @param key A ServerSetting.
     * @param value The value to fulfill the setting with.
     * @throws IllegalStateException if settings are already marked as ready
     */
    public fun include(key: ServerSetting<*, *>, value:Any?) {
        if (ready) throw IllegalStateException("Settings are marked as ready.")
        serializable.register(key, value)
    }

    /**
     * Marks settings as ready without validation, using defaults for any unset settings.
     *
     * **Warning**: This bypasses all validation and transformation. It's primarily intended
     * for testing scenarios where you want to skip the normal ready phase.
     * In production code, use [ready] instead to ensure proper validation.
     */
    public fun readyUsingDefaults() {
        ready = true
        usingDefaults = true
    }

    /**
     * Validates and prepares all settings for runtime use.
     *
     * This function performs the following operations:
     * 1. Validates that all required settings have been provided
     * 2. Marks the settings as ready (after this, no more configuration changes are allowed)
     * 3. Applies logging configuration if [loggingSettings] is present
     * 4. Pre-loads and transforms all settings by calling their getter functions
     * 5. Collects and reports any errors that occur during transformation
     *
     * **Important**: After this function succeeds, settings can be accessed via [get] but
     * can no longer be modified via [set], [setStatic], or [include].
     *
     * @receiver A [ServerRuntime] context required for logging and setting transformation
     * @throws IllegalStateException if any required settings are missing
     * @throws Error if any settings fail to transform (includes detailed logging of all failures)
     */
    context(server: ServerRuntime)
    public fun ready() {
        val missing = settings.minus(serializable.keys + goal.keys + overrides.keys)
        if (missing.isNotEmpty()) throw IllegalStateException("Settings ${missing.joinToString { it.name }} are missing.")
        ready = true

        val errors = mutableMapOf<ServerSetting<*, *>, Exception>()

        if (loggingSettings in settings)
            get(loggingSettings).applyToLogback()
        settings.forEach { setting ->
            try {
                get(setting)
            } catch (e: Exception) {
                errors[setting] = e
            }
        }
        if (errors.isNotEmpty()) {
            errors.forEach { (setting, error) ->
                server.logger.error { "Invalid value for ${setting.name}" }
                server.logger.error { error.stackTraceToString() }
            }
            throw Error("Failed to preload all settings")
        }
    }

    /**
     * Retrieves the transformed result value for a setting.
     *
     * This function returns the final transformed value of a setting by:
     * 1. Checking if the result has been cached in the goal registry
     * 2. If not cached, applying the setting's getter function to the serializable value
     * 3. Caching the result for subsequent calls
     *
     * The getter function is only called once per setting; subsequent calls return the cached value.
     *
     * **Circular Dependency Detection**: This function tracks settings being resolved on the current
     * thread and will throw [CircularOverrideException] if a circular dependency is detected at runtime.
     * This catches cases where a setting's getter function calls `get()` for another setting that
     * eventually calls back to the original setting.
     *
     * @param key The setting to retrieve
     * @return The transformed result value for this setting
     * @receiver A [ServerRuntime] context required for the setting's transformation
     * @throws IllegalStateException if settings are not ready yet (call [ready] first)
     * @throws CircularOverrideException if a circular dependency is detected during resolution
     */
    @Suppress("UNCHECKED_CAST")
    context(_: ServerRuntime)
    public fun <SERIALIZABLE, RESULT> get(key: ServerSetting<SERIALIZABLE, RESULT>): RESULT {
        if (!ready) throw IllegalStateException("Settings not ready yet.")

        // Lock-free fast path: once a setting is resolved its value lives in [goal] forever, read without locking.
        // containsKey (not a null check) because a resolved value may legitimately be null.
        goal.let { if (it.containsKey(key)) return it[key] as RESULT }

        // Slow path: resolve under [resolveLock] so each setting is transformed at most once, even when several
        // threads first request it concurrently. The per-thread [resolving] set still detects circular deps.
        synchronized(resolveLock) {
            // Re-check now that we hold the lock; another thread may have resolved it while we waited.
            goal.let { if (it.containsKey(key)) return it[key] as RESULT }

            // Check for circular dependency during resolution
            val currentlyResolving = resolving?.get()
            if (currentlyResolving != null && key in currentlyResolving) {
                throw CircularOverrideException(currentlyResolving.toList() + key)
            }

            currentlyResolving?.add(key)
            val result = try {
                overrides[key]?.let { defer ->
                    serializable[key]
                        ?.let { key.get(it as SERIALIZABLE) } // specified in settings file
                        ?: defer.invoke() // use defer if unspecified
                } ?: key.get(
                    serializable.getOrElse(key) {
                        if (usingDefaults) key.default
                        else throw IllegalStateException(
                            buildString {
                                append("Setting ${key.name} was not registered. ")
                                append("This is likely because you used it in a Runtime calculation without registering it as a dependency. ")
                                currentlyResolving
                                    ?.takeIf { it.minus(key).isNotEmpty() }
                                    ?.let {
                                        append("Occurred while resolving ${it.joinToString(" -> ") { it.name }}.")
                                    }
                            }
                        )
                    } as SERIALIZABLE
                )
            } finally {
                currentlyResolving?.remove(key)
            }
            // Publish by re-reading [goal] (never a snapshot captured before resolution): the getter above may
            // have recursively resolved dependencies and published them, and those additions must be preserved.
            goal = goal + (key to result)
            return result as RESULT
        }
    }

    // For some dumb fucking reason kotlin made this internal, and it's what getOrElse should do in the first place!
    @OptIn(ExperimentalContracts::class)
    private inline fun <K, V> Map<K, V>.getOrElse(key: K, defaultValue: () -> V): V {
        contract { callsInPlace(defaultValue, InvocationKind.AT_MOST_ONCE) }
        val value = get(key)
        if (value == null && !containsKey(key)) {
            return defaultValue()
        } else {
            @Suppress("UNCHECKED_CAST")
            return value as V
        }
    }


    /**
     * Returns all settings with their serializable values.
     *
     * This provides a snapshot of the raw configuration values before transformation.
     * Useful for debugging, logging, or re-serializing settings to a file.
     *
     * @return A map of all settings to their serializable values (using defaults for unset settings)
     */
    public fun allSerializable(): Map<ServerSetting<*, *>, Any?> =
        settings.associateWith { serializable[it] ?: it.default }

    /**
     * Returns all settings with their transformed result values.
     *
     * This triggers transformation for any settings that haven't been accessed yet.
     * Useful for pre-warming all settings or debugging the complete configuration state.
     *
     * @return A map of all settings to their transformed result values
     * @receiver A [ServerRuntime] context required for transformation
     * @throws IllegalStateException if settings are not ready yet
     */
    context(_: ServerRuntime)
    public fun allGoals(): Map<ServerSetting<*, *>, Any?> = settings.associateWith { get(it) }

    private fun copy(
        settings: Set<ServerSetting<*, *>> = this.settings,
        overrides: Map<ServerSetting<*, *>, Runtime<*>> = this.overrides,
    ) = ServerSettings(settings, overrides).also {
        it.serializable.include(this.serializable)
        // Safe to share the reference: [goal] is immutable, and copy-on-write writes replace it rather than mutate.
        it.goal = this.goal
    }

    public operator fun plus(requirement: ServerSetting<*, *>): ServerSettings = copy(settings + requirement)
    public operator fun plus(requirements: Collection<ServerSetting<*, *>>): ServerSettings =
        copy(settings + requirements)

    public data class Override<S, R>(val override: ServerSetting<S, R>, val deferTo: Runtime<R>)
}

/*
 * TODO: API Recommendations for ServerSettings.kt
 *
 * 1. The `ready()` function throws `Error` (not Exception) when settings fail to preload.
 *    This is unusual - consider using a more specific exception type that extends Exception.
 *
 * 2. The missing settings check in `ready()` doesn't distinguish between truly required settings
 *    and optional settings with no default. The error message could be more helpful.
 *
 *
 * 4. `readyUsingDefaults()` bypasses all validation with a warning, but doesn't actually
 *    enforce that defaults exist for all settings. Could still throw during get().
 *
 * 5. The serializable registry uses `Any?` type which loses type safety. A setting configured
 *    with the wrong type won't be caught until transformation time, potentially late in startup.
 *
 * 6. No way to query if a specific setting has been configured before calling ready().
 *    Adding `isSet(setting)` or `hasValue(setting)` would be useful for conditional logic.
 *
 * 7. The `allSerializable()` and `allGoals()` functions create new maps on every call.
 *    Consider caching these after ready() since they won't change.
 *
 * 8. No mechanism to reload or hot-reload settings after ready(). Once ready, settings are
 *    permanently locked. Consider adding support for reloadable settings.
 */
