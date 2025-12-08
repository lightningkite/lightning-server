package com.lightningkite.lightningserver.settings

import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.services.data.KFile
import kotlinx.io.files.Path
import java.io.File

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
public class IncompleteSettingsException(public val missing: Set<ServerSetting<*, *>>, public val suggestedFile: KFile) :
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
