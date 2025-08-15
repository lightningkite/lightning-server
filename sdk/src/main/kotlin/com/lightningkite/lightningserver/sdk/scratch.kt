package com.lightningkite.lightningserver.sdk

import com.lightningkite.lightningserver.LightningServerDsl
import com.lightningkite.lightningserver.definition.ModularServerDefinition
import com.lightningkite.lightningserver.definition.MutableExtensions
import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.getValue
import com.lightningkite.lightningserver.definition.setValue
import com.lightningkite.lightningserver.definition.toMutableExtensions
import kotlin.reflect.KClass

public data class SdkModuleInfo(
    val interfaceName: String,
    val valueName: String
) {
    public companion object : MutableExtensions.Key<SdkModuleInfo>
}

@LightningServerDsl
public fun <S : ServerBuilder> module(
    module: S,
    interfaceName: String = module::class.simpleName?.pascalCase() ?: throw IllegalArgumentException("Cannot infer name for anonymous object"),
    valueName: String = interfaceName.camelCase()
) : S {
    module.extensions[SdkModuleInfo] = SdkModuleInfo(interfaceName, valueName)
    return module
}

@LightningServerDsl
public fun module(
    module: ModularServerDefinition,
    interfaceName: String,
    valueName: String = interfaceName.camelCase()
) : ModularServerDefinition =
    module.copy(
        definition = module.definition.copy(
            extensions = module.definition.extensions.toMutableExtensions().also {
                it[SdkModuleInfo] = SdkModuleInfo(interfaceName, valueName)
            }
        )
    )

@LightningServerDsl
public fun module(
    module: ServerDefinition,
    interfaceName: String,
    valueName: String = interfaceName.camelCase()
) : ServerDefinition =
    module.copy(
        extensions = module.extensions.toMutableExtensions().also {
            it[SdkModuleInfo] = SdkModuleInfo(interfaceName, valueName)
        }
    )

