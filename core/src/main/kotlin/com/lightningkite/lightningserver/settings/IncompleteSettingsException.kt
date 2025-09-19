package com.lightningkite.lightningserver.settings

import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.services.data.KFile
import kotlinx.io.files.Path
import java.io.File

public class IncompleteSettingsException(public val missing: Set<ServerSetting<*, *>>, public val suggestedFile: KFile) :
    Exception("Missing keys ${missing.joinToString { it.name }}. Created suggested settings at ${suggestedFile.resolved.path}")

public class MissingSettingFile(suggestedFile: KFile) :
    Exception("Settings file does not exists. Created file at ${suggestedFile.resolved.path}")
