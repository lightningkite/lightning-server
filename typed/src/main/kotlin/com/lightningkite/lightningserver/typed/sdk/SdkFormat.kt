package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.services.data.KFile

public interface SdkFormat {
    public fun write(data: SdkServerDefinition, folder: KFile, packageName: String)
}