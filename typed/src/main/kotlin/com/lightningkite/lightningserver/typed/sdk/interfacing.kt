package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.lightningserver.definition.*
import kotlinx.serialization.KSerializer
import kotlin.reflect.KClass

public data class InterfaceInfo(
    val type: KClass<*>,
    val typeParameters: List<KSerializer<*>> = emptyList(),
    val imports: Set<String> = emptySet(),
) {
    init {
        require(type.typeParameters.size == typeParameters.size) {
            """
                InterfaceInfo requires a KSerializer for each type parameter.
                
                Type parameters: ${type.typeParameters}, provided: ${typeParameters.joinToString { "KSerializer(${it.descriptor.serialName})" }}
            """.trimIndent()
        }
    }

    public companion object : MutableExtensions.Key<InterfaceInfo>

    public fun kotlinString(qualified: Boolean = true): String {
        val params = typeParameters
            .takeUnless { it.isEmpty() }
            ?.joinToString(prefix = "<", postfix = ">") { it.kotlinTypeString() }
            ?: ""

        val name = (if (qualified) type.qualifiedName else type.simpleName)
            ?: throw IllegalArgumentException("Cannot find ${if (qualified) "qualified " else ""}name for $type")

        return name + params
    }
}

public fun KClass<*>.info(vararg typeParams: KSerializer<*>): InterfaceInfo = InterfaceInfo(this, typeParams.toList())

public var SdkSettings.clientInterface: InterfaceInfo? by InterfaceInfo

