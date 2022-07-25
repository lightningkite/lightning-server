package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningserver.aws.AwsAdapter
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.Settings
import kotlinx.serialization.decodeFromString

class AwsHandler: AwsAdapter() {
    companion object {
        init {
            Server
            cache
            Serialization.json.decodeFromString<Settings>(System.getenv("LIGHTNING_SERVER_SETTINGS")!!)
        }
    }
    init {
        Companion
    }
}