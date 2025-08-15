package com.lightningkite.lightningserver.sdk

import com.lightningkite.lightningserver.definition.MutableExtensions
import com.lightningkite.lightningserver.definition.builder.ListRegistry
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.getValue
import kotlinx.serialization.KSerializer
import kotlin.reflect.KClass
import kotlin.reflect.KTypeParameter

public abstract class ClientInterfaceBuilder : ServerBuilder() {
    public interface SdkSettings {
        public val imports: List<String>
        public val name: String
        public val typeArguments: List<KSerializer<*>>

        public companion object : MutableExtensions.DegradingKey<Builder, SdkSettings> {
            override fun default(): Builder = Builder()
            override fun Builder.include(other: SdkSettings) { /*SdkSettings are module specific*/ }
        }

        public class Builder : SdkSettings {
            override val imports: ListRegistry<String> = ListRegistry()
            override var name: String =  ""
            override var typeArguments: List<KSerializer<*>> = emptyList()
        }
    }


}