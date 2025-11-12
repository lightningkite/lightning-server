package com.lightningkite.lightningserver.media

import com.lightningkite.lightningserver.db.DatabaseSettings
import com.lightningkite.lightningserver.engine.UnitTestEngine
import com.lightningkite.lightningserver.engine.engine
import com.lightningkite.lightningserver.files.FilesSettings
import com.lightningkite.lightningserver.logging.LoggingSettings
import com.lightningkite.lightningserver.logging.loggingSettings
import com.lightningkite.lightningserver.settings.GeneralServerSettings
import com.lightningkite.lightningserver.settings.Settings
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.settings.setting
import com.lightningkite.lightningserver.tasks.Tasks
import com.lightningkite.prepareModelsServerCore
import com.lightningkite.prepareModelsShared
import com.lightningkite.prepareModelsServerMediaTest
import kotlinx.coroutines.runBlocking

object TestSettings {
    val database = setting("database", DatabaseSettings("ram"))
    val files = setting("files", FilesSettings())

    init {
        prepareModelsShared()
        prepareModelsServerCore()
        prepareModelsServerMediaTest()
        Settings.populateDefaults(
            mapOf(
                generalSettings.name to GeneralServerSettings(debug = true),
                loggingSettings.name to LoggingSettings(
                    default = LoggingSettings.ContextSettings(
                        filePattern = null,
                        toConsole = true,
                        level = "DEBUG"
                    )
                )
            )
        )
        runBlocking {
            Tasks.onSettingsReady()
            engine = UnitTestEngine
            Tasks.onEngineReady()
        }
    }
}