package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.lightningserver.LightningServerDsl
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.typed.sdk.SdkModule.Companion.withSdkInfo
import kotlin.uuid.Uuid

public data class SdkModule<S>(
    val value: S,
    val info: Info,
) {
    public data class Info(
        val interfaceName: String,
        val valueName: String = interfaceName.camelCase()
    )

    public constructor(value: S, interfaceName: String, valueName: String) : this(value, Info(interfaceName, valueName))

    private object Default : MutableExtensions.Key<Info>

    public companion object {
        public var SdkSettings.defaultInfo: Info? by Default

        public fun <S : ServerBuilder> S.withSdkInfo(
            interfaceName: String = sdkSettings.defaultInfo?.interfaceName ?: this::class.simpleName?.let { it.pascalCase() + "Api" } ?: throw IllegalArgumentException("Cannot infer name for anonymous object"),
            valueName: String = sdkSettings.defaultInfo?.valueName ?: interfaceName.camelCase().removeSuffix("Api")
        ) : SdkModule<S> =
            SdkModule(this, interfaceName, valueName)

        public fun ServerDefinition.withSdkInfo(
            interfaceName: String,
            valueName: String = interfaceName.camelCase()
        ) : SdkModule<ServerDefinition> =
            SdkModule(this, interfaceName, valueName)
    }
}

@LightningServerDsl
context(builder: ServerBuilder)
public infix fun <S : ServerBuilder> PathSpec0.module(module: SdkModule<S>): S {
    builder.extensions[ModuleRegistry][module.value.sdkId] = module.info
    return with(builder) { include(module.value) }
}

@LightningServerDsl
context(builder: ServerBuilder)
public infix fun <S : ServerBuilder> PathSpec0.module(module: S): S = module(module.withSdkInfo())

@LightningServerDsl
context(builder: ServerBuilder)
public infix fun PathSpec0.module(module: SdkModule<ServerDefinition>): ServerDefinition {
    val (mod, id) = module.value.withSdkId()
    builder.extensions[ModuleRegistry][id] = module.info
    return with(builder) { include(mod) }
}



// Implementation details

private object SdkId : MutableExtensions.Key<Uuid>

private val ServerBuilder.sdkId get() = extensions.getOrPut(SdkId) { Uuid.random() }

private val ServerDefinition.Module.sdkId get() = extensions[SdkId]
private fun ServerDefinition.withSdkId(): Pair<ServerDefinition, Uuid> {
    thisLayer.extensions[SdkId]?.let { return this to it }
    val id = Uuid.random()
    val copied = copy(
        thisLayer = thisLayer.copy(
            extensions = extensions.toMutableExtensions().apply { set(SdkId, id) }
        ),
    )
    return copied to id
}

private object ModuleRegistry : MutableExtensions.DegradingKey<MutableMap<Uuid, SdkModule.Info>, Map<Uuid, SdkModule.Info>> {
    override fun default(): MutableMap<Uuid, SdkModule.Info> = HashMap()
    override fun MutableMap<Uuid, SdkModule.Info>.include(other: Map<Uuid, SdkModule.Info>, pathSpec: PathSpec0) {
        /*No-op, we want to keep registered modules specific per-module, not cascading.*/
    }
}

internal fun ServerDefinition.Module.getModuleInfo(other: ServerDefinition.Module): SdkModule.Info? {
    val id = other.sdkId ?: return null
    return extensions[ModuleRegistry]?.get(id)
}