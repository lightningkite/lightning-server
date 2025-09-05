package com.lightningkite.lightningserver.sdk

import com.lightningkite.lightningserver.definition.MutableExtensions
import com.lightningkite.lightningserver.definition.getValue
import com.lightningkite.lightningserver.definition.setValue
import kotlinx.serialization.KSerializer

public data class InterfaceInfo(
    val name: String,
    val typeParameters: List<KSerializer<*>> = emptyList(),
    val imports: Set<String> = emptySet()
) {
    public companion object : MutableExtensions.Key<InterfaceInfo>
}

public var SdkSettings.clientInterface: InterfaceInfo? by InterfaceInfo