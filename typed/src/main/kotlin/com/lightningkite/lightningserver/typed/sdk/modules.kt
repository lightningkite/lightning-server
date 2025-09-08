package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.lightningserver.LightningServerDsl
import com.lightningkite.lightningserver.definition.ModularServerDefinition
import com.lightningkite.lightningserver.definition.MutableExtensions
import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.getOrPut
import com.lightningkite.lightningserver.definition.getValue
import com.lightningkite.lightningserver.definition.setValue
import com.lightningkite.lightningserver.definition.toMutableExtensions
import com.lightningkite.lightningserver.pathing.PathSpec0
import kotlin.collections.set
import kotlin.uuid.Uuid

public data class SdkModuleInfo(
    val interfaceName: String,
    val valueName: String = interfaceName.camelCase()
)

private object SdkId : MutableExtensions.Key<Uuid>

private val ServerBuilder.sdkId get() = extensions.getOrPut(SdkId) { Uuid.random() }
private val ServerDefinition.sdkId get() = extensions[SdkId]
private fun ServerDefinition.withSdkId(): Pair<ServerDefinition, Uuid> {
    extensions[SdkId]?.let { return this to it }
    val id = Uuid.random()
    val copied = copy(
        extensions = extensions.toMutableExtensions().apply { set(SdkId, id) }
    )
    return copied to id
}

private object ModuleRegistry : MutableExtensions.DegradingKey<MutableMap<Uuid, SdkModuleInfo>, Map<Uuid, SdkModuleInfo>> {
    override fun default(): MutableMap<Uuid, SdkModuleInfo> = HashMap()
    override fun MutableMap<Uuid, SdkModuleInfo>.include(other: Map<Uuid, SdkModuleInfo>, pathSpec: PathSpec0) {
        /*No-op, we want to keep registered modules specific per-module, not cascading.*/
    }
}

internal fun ServerDefinition.getModuleInfo(other: ServerDefinition): SdkModuleInfo? {
    val id = other.sdkId ?: return null
    return extensions[ModuleRegistry]?.get(id)
}

private object DefaultInterfaceName : MutableExtensions.Key<SdkModuleInfo>
public var SdkSettings.defaultInfo: SdkModuleInfo? by DefaultInterfaceName


@LightningServerDsl
context(builder: ServerBuilder)
public fun <S : ServerBuilder> module(
    module: S,
    interfaceName: String = module.sdkSettings.defaultInfo?.interfaceName ?: module::class.simpleName?.let { it.pascalCase() + "Api" } ?: throw IllegalArgumentException("Cannot infer name for anonymous object"),
    valueName: String = module.sdkSettings.defaultInfo?.valueName ?: interfaceName.camelCase().removeSuffix("Api")
) : S {
    builder.extensions[ModuleRegistry][module.sdkId] = SdkModuleInfo(interfaceName, valueName)
    return module
}

@LightningServerDsl
context(builder: ServerBuilder)
public fun module(
    module: ModularServerDefinition,
    interfaceName: String,
    valueName: String = interfaceName.camelCase()
) : ModularServerDefinition {
    val (def, id) = module.definition.withSdkId()
    builder.extensions[ModuleRegistry][id] = SdkModuleInfo(interfaceName, valueName)
    return module.copy(definition = def)
}

@LightningServerDsl
context(builder: ServerBuilder)
public fun module(
    module: ServerDefinition,
    interfaceName: String,
    valueName: String = interfaceName.camelCase()
) : ServerDefinition {
    val (mod, id) = module.withSdkId()
    builder.extensions[ModuleRegistry][id] = SdkModuleInfo(interfaceName, valueName)
    return mod
}