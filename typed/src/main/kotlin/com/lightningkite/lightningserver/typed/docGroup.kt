package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.definition.ListRegistryExtension
import com.lightningkite.lightningserver.definition.MutableExtensions
import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.definition.builder.ListRegistry
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.getValue
import com.lightningkite.lightningserver.pathing.MutablePathSpecMap
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpecMap


private object DocGroupExtension : MutableExtensions.DegradingKey<MutablePathSpecMap<String>, PathSpecMap<String>> {
    override fun default(): MutablePathSpecMap<String> = MutablePathSpecMap()
    override fun MutablePathSpecMap<String>.include(other: PathSpecMap<String>) {
        for(entry in other.entries) this[entry.key] = entry.value
    }
}

public val ServerBuilder.docGroupings: MutablePathSpecMap<String> by DocGroupExtension
public val ServerDefinition.docGroupings: PathSpecMap<String> by DocGroupExtension

context(definition: ServerDefinition)
public val PathSpec.docGroup: String?
    get() = generateSequence(this) { it.parent }.firstNotNullOfOrNull { definition.docGroupings[it] }

context(definition: ServerBuilder)
public var PathSpec.docGroup: String?
    get() = generateSequence(this) { it.parent }.firstNotNullOfOrNull { definition.docGroupings[it] }
    set(value) {
        definition.docGroupings[this] = value ?: ""
    }