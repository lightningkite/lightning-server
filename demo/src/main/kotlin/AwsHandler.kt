package com.lightningkite.lightningserverdemo

import com.lightningkite.lightningserver.aws.AwsAdapter
import com.lightningkite.lightningserver.metrics.CloudwatchMetrics
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.Settings
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.decodeFromStream
import software.amazon.awssdk.services.s3.S3Client

class AwsHandler : AwsAdapter() {
    companion object {
        init {
            CloudwatchMetrics
            Server
            preventLambdaTimeoutReuse = true
            loadSettings()
        }
    }

    init {
        Companion
    }
}