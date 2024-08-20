package com.lightningkite.lightningserver.schema

import com.lightningkite.lightningdb.VirtualEnum
import com.lightningkite.lightningdb.VirtualStructure
import com.lightningkite.lightningdb.VirtualTypeReference
import kotlinx.serialization.Serializable


@Serializable
data class LightningServerKSchema(
    val uploadEarlyEndpoint: String? = null,
    val structures: Map<String, VirtualStructure>,
    val enums: Map<String, VirtualEnum>,
    val endpoints: List<LightningServerKSchemaEndpoint>,
    val models: Map<String, LightningServerKSchemaModel>,
)

@Serializable
data class LightningServerKSchemaModel(
    val collectionName: String,
    val type: VirtualTypeReference,
    val url: String,
    val searchFields: List<String>,
    val tableColumns: List<String>,
    val titleFields: List<String>,
)

@Serializable
data class LightningServerKSchemaEndpoint(
    val group: String? = null,
    val method: String,
    val path: String,
    val routes: Map<String, VirtualTypeReference>,
    val input: VirtualTypeReference,
    val output: VirtualTypeReference,
)