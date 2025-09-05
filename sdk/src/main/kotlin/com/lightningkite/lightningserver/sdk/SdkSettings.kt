package com.lightningkite.lightningserver.sdk

import com.lightningkite.lightningserver.definition.Extendable
import com.lightningkite.lightningserver.definition.builder.ServerBuilder

@JvmInline
public value class SdkSettings internal constructor(private val builder: ServerBuilder): Extendable by builder

public val ServerBuilder.sdk: SdkSettings get() = SdkSettings(this)