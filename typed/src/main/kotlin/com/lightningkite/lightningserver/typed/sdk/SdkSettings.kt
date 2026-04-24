package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.lightningserver.definition.Extendable
import com.lightningkite.lightningserver.definition.builder.ServerBuilder

@JvmInline
public value class SdkSettings internal constructor(private val builder: ServerBuilder) : Extendable by builder

public val ServerBuilder.sdkSettings: SdkSettings get() = SdkSettings(this)