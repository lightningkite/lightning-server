package com.lightningkite.lightningserver.schema

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.db.ModelRestEndpoints
import com.lightningkite.lightningserver.files.UploadEarlyEndpoint
import com.lightningkite.lightningserver.jsonschema.JsonSchemaBuilder
import com.lightningkite.lightningserver.jsonschema.LightningServerSchema
import com.lightningkite.lightningserver.jsonschema.LightningServerSchemaEndpoint
import com.lightningkite.lightningserver.jsonschema.LightningServerSchemaModel
import com.lightningkite.lightningserver.kabobCase
import com.lightningkite.lightningserver.routes.fullUrl
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.titleCase
import com.lightningkite.lightningserver.typed.Documentable
import com.lightningkite.lightningserver.typed.docGroup
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.elementNames


val lightningServerKSchema: LightningServerKSchema by lazy {
    val vtypes = Documentable.endpoints.flatMap {
        sequenceOf(it.inputType, it.outputType) + it.route.path.serializers.asSequence()
    }.distinctBy { KSerializerKey(it) }.map { it.makeVirtualType() }
    LightningServerKSchema(
        uploadEarlyEndpoint = UploadEarlyEndpoint.default?.path?.fullUrl(),
        endpoints = Documentable.endpoints.map {
            LightningServerKSchemaEndpoint(
                group = it.docGroup,
                method = it.route.method.toString(),
                path = it.path.path.toString(),
                routes = it.route.path.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>().map { it.name }.zip(it.route.path.serializers.map { it.virtualTypeReference() }).associate { it },
                input = it.inputType.virtualTypeReference(),
                output = it.outputType.virtualTypeReference(),
            )
        }.toList(),
        models = ModelRestEndpoints.all.associate {
            it.collectionName.kabobCase() to LightningServerKSchemaModel(
                collectionName = it.collectionName.titleCase(),
                url = it.path.fullUrl(),
                type = it.info.serialization.serializer.virtualTypeReference(),
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
        enums = vtypes.filterIsInstance<VirtualEnum>().associate { it.serialName to it },
        structures = vtypes.filterIsInstance<VirtualStructure>().associate { it.serialName to it },
    )
}