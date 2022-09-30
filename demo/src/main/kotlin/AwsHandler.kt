package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningserver.aws.AwsAdapter
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.Settings
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

class AwsHandler : AwsAdapter() {
    companion object {
        init {
            Server
            System.getenv("LIGHTNING_SERVER_SETTINGS")?.let {
                Serialization.Internal.json.decodeFromString<Settings>(it)
            } ?: run {
                val compiled = JsonObject(Settings.requirements.entries.associate {
                    it.key to Serialization.Internal.json.decodeFromString(
                        System.getenv("LIGHTNING_SERVER_SETTINGS_${it.key}")
                    )
                })
                Serialization.Internal.json.decodeFromJsonElement<Settings>(compiled)
            }
        }
    }

    init {
        Companion
    }
}