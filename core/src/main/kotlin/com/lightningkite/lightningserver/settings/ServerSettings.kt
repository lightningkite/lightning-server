package com.lightningkite.lightningserver.settings

import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.lightningserver.definition.builder.MapRegistry
import com.lightningkite.lightningserver.definition.builder.getOrRegister
import com.lightningkite.lightningserver.definition.builder.include
import com.lightningkite.lightningserver.definition.loggingSettings
import com.lightningkite.lightningserver.logger
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.otel.applyToLogback

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
public class ServerSettings(public val settings: Set<ServerSetting<*, *>>) {
    init {
        settings
            .distinct()     // Allow duplicate instances (same object registered multiple times)
            .groupBy { it.name }
            .filterValues { it.size > 1 }
            .takeIf { it.isNotEmpty() }
            ?.let { conflicts ->
                throw ConflictingSettingsException(conflicts)
            }
    }

    public var ready: Boolean = false
        private set

    private val serializable: MapRegistry<ServerSetting<*, *>, Any?> = MapRegistry()
    private val goal: MapRegistry<ServerSetting<*, *>, Any?> = MapRegistry()


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
        goal.register(this, value as Any?)
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
     * Marks settings as ready without validation, using defaults for any unset settings.
     *
     * **Warning**: This bypasses all validation and transformation. It's primarily intended
     * for testing scenarios where you want to skip the normal ready phase.
     * In production code, use [ready] instead to ensure proper validation.
     */
    public fun readyUsingDefaults() {
        ready = true
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
        val missing = settings.minus(serializable.keys + goal.keys)
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
     * @param key The setting to retrieve
     * @return The transformed result value for this setting
     * @receiver A [ServerRuntime] context required for the setting's transformation
     * @throws IllegalStateException if settings are not ready yet (call [ready] first)
     */
    @Suppress("UNCHECKED_CAST")
    context(_: ServerRuntime)
    public fun <SERIALIZABLE, RESULT> get(key: ServerSetting<SERIALIZABLE, RESULT>): RESULT {
        if (!ready) throw IllegalStateException("Settings not ready yet.")
        return goal.getOrRegister(key) {
            key.get(
                serializable.getOrElse(key) { key.default } as SERIALIZABLE
            )
        } as RESULT
    }

    /**
     * Returns all settings with their serializable values.
     *
     * This provides a snapshot of the raw configuration values before transformation.
     * Useful for debugging, logging, or re-serializing settings to a file.
     *
     * @return A map of all settings to their serializable values (using defaults for unset settings)
     */
    public fun allSerializable(): Map<ServerSetting<*, *>, Any?> = settings.associateWith { serializable[it] ?: it.default }

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

    public operator fun plus(requirement: ServerSetting<*, *>): ServerSettings = ServerSettings(settings + requirement)
    public operator fun plus(requirements: Collection<ServerSetting<*, *>>): ServerSettings = ServerSettings(settings + requirements)
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
 * 3. The `get()` function performs lazy transformation but the caching isn't thread-safe.
 *    If called concurrently during initial ready phase, could transform the same setting twice.
 *    (Though this is documented in ServerSetting.kt TODO, worth noting here too.)
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
