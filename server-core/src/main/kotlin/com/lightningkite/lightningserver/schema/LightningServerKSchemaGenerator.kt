package com.lightningkite.lightningserver.schema

import com.lightningkite.lightningdb.*
import com.lightningkite.serialization.*
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.db.ModelRestEndpoints
import com.lightningkite.lightningserver.kabobCase
import com.lightningkite.lightningserver.routes.fullUrl
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.titleCase
import com.lightningkite.lightningserver.typed.Documentable
import com.lightningkite.lightningserver.typed.docGroup
import com.lightningkite.registerShared
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.elementNames


val lightningServerKSchema: LightningServerKSchema by lazy {
    val registry = SerializationRegistry(Serialization.module).also {
        it.registerShared()
    }
    Documentable.endpoints.flatMap {
        sequenceOf(it.inputType, it.outputType) + it.route.path.serializers.asSequence()
    }.forEach {
        registry.registerVirtual(it)
    }
    LightningServerKSchema(
        baseUrl = generalSettings().publicUrl,
        baseWsUrl = generalSettings().wsUrl,
        endpoints = Documentable.endpoints.map {
            LightningServerKSchemaEndpoint(
                group = it.docGroup,
                method = it.route.method.toString(),
                path = it.path.path.toString(),
                routes = it.route.path.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>().map { it.name }.zip(it.route.path.serializers.map { it.virtualTypeReference(registry) }).associate { it },
                input = it.inputType.virtualTypeReference(registry),
                output = it.outputType.virtualTypeReference(registry),
            )
        }.toList() + Documentable.websockets.map {
            LightningServerKSchemaEndpoint(
                group = it.docGroup,
                method = "WEBSOCKET",
                path = it.path.path.toString(),
                routes = it.path.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>().map { it.name }.zip(it.path.serializers.map { it.virtualTypeReference(registry) }).associate { it },
                input = it.inputType.virtualTypeReference(registry),
                output = it.outputType.virtualTypeReference(registry),
            )
        },
        models = ModelRestEndpoints.all.associate {
            it.collectionName.kabobCase() to LightningServerKSchemaModel(
                collectionName = it.collectionName.titleCase(),
                path = it.path.toString(),
                type = it.info.serialization.serializer.virtualTypeReference(registry),
                searchFields = it.info.serialization.serializer.descriptor.annotations
                    .filterIsInstance<AdminSearchFields>()
                    .firstOrNull()
                    ?.fields?.toList()
                    ?: it.info.serialization.serializer.descriptor.let {
                        (0 until it.elementsCount)
                            .filter { index -> it.getElementDescriptor(index).kind == PrimitiveKind.STRING }
                            .map { index -> it.getElementName(index) }
                    },
                tableColumns = it.info.serialization.serializer.descriptor.annotations
                    .filterIsInstance<AdminTableColumns>()
                    .firstOrNull()
                    ?.fields?.toList()
                    ?: it.info.serialization.serializer.descriptor.let {
                        (0 until it.elementsCount)
                            .take(3)
                            .map { index -> it.getElementName(index) }
                    },
                titleFields = it.info.serialization.serializer.descriptor.annotations
                    .filterIsInstance<AdminTitleFields>()
                    .firstOrNull()
                    ?.fields?.toList()
                    ?: it.info.serialization.serializer.descriptor.elementNames.toSet().let {
                        when {
                            it.contains("name") -> listOf("name")
                            it.contains("key") -> listOf("key")
                            else -> listOf("_id")
                        }
                    }
            )
        },
        enums = @Suppress("UNCHECKED_CAST") registry.virtualTypes.filterValues { it is VirtualEnum } as Map<String, VirtualEnum>,
        structures = @Suppress("UNCHECKED_CAST") registry.virtualTypes.filterValues { it is VirtualStruct } as Map<String, VirtualStruct>,
    )
}