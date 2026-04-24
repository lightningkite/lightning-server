package com.lightningkite.lightningserver.settings

import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.services.kfile.KFile

/**
 * Exception thrown when required settings are missing from a settings file.
 *
 * This exception is thrown during settings loading when one or more non-optional settings
 * are not present in the loaded configuration file. A suggested settings file is automatically
 * generated with default values for all missing settings to help users complete their configuration.
 *
 * @property missing The set of [ServerSetting] instances that were not found in the configuration
 * @property suggestedFile A [KFile] reference to the auto-generated file containing suggested values
 *
 * @see ServerSettings.loadFromFile
 */
public class IncompleteSettingsException(
    public val missing: Set<ServerSetting<*, *>>,
    public val suggestedFile: KFile,
) :
    Exception("Missing keys ${missing.joinToString { it.name }}. Created suggested settings at ${suggestedFile.resolved.path}")

/**
 * Exception thrown when attempting to load a settings file that does not exist.
 *
 * When this exception is thrown, a new settings file is automatically created at the specified
 * location with default values for all registered settings. This allows applications to work
 * out-of-the-box on first run by generating a starter configuration.
 *
 * @param suggestedFile A [KFile] reference to the auto-generated settings file
 *
 * @see ServerSettings.loadFromFile
 */
public class MissingSettingFile(suggestedFile: KFile) :
    Exception("Settings file does not exists. Created file at ${suggestedFile.resolved.path}")

/**
 * Exception thrown when multiple [ServerSetting] instances are registered with the same name.
 *
 * This exception is thrown during [ServerSettings] initialization when the validation check
 * detects that different settings share the same name. While duplicate settings
 * are allowed, different settings with the same name create ambiguity and potential type conflicts.
 *
 * **Why this check exists:**
 * - Prevents type conflicts (e.g., one setting expects `Int`, another expects `String`)
 * - Ensures deterministic behavior when retrieving settings by name
 * - Catches configuration errors early during application startup
 *
 * **Example of conflict:**
 * ```kotlin
 * val portSetting1 = ServerSetting<Int>("port", 8080)
 * val portSetting2 = ServerSetting<String>("port", "8080") // Conflict!
 * ServerSettings(listOf(portSetting1, portSetting2)) // Throws ConflictingSettingsException
 * */
public class ConflictingSettingsException(conflicting: Map<String, Collection<ServerSetting<*, *>>>) :
    IllegalStateException("Settings found with conflicting names. All server settings must have unique names. Conflicts: ${conflicting.keys}")

/**
 * Exception thrown when circular references are detected in setting overrides.
 *
 * This exception is thrown during [ServerSettings] initialization when the validation check
 * detects that setting overrides form a cycle (e.g., A overrides B, B overrides C, C overrides A).
 *
 * **Why this check exists:**
 * - Prevents infinite loops when resolving setting values
 * - Catches configuration errors early during application startup
 *
 * **Example of circular override:**
 * ```kotlin
 * val settingA = ServerSetting<Int>("a", 1)
 * val settingB = ServerSetting<Int>("b", 2)
 * ServerSettings(
 *     listOf(settingA, settingB),
 *     Override(settingA, settingB),
 *     Override(settingB, settingA)  // Creates a cycle!
 * ) // Throws CircularOverrideException
 * ```
 *
 * @param cycle The list of [ServerSetting] instances that form the circular reference chain
 */
public class CircularOverrideException(cycle: List<ServerSetting<*, *>>) :
    IllegalStateException("Circular reference detected in setting overrides: ${cycle.joinToString(" -> ") { it.name }}")
