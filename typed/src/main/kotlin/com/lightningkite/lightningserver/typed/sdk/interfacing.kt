package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.lightningserver.definition.MutableExtensions
import com.lightningkite.lightningserver.definition.getValue
import com.lightningkite.lightningserver.definition.setValue
import kotlinx.serialization.KSerializer
import kotlin.reflect.KClass

public data class InterfaceInfo(
    val type: KClass<*>,
    val typeParameters: List<KSerializer<*>> = emptyList(),
    val imports: Set<String> = emptySet()
) {
    public companion object : MutableExtensions.Key<InterfaceInfo>

    public fun kotlinString(): String {
        val name = type.qualifiedName ?: type.simpleName ?: return ""
        val params = typeParameters
            .takeUnless { it.isEmpty() }
            ?.joinToString(prefix = "<", postfix = ">") { it.descriptor.serialName }
            ?: ""

        return name + params
    }
}

public fun KClass<*>.info(vararg typeParams: KSerializer<*>): InterfaceInfo = InterfaceInfo(this, typeParams.toList())

public var SdkSettings.clientInterface: InterfaceInfo? by InterfaceInfo