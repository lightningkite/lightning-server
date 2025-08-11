package com.lightningkite.lightningserver.settings

import com.lightningkite.lightningserver.definition.ServerSetting
import java.io.File

public class IncompleteSettingsException(public val missing: Set<ServerSetting<*, *>>, public val suggestedFile: File) :
    Exception("Missing keys ${missing.joinToString { it.settingName }}.  Created suggested settings at ${suggestedFile.absolutePath}")