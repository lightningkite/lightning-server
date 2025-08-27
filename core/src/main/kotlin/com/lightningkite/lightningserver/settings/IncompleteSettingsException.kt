package com.lightningkite.lightningserver.settings

import com.lightningkite.lightningserver.definition.ServerSetting
import java.io.File

public class IncompleteSettingsException(public val missing: Set<ServerSetting<*, *>>, public val suggestedFile: File) :
    Exception("Missing keys ${missing.joinToString { it.name }}. Created suggested settings at ${suggestedFile.absolutePath}")

public class MissingSettingFile(suggestedFile: File) :
    Exception("Settings file does not exists. Created file at ${suggestedFile.absolutePath}")
